package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/** Maps RAR3/RAR4 classic-LZ length and distance symbols for the first-party path. */
final class Rar3LengthDistanceDecoder {
    private static final int[] LONG_LENGTH_BASE = {
            0, 1, 2, 3, 4, 5, 6, 7,
            8, 10, 12, 14, 16, 20, 24, 28,
            32, 40, 48, 56, 64, 80, 96, 112,
            128, 160, 192, 224
    };

    private static final int[] LONG_LENGTH_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4,
            5, 5, 5, 5
    };

    private static final int[] REPEAT_LENGTH_BASE = {
            0, 1, 2, 3, 4, 5, 6, 7,
            8, 10, 12, 14, 16, 20, 24, 28,
            32, 40, 48, 56, 64, 80, 96, 112,
            128, 160, 192, 224
    };

    private static final int[] REPEAT_LENGTH_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4,
            5, 5, 5, 5
    };

    private static final int[] SHORT_DISTANCE_BASE = {0, 4, 8, 16, 32, 64, 128, 192};
    private static final int[] SHORT_DISTANCE_BITS = {2, 2, 3, 4, 5, 6, 6, 6};

    private Rar3LengthDistanceDecoder() {}

    static Match decodeLongMatch(@NonNull Rar3DecodeAction action,
                                 @NonNull Rar3HuffmanTables tables,
                                 @NonNull RarBitInput input,
                                 @NonNull Rar3UnpackState state) throws IOException {
        int length = decodeLengthSlot(action.slot, LONG_LENGTH_BASE, LONG_LENGTH_BITS, input) + 3;
        Distance distance = decodeDistance(tables, input, state);
        length += longDistanceLengthCorrection(distance.value);
        return Match.longDistance(action.slot, distance, length);
    }

    static Match decodeShortDistanceMatch(@NonNull Rar3DecodeAction action,
                                          @NonNull RarBitInput input) throws IOException {
        if (action.slot < 0 || action.slot >= SHORT_DISTANCE_BASE.length) {
            throw new IOException("Invalid RAR3/RAR4 short-distance slot");
        }
        int extraBits = SHORT_DISTANCE_BITS[action.slot];
        int distance = SHORT_DISTANCE_BASE[action.slot]
                + (extraBits == 0 ? 0 : input.readBits(extraBits))
                + 1;
        return Match.shortDistance(action.slot, extraBits, distance, 2);
    }

    static Match decodeOldDistanceMatch(@NonNull Rar3DecodeAction action,
                                        @NonNull Rar3HuffmanTables tables,
                                        @NonNull RarBitInput input,
                                        @NonNull Rar3UnpackState state) throws IOException {
        int distance = state.oldDistance(action.slot);
        if (distance <= 0) {
            throw new IOException("RAR3/RAR4 old-distance match has no previous distance");
        }
        int lengthSlot = tables.repeatTable.decode(input);
        // Old-distance repeats do not apply long-distance length corrections.
        int length = decodeLengthSlot(lengthSlot, REPEAT_LENGTH_BASE, REPEAT_LENGTH_BITS, input) + 2;
        return Match.oldDistance(action.slot, lengthSlot, distance, length);
    }

    static Match repeatLastMatch(@NonNull Rar3UnpackState state) throws IOException {
        int distance = state.lastDistance();
        int length = state.lastLength();
        if (distance <= 0 || length <= 0) {
            throw new IOException("RAR3/RAR4 repeat-last match has no previous match");
        }
        return Match.repeatLast(distance, length);
    }

    static int decodeLengthSlotForTest(int slot) throws IOException {
        return decodeLengthSlot(slot, LONG_LENGTH_BASE, LONG_LENGTH_BITS, new RarBitInput(new byte[] {0})) + 3;
    }

    static int distanceBaseForSlotForTest(int slot) throws IOException {
        if (slot < 0 || slot >= Rar3HuffmanTables.DC) {
            throw new IOException("Invalid RAR3/RAR4 distance slot");
        }
        return distanceBase(slot) + 1;
    }

    static int distanceExtraBitsForTest(int slot) throws IOException {
        if (slot < 0 || slot >= Rar3HuffmanTables.DC) {
            throw new IOException("Invalid RAR3/RAR4 distance slot");
        }
        return distanceExtraBits(slot);
    }

    private static int decodeLengthSlot(int slot,
                                        @NonNull int[] bases,
                                        @NonNull int[] extraBits,
                                        @NonNull RarBitInput input) throws IOException {
        if (slot < 0 || slot >= bases.length) {
            throw new IOException("Invalid RAR3/RAR4 length slot");
        }
        int bits = extraBits[slot];
        return bases[slot] + (bits == 0 ? 0 : input.readBits(bits));
    }

    @NonNull
    private static Distance decodeDistance(@NonNull Rar3HuffmanTables tables,
                                           @NonNull RarBitInput input,
                                           @NonNull Rar3UnpackState state) throws IOException {
        int slot = tables.distanceTable.decode(input);
        if (slot < 0 || slot >= Rar3HuffmanTables.DC) {
            throw new IOException("Invalid RAR3/RAR4 distance slot");
        }
        int bits = distanceExtraBits(slot);
        int distance = distanceBase(slot);
        int lowDistance = -1;
        boolean lowDistanceRepeated = false;
        if (bits > 0) {
            if (slot > 9) {
                if (bits > 4) {
                    distance += input.readBits(bits - 4) << 4;
                }
                LowDistance decodedLow = decodeLowDistance(tables, input, state);
                lowDistance = decodedLow.value;
                lowDistanceRepeated = decodedLow.repeated;
                distance += decodedLow.value;
            } else {
                distance += input.readBits(bits);
            }
        }
        return new Distance(distance + 1, slot, bits, lowDistance, lowDistanceRepeated);
    }

    @NonNull
    private static LowDistance decodeLowDistance(@NonNull Rar3HuffmanTables tables,
                                                 @NonNull RarBitInput input,
                                                 @NonNull Rar3UnpackState state) throws IOException {
        if (state.lowDistanceRepeatCount() > 0) {
            state.consumeRepeatedLowDistance();
            return new LowDistance(state.previousLowDistance(), true);
        }
        int lowDistance = tables.lowDistanceTable.decode(input);
        if (lowDistance == 16) {
            state.startLowDistanceRepeat(15);
            return new LowDistance(state.previousLowDistance(), true);
        }
        if (lowDistance < 0 || lowDistance > 15) {
            throw new IOException("Invalid RAR3/RAR4 low-distance slot");
        }
        state.rememberLowDistance(lowDistance);
        return new LowDistance(lowDistance, false);
    }

    private static int distanceBase(int slot) {
        if (slot < 4) return slot;
        int bits = distanceExtraBits(slot);
        return (2 + (slot & 1)) << bits;
    }

    private static int distanceExtraBits(int slot) {
        return slot < 4 ? 0 : (slot >> 1) - 1;
    }

    private static int longDistanceLengthCorrection(int distance) {
        int correction = 0;
        if (distance >= 0x2000) correction++;
        if (distance >= 0x40000) correction++;
        return correction;
    }

    private static final class Distance {
        final int value;
        final int slot;
        final int extraBits;
        final int lowDistance;
        final boolean lowDistanceRepeated;

        Distance(int value, int slot, int extraBits, int lowDistance, boolean lowDistanceRepeated) {
            this.value = value;
            this.slot = slot;
            this.extraBits = extraBits;
            this.lowDistance = lowDistance;
            this.lowDistanceRepeated = lowDistanceRepeated;
        }
    }

    private static final class LowDistance {
        final int value;
        final boolean repeated;

        LowDistance(int value, boolean repeated) {
            this.value = value;
            this.repeated = repeated;
        }
    }

    static final class Match {
        static final int KIND_NEW_DISTANCE = 0;
        static final int KIND_OLD_DISTANCE = 1;
        static final int KIND_REPEAT_LAST = 2;

        final int distance;
        final int length;
        final int kind;
        final int oldDistanceSlot;
        final int lengthSlot;
        final int distanceSlot;
        final int distanceExtraBits;
        final int lowDistance;
        final boolean lowDistanceRepeated;

        private Match(int distance,
                      int length,
                      int kind,
                      int oldDistanceSlot,
                      int lengthSlot,
                      int distanceSlot,
                      int distanceExtraBits,
                      int lowDistance,
                      boolean lowDistanceRepeated) {
            this.distance = distance;
            this.length = length;
            this.kind = kind;
            this.oldDistanceSlot = oldDistanceSlot;
            this.lengthSlot = lengthSlot;
            this.distanceSlot = distanceSlot;
            this.distanceExtraBits = distanceExtraBits;
            this.lowDistance = lowDistance;
            this.lowDistanceRepeated = lowDistanceRepeated;
        }

        static Match longDistance(int lengthSlot, @NonNull Distance distance, int length) {
            return new Match(distance.value, length, KIND_NEW_DISTANCE, -1, lengthSlot,
                    distance.slot, distance.extraBits, distance.lowDistance, distance.lowDistanceRepeated);
        }

        static Match shortDistance(int distanceSlot, int distanceExtraBits, int distance, int length) {
            return new Match(distance, length, KIND_NEW_DISTANCE, -1, -1,
                    distanceSlot, distanceExtraBits, -1, false);
        }

        static Match oldDistance(int slot, int lengthSlot, int distance, int length) {
            return new Match(distance, length, KIND_OLD_DISTANCE, slot, lengthSlot,
                    -1, -1, -1, false);
        }

        static Match repeatLast(int distance, int length) {
            return new Match(distance, length, KIND_REPEAT_LAST, -1, -1,
                    -1, -1, -1, false);
        }
    }
}
