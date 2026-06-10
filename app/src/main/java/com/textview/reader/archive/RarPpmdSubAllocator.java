package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;

/**
 * Small first-party PPMd suballocator scaffold.
 *
 * <p>RAR3/RAR4 PPMd uses a custom allocator for model contexts and state arrays. This
 * class does not implement the complete RAR PPMd allocator/model coupling; it provides a
 * bounded unit allocator so future PPMd context code has one place for memory ownership and
 * validation instead of scattering ad-hoc byte arrays through the unpacker.</p>
 */
final class RarPpmdSubAllocator {
    static final int UNIT_SIZE_BYTES = 12;
    private static final int MIN_UNITS = 1;
    private static final int MAX_UNITS = 128;

    @NonNull private final byte[] heap;
    @NonNull private final TreeMap<Integer, ArrayDeque<Integer>> freeByUnits = new TreeMap<>();
    private int usedBytes;

    RarPpmdSubAllocator(int memoryKilobytes) throws IOException {
        if (memoryKilobytes <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd suballocator memory must be positive: " + memoryKilobytes);
        }
        long bytes = memoryKilobytes * 1024L;
        if (bytes < UNIT_SIZE_BYTES || bytes > Integer.MAX_VALUE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd suballocator memory is outside supported bounds: "
                            + memoryKilobytes + " KiB");
        }
        heap = new byte[(int) bytes];
    }

    int capacityBytes() {
        return heap.length;
    }

    int usedBytes() {
        return usedBytes;
    }

    int availableBytes() {
        return heap.length - usedBytes + reusableBytes();
    }

    int reusableBytes() {
        int total = 0;
        for (Map.Entry<Integer, ArrayDeque<Integer>> entry : freeByUnits.entrySet()) {
            total += entry.getKey() * UNIT_SIZE_BYTES * entry.getValue().size();
        }
        return total;
    }

    int allocUnits(int units) throws IOException {
        validateUnits(units);
        Map.Entry<Integer, ArrayDeque<Integer>> reusable = freeByUnits.ceilingEntry(units);
        if (reusable != null) {
            int pointer = reusable.getValue().removeFirst();
            int reusableUnits = reusable.getKey();
            if (reusable.getValue().isEmpty()) freeByUnits.remove(reusableUnits);
            int remainder = reusableUnits - units;
            if (remainder > 0) {
                freeUnitsInternal(pointer + units * UNIT_SIZE_BYTES, remainder);
            }
            zero(pointer, units * UNIT_SIZE_BYTES);
            return pointer;
        }

        int bytes = units * UNIT_SIZE_BYTES;
        if (usedBytes > heap.length - bytes) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd suballocator exhausted: requested=" + units
                            + " units, availableBytes=" + availableBytes());
        }
        int pointer = usedBytes;
        usedBytes += bytes;
        zero(pointer, bytes);
        return pointer;
    }

    void freeUnits(int pointer, int units) throws IOException {
        validatePointer(pointer, units);
        freeUnitsInternal(pointer, units);
    }

    void writeByte(int pointer, int offset, int value) throws IOException {
        validateAddress(pointer, offset);
        heap[pointer + offset] = (byte) value;
    }

    int readByte(int pointer, int offset) throws IOException {
        validateAddress(pointer, offset);
        return heap[pointer + offset] & 0xff;
    }

    void clear() {
        usedBytes = 0;
        freeByUnits.clear();
    }

    private void freeUnitsInternal(int pointer, int units) {
        freeByUnits.computeIfAbsent(units, ignored -> new ArrayDeque<>()).addFirst(pointer);
    }

    private void validatePointer(int pointer, int units) throws IOException {
        validateUnits(units);
        int bytes = units * UNIT_SIZE_BYTES;
        if (pointer < 0 || pointer > heap.length - bytes || pointer % UNIT_SIZE_BYTES != 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd suballocator pointer is invalid: pointer=" + pointer
                            + ", units=" + units);
        }
    }

    private void validateUnits(int units) throws IOException {
        if (units < MIN_UNITS || units > MAX_UNITS) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd suballocator unit request is invalid: " + units);
        }
    }

    private void validateAddress(int pointer, int offset) throws IOException {
        if (pointer < 0 || offset < 0 || pointer >= heap.length || offset >= heap.length - pointer) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd suballocator address is invalid: pointer=" + pointer
                            + ", offset=" + offset);
        }
    }

    private void zero(int pointer, int bytes) {
        for (int i = 0; i < bytes; i++) heap[pointer + i] = 0;
    }
}
