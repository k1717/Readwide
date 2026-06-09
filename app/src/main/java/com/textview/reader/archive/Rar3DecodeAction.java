package com.textview.reader.archive;

final class Rar3DecodeAction {
    enum Type {
        LITERAL,
        END_BLOCK,
        VM_FILTER,
        REPEAT_LAST_MATCH,
        OLD_DISTANCE_MATCH,
        SHORT_DISTANCE_MATCH,
        LONG_MATCH
    }

    final Type type;
    final int literal;
    final int slot;
    final int baseLength;

    private Rar3DecodeAction(Type type, int literal, int slot, int baseLength) {
        this.type = type;
        this.literal = literal;
        this.slot = slot;
        this.baseLength = baseLength;
    }

    static Rar3DecodeAction literal(int value) {
        return new Rar3DecodeAction(Type.LITERAL, value & 0xff, -1, 1);
    }

    static Rar3DecodeAction endBlock() {
        return new Rar3DecodeAction(Type.END_BLOCK, -1, -1, 0);
    }

    static Rar3DecodeAction vmFilter() {
        return new Rar3DecodeAction(Type.VM_FILTER, -1, -1, 0);
    }

    static Rar3DecodeAction repeatLastMatch() {
        return new Rar3DecodeAction(Type.REPEAT_LAST_MATCH, -1, -1, 0);
    }

    static Rar3DecodeAction oldDistanceMatch(int index) {
        return new Rar3DecodeAction(Type.OLD_DISTANCE_MATCH, -1, index, 0);
    }

    static Rar3DecodeAction shortDistanceMatch(int slot, int length) {
        return new Rar3DecodeAction(Type.SHORT_DISTANCE_MATCH, -1, slot, length);
    }

    static Rar3DecodeAction longMatch(int slot) {
        return new Rar3DecodeAction(Type.LONG_MATCH, -1, slot, 0);
    }
}
