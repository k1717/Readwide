/*
 * Java adaptation of nwaples/rardecode vm.go and the VM portion of filters.go.
 * Copyright (c) 2015, Nicholas Waples. BSD-2-Clause.
 * Reference revision: 76ac572a52e7550ccbbc75395d2e08e39c069e36.
 * Full license: app/src/main/assets/open_source_licenses/rardecode_bsd2.txt.
 * Readwide changes: checked bytecode, explicit execution-budget/range errors,
 * cancellation and transactional persistent globals. No host I/O is exposed.
 */
package com.readwide.manager.archive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.readwide.manager.util.FileOperationProgress;

/** Fixed-address-space RAR3 filter VM; never executes native/host instructions. */
final class Rar3VirtualMachine {
    static final int MEMORY_SIZE = 0x40000;
    static final int GLOBAL_ADDRESS = 0x3c000;
    private static final int GLOBAL_SIZE = 0x2000, FIXED_GLOBAL_SIZE = 0x40;
    private static final int MASK = MEMORY_SIZE - 1;
    private static final int C = 1, Z = 2, S = 0x80000000;
    private static final int INSTRUCTION_BUDGET = 25_000_000;
    private static final int[] ARG_COUNT = {
            2,2,2,2,1,1,1,1,1,2,2,2,2,1,1,1,1,1,1,1,1,1,0,1,2,2,2,1,0,0,0,0,2,2,2,2,2,2,2,0};
    private static final boolean[] BYTE_MODE = {
            true,true,true,true,false,false,true,true,false,true,true,true,true,
            false,false,false,false,false,false,false,false,false,false,true,true,
            true,true,true,false,false,false,false,false,false,true,true,true,true,true,false};

    static final class Program {
        private final Instruction[] instructions;
        private final byte[] staticData;
        private byte[] savedGlobal = new byte[0];
        private int executionCount;
        Program(Instruction[] instructions, byte[] staticData) {
            this.instructions = instructions; this.staticData = staticData;
        }
        int instructionCount() { return instructions.length; }
    }

    private static final class Operand {
        // immediate, register, memory; memory can have a register and displacement.
        final int kind, register, value;
        Operand(int kind, int register, int value) {
            this.kind = kind; this.register = register; this.value = value;
        }
        int get(Rar3VirtualMachine vm, boolean small) {
            // Immediate operands retain their encoded width (including MOVZX).
            if (kind == 0) return value;
            int n = kind == 1 ? vm.r[register]
                    : vm.get32((value + (register < 0 ? 0 : vm.r[register])) & MASK);
            return small ? n & 255 : n;
        }
        void set(Rar3VirtualMachine vm, boolean small, int value) {
            if (kind == 0) return; // The VM defines immediate destinations as no-ops.
            if (kind == 1) {
                vm.r[register] = small ? (vm.r[register] & ~255) | (value & 255) : value;
            } else {
                int address = (this.value + (register < 0 ? 0 : vm.r[register])) & MASK;
                if (small) vm.memory[address] = (byte) value; else vm.put32(address, value);
            }
        }
    }

    private static final class Instruction {
        final int code;
        final boolean small;
        final Operand a, b;
        Instruction(int code, boolean small, Operand a, Operand b) {
            this.code = code; this.small = small; this.a = a; this.b = b;
        }
    }

