/*
 * LEA block-cipher adaptation of alkegi/unegg src/lea.rs (MIT).
 * Copyright (c) 2026 Sungjoon Moon.
 * Source: 9929009ca4d010e8cd88bb796f7a1868162026d4.
 * Full permission notice: app/src/main/assets/open_source_licenses/unegg_mit.txt.
 */
package com.readwide.manager.archive;

import java.util.Arrays;

/** LEA-128/256 encryption primitive used only to generate EGG CTR keystream. */
final class EggLeaCipher {
    private static final int[] DELTA = {0xc3efe9db, 0x44626b02, 0x79e27c8a, 0x78df30ec,
            0x715ea49e, 0xc785da0a, 0xe04ef22a, 0xe5c40957};
    private static final int[] ROTATION = {1, 3, 6, 11, 13, 17};
    private final int[] keys;

    EggLeaCipher(byte[] key) {
        if (key.length != 16 && key.length != 32) throw new IllegalArgumentException("Invalid LEA key size");
        int[] t = new int[key.length / 4];
        for (int i = 0; i < t.length; i++) t[i] = get32(key, i * 4);
        int rounds = key.length == 16 ? 24 : 32;
        keys = new int[rounds * 6];
        for (int round = 0; round < rounds; round++) {
            int delta = DELTA[round % t.length];
            if (t.length == 4) {
                for (int j = 0; j < 4; j++) {
                    t[j] = Integer.rotateLeft(t[j] + Integer.rotateLeft(delta, round + j), ROTATION[j]);
                }
                keys[round * 6] = t[0]; keys[round * 6 + 1] = t[1];
                keys[round * 6 + 2] = t[2]; keys[round * 6 + 3] = t[1];
                keys[round * 6 + 4] = t[3]; keys[round * 6 + 5] = t[1];
            } else {
                for (int j = 0; j < 6; j++) {
                    int index = (round * 6 + j) & 7;
                    t[index] = Integer.rotateLeft(t[index] + Integer.rotateLeft(delta, round + j), ROTATION[j]);
                    keys[round * 6 + j] = t[index];
                }
            }
        }
        Arrays.fill(t, 0);
    }

    void encryptBlock(byte[] input, byte[] output) {
        if (input.length < 16 || output.length < 16) throw new IllegalArgumentException("Invalid LEA block");
        int a = get32(input, 0), b = get32(input, 4), c = get32(input, 8), d = get32(input, 12);
        for (int i = 0; i < keys.length; i += 6) {
            int x = Integer.rotateLeft((a ^ keys[i]) + (b ^ keys[i + 1]), 9);
            int y = Integer.rotateRight((b ^ keys[i + 2]) + (c ^ keys[i + 3]), 5);
            int z = Integer.rotateRight((c ^ keys[i + 4]) + (d ^ keys[i + 5]), 3);
            d = a; a = x; b = y; c = z;
        }
        put32(output, 0, a); put32(output, 4, b); put32(output, 8, c); put32(output, 12, d);
    }

    private static int get32(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16) | (b[p + 3] << 24);
    }
    private static void put32(byte[] b, int p, int value) {
        for (int i = 0; i < 4; i++) b[p + i] = (byte) (value >>> (i * 8));
    }
}