    static Program compile(byte[] code) throws IOException {
        if (code == null || code.length < 2 || code.length > 0x10000) throw invalid("program size");
        int check = 0;
        for (int i = 1; i < code.length; i++) check ^= code[i] & 255;
        if (check != (code[0] & 255)) throw invalid("program checksum");
        Bits bits = new Bits(code);
        byte[] data = new byte[0];
        if (bits.read(1) != 0) {
            long count = (bits.number() & 0xffffffffL) + 1L;
            if (count > GLOBAL_SIZE - FIXED_GLOBAL_SIZE) throw invalid("static data size");
            data = new byte[(int) count];
            for (int i = 0; i < data.length; i++) data[i] = (byte) bits.read(8);
        }
        List<Instruction> result = new ArrayList<>();
        while (bits.remaining() > 0) {
            checkpoint();
            // The program is byte-sized but instructions are bit-sized. Only up
            // to seven zero padding bits may remain; other truncations are errors.
            if (bits.zeroPadding()) break;
            int codeNumber = bits.read(4);
            if ((codeNumber & 8) != 0) codeNumber = ((codeNumber << 2) | bits.read(2)) - 24;
            if (codeNumber >= ARG_COUNT.length) throw invalid("instruction");
            boolean small = BYTE_MODE[codeNumber] && bits.read(1) != 0;
            int arguments = ARG_COUNT[codeNumber];
            Operand a = arguments > 0 ? bits.operand(small) : null;
            Operand b = arguments > 1 ? bits.operand(small) : null;
            if (arguments == 1 && isJump(codeNumber) && a.kind == 0) {
                int jump = a.value;
                if (unsignedCompare(jump, 256) >= 0) jump -= 256;
                else {
                    if (jump >= 136) jump -= 264;
                    else if (jump >= 16) jump -= 8;
                    else if (jump >= 8) jump -= 16;
                    jump += result.size();
                }
                a = new Operand(0, -1, jump);
            }
            result.add(new Instruction(codeNumber, small, a, b));
        }
        if (result.isEmpty()) throw invalid("empty instruction list");
        return new Program(result.toArray(new Instruction[0]), data);
    }

    private static boolean isJump(int code) {
        return code == 4 || code == 5 || code == 8 || (code >= 13 && code <= 18) || code == 21;
    }

    static byte[] apply(Program program, byte[] input, int length, int[] registers,
                        byte[] global, long offset) throws IOException {
        return apply(program, input, length, registers, global, offset, INSTRUCTION_BUDGET, null);
    }

    static byte[] apply(Program program, byte[] input, int length, int[] registers,
                        byte[] global, long offset, FileOperationProgress progress) throws IOException {
        return apply(program, input, length, registers, global, offset, INSTRUCTION_BUDGET, progress);
    }

    // Package-private budget override exists solely for deterministic regression sources.
    static byte[] apply(Program program, byte[] input, int length, int[] registers,
                        byte[] global, long offset, int budget) throws IOException {
        return apply(program, input, length, registers, global, offset, budget, null);
    }

    static byte[] apply(Program program, byte[] input, int length, int[] registers,
                        byte[] global, long offset, int budget, FileOperationProgress progress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        if (program == null || input == null || registers == null || registers.length != 7
                || global == null || length < 0 || length > input.length || length > GLOBAL_ADDRESS
                || global.length > GLOBAL_SIZE - FIXED_GLOBAL_SIZE || offset < 0 || budget < 1) {
            throw invalid("execution range");
        }
        Rar3VirtualMachine vm = new Rar3VirtualMachine();
        System.arraycopy(input, 0, vm.memory, 0, length);
        System.arraycopy(registers, 0, vm.r, 0, 7);
        vm.r[7] = MEMORY_SIZE;
        for (int i = 0; i < 7; i++) vm.put32(GLOBAL_ADDRESS + i * 4, vm.r[i]);
        vm.put32(GLOBAL_ADDRESS + 0x1c, length);
        vm.put32(GLOBAL_ADDRESS + 0x24, (int) offset);
        vm.put32(GLOBAL_ADDRESS + 0x28, (int) (offset >>> 32));
        // This counter is distinct from R5, which a record may explicitly override.
        vm.put32(GLOBAL_ADDRESS + 0x2c, program.executionCount);
        vm.r[6] = (int) offset;
        byte[] user = program.savedGlobal.length > 0 ? program.savedGlobal : global;
        int room = GLOBAL_SIZE - FIXED_GLOBAL_SIZE;
        System.arraycopy(user, 0, vm.memory, GLOBAL_ADDRESS + FIXED_GLOBAL_SIZE, user.length);
        System.arraycopy(program.staticData, 0, vm.memory, GLOBAL_ADDRESS + FIXED_GLOBAL_SIZE + user.length,
                Math.min(room - user.length, program.staticData.length));
        vm.execute(program.instructions, budget, progress);
        int start = vm.get32(GLOBAL_ADDRESS + 0x20) & MASK;
        int size = vm.get32(GLOBAL_ADDRESS + 0x1c) & MASK;
        if (size > MEMORY_SIZE - start) throw invalid("output range");
        long globalSize = vm.get32(GLOBAL_ADDRESS + 0x30) & 0xffffffffL;
        if (globalSize > room) throw invalid("persistent global size");
        // Commit persistent globals only after successful execution and validation.
        byte[] output = Arrays.copyOfRange(vm.memory, start, start + size);
        byte[] savedGlobal = Arrays.copyOfRange(vm.memory, GLOBAL_ADDRESS + FIXED_GLOBAL_SIZE,
                GLOBAL_ADDRESS + FIXED_GLOBAL_SIZE + (int) globalSize);
        ArchiveCancellation.checkpoint(progress);
        program.savedGlobal = savedGlobal;
        program.executionCount++;
        return output;
    }

    private final int[] r = new int[8];
    // Four spill bytes match the VM's masked starting address for a word access.
    private final byte[] memory = new byte[MEMORY_SIZE + 4];
    private int flags;

    private void execute(Instruction[] commands, int budget, FileOperationProgress progress) throws IOException {
        int ip = 0;
        for (int steps = 0; steps < budget; steps++) {
            if ((steps & 1023) == 0) ArchiveCancellation.checkpoint(progress);
            if (ip < 0 || ip >= commands.length) return;
            Instruction ins = commands[ip];
            int next = ip + 1;
            boolean small = ins.small;
            int a = ins.a == null ? 0 : ins.a.get(this, small);
            int b = ins.b == null ? 0 : ins.b.get(this, small);
            int value;
            switch (ins.code) {
                case 0: ins.a.set(this, small, b); break;
                case 1: // CMP deliberately uses VM 32-bit flags, even for byte operands.
                case 3:
                    value = a - b; flags = value == 0 ? Z : (value & S) | (unsignedCompare(value, a) > 0 ? C : 0);
                    if (ins.code == 3) ins.a.set(this, small, value);
                    break;
                case 2:
                    value = a + b; if (small) value &= 255;
                    flags = value == 0 ? Z : (value & (small ? 128 : S)) != 0 ? S : 0;
                    if (unsignedCompare(value, a) < 0) flags |= C;
                    ins.a.set(this, small, value); break;
                case 4: if ((flags & Z) != 0) next = a; break;
                case 5: if ((flags & Z) == 0) next = a; break;
                case 6:
                    value = a + 1; if (small) value &= 255;
                    ins.a.set(this, small, value); flags = flagsFor(value); break;
                case 7: value = a - 1; ins.a.set(this, small, value); flags = flagsFor(value); break;
                case 8: next = a; break;
                case 9: case 10: case 11: case 12:
                    value = ins.code == 9 ? a ^ b : ins.code == 11 ? a | b : a & b;
                    flags = flagsFor(value);
                    if (ins.code != 12) ins.a.set(this, small, value);
                    break;
                case 13: if ((flags & S) != 0) next = a; break;
                case 14: if ((flags & S) == 0) next = a; break;
                case 15: if ((flags & C) != 0) next = a; break;
                case 16: if ((flags & (C | Z)) != 0) next = a; break;
                case 17: if ((flags & (C | Z)) == 0) next = a; break;
                case 18: if ((flags & C) == 0) next = a; break;
                case 19: r[7] -= 4; put32(r[7] & MASK, ins.a.get(this, false)); break;
                case 20: ins.a.set(this, false, get32(r[7] & MASK)); r[7] += 4; break;
                case 21: r[7] -= 4; put32(r[7] & MASK, ip + 1); next = ins.a.get(this, false); break;
                case 22:
                    if (unsignedCompare(r[7], MEMORY_SIZE) >= 0) return;
                    next = get32(r[7]); r[7] += 4; break;
                case 23: ins.a.set(this, small, ~a); break;
                case 24: case 25: case 26:
                    long shift = b & 0xffffffffL;
                    if (ins.code == 24) value = shift >= 32 ? 0 : a << (int) shift;
                    else if (ins.code == 25) value = shift >= 32 ? 0 : a >>> (int) shift;
                    else value = shift >= 32 ? a >> 31 : a >> (int) shift;
                    flags = flagsFor(value);
                    if (shift > 0 && shift <= 32 && (ins.code == 24
                            ? ((a << (int) (shift - 1)) & S) != 0
                            : ((a >>> (int) (shift - 1)) & 1) != 0)) flags |= C;
                    ins.a.set(this, small, value); break;
                case 27: value = -a; flags = value == 0 ? Z : (value & S) | C; ins.a.set(this, small, value); break;
                case 28:
                    int sp = r[7];
                    for (int register : r) { sp = (sp - 4) & MASK; put32(sp, register); }
                    r[7] = sp; break;
                case 29:
                    sp = r[7] & MASK;
                    for (int i = 7; i >= 0; i--) { r[i] = get32(sp); sp = (sp + 4) & MASK; }
                    break;
                case 30: r[7] -= 4; put32(r[7] & MASK, flags); break;
                case 31: flags = get32(r[7] & MASK); r[7] += 4; break;
                case 32: ins.a.set(this, false, ins.b.get(this, true)); break;
                case 33: ins.a.set(this, false, (byte) ins.b.get(this, true)); break;
                case 34: ins.a.set(this, small, b); ins.b.set(this, small, a); break;
                case 35: ins.a.set(this, small, a * b); break;
                case 36: if (b != 0) ins.a.set(this, small, (int) ((a & 0xffffffffL) / (b & 0xffffffffL))); break;
                case 37: case 38:
                    int carry = flags & C;
                    value = ins.code == 37 ? a + b + carry : a - b - carry;
                    if (small) value &= 255;
                    flags = flagsFor(value);
                    if ((ins.code == 37 ? unsignedCompare(value, a) < 0
                            : unsignedCompare(value, a) > 0) || (value == a && carry != 0)) flags |= C;
                    ins.a.set(this, small, value); break;
                case 39: break; // PRINT is intentionally inert; never expose host output.
                default: throw invalid("instruction");
            }
            ip = next;
        }
        if (ip >= 0 && ip < commands.length) throw invalid("instruction budget exhausted");
    }

    private static int unsignedCompare(int a, int b) { return Long.compare(a & 0xffffffffL, b & 0xffffffffL); }
    private static int flagsFor(int n) { return n == 0 ? Z : n & S; }
    private int get32(int p) {
        return (memory[p] & 255) | ((memory[p + 1] & 255) << 8)
                | ((memory[p + 2] & 255) << 16) | (memory[p + 3] << 24);
    }
    private void put32(int p, int n) { for (int i = 0; i < 4; i++) memory[p + i] = (byte) (n >>> (8 * i)); }
    private static void checkpoint() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
    }
    private static IOException invalid(String what) { return new IOException("Invalid RAR3 VM " + what); }

    private static final class Bits {
        private final byte[] data;
        private int position = 8; // The first byte is the XOR checksum.
        Bits(byte[] data) { this.data = data; }
        int remaining() { return data.length * 8 - position; }
        boolean zeroPadding() {
            if (remaining() > 7) return false;
            for (int bit = position; bit < data.length * 8; bit++) {
                if (((data[bit >>> 3] >>> (7 - (bit & 7))) & 1) != 0) return false;
            }
            return true;
        }
        int read(int count) throws IOException {
            if (count < 0 || count > 32 || count > remaining()) throw invalid("truncated bytecode");
            int value = 0;
            for (int i = 0; i < count; i++, position++) {
                value = (value << 1) | ((data[position >>> 3] >>> (7 - (position & 7))) & 1);
            }
            return value;
        }
        int number() throws IOException {
            switch (read(2)) {
                case 0: return read(4);
                case 1: int high = read(4); return high == 0 ? 0xffffff00 | read(8) : (high << 4) | read(4);
                case 2: return read(16);
                default: return read(32);
            }
        }
        Operand operand(boolean small) throws IOException {
            if (read(1) != 0) return new Operand(1, read(3), 0);
            if (read(1) == 0) return new Operand(0, -1, small ? read(8) : number());
            if (read(1) == 0) return new Operand(2, read(3), 0);
            if (read(1) == 0) { int register = read(3); return new Operand(2, register, number()); }
            return new Operand(2, -1, number() & MASK);
        }
    }
}
