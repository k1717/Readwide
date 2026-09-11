package com.readwide.manager.archive;

import java.util.Arrays;

/**
 * Streaming unkeyed BLAKE2sp-256 for RAR5 file hashes. Fixed-size working memory.
 * Tree parameters and compression follow Samuel Neves' BLAKE2 reference
 * (Copyright 2012 Samuel Neves, used under its Apache-2.0 option), also vendored in
 * libarchive/archive_blake2{s,sp}_ref.c. No UnRAR implementation is used.
 */
final class RarBlake2sp {
    private final Node[] leaves = new Node[8];
    private int lane, laneBytes;
    private boolean finished;

    RarBlake2sp() {
        for (int i = 0; i < leaves.length; i++) leaves[i] = new Node(i, 0, i == 7);
    }

    void update(byte[] input, int offset, int length) {
        if (finished) throw new IllegalStateException("BLAKE2sp already finalized");
        if (offset < 0 || length < 0 || offset > input.length - length) throw new IndexOutOfBoundsException();
        while (length > 0) {
            int take = Math.min(length, 64 - laneBytes);
            leaves[lane].update(input, offset, take);
            offset += take;
            length -= take;
            laneBytes += take;
            if (laneBytes == 64) { laneBytes = 0; lane = (lane + 1) & 7; }
        }
    }

    byte[] digest() {
        if (finished) throw new IllegalStateException("BLAKE2sp already finalized");
        finished = true;
        Node root = new Node(0, 1, true);
        for (Node leaf : leaves) root.update(leaf.finish(), 0, 32);
        return root.finish();
    }

    private static final class Node {
        private static final int[] IV = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
        private static final int[][] SIGMA = {
            {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15},
            {14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3},
            {11,8,12,0,5,2,15,13,10,14,3,6,7,1,9,4},
            {7,9,3,1,13,12,11,14,2,6,5,10,4,0,15,8},
            {9,0,5,7,2,4,10,15,14,1,11,12,6,8,3,13},
            {2,12,6,10,0,11,8,3,4,13,7,5,15,14,1,9},
            {12,5,1,15,14,13,4,10,0,7,6,3,9,2,8,11},
            {13,11,7,14,12,1,3,9,5,0,15,4,8,6,2,10},
            {6,15,14,9,11,3,0,8,12,2,13,7,1,4,10,5},
            {10,2,8,4,7,6,1,5,15,11,9,14,3,12,13,0}
        };
        private final int[] h = IV.clone(), v = new int[16], m = new int[16];
        private final byte[] block = new byte[64];
        private final boolean lastNode;
        private int used;
        private long bytes;

        Node(int nodeOffset, int depth, boolean lastNode) {
            this.lastNode = lastNode;
            h[0] ^= 0x02080020; // digest=32, key=0, fanout=8, depth=2
            h[2] ^= nodeOffset;
            h[3] ^= (depth << 16) | (32 << 24);
        }

        void update(byte[] input, int offset, int length) {
            while (length > 0) {
                // Retain the last complete block until more input or finalization.
                if (used == 64) { bytes += 64; compress(false); used = 0; }
                int take = Math.min(length, 64 - used);
                System.arraycopy(input, offset, block, used, take);
                used += take; offset += take; length -= take;
            }
        }

        byte[] finish() {
            bytes += used;
            Arrays.fill(block, used, block.length, (byte) 0);
            compress(true);
            byte[] result = new byte[32];
            for (int i = 0; i < result.length; i++) result[i] = (byte) (h[i / 4] >>> (8 * (i & 3)));
            return result;
        }

        private void compress(boolean last) {
            for (int i = 0; i < 16; i++) {
                int p = i * 4;
                m[i] = (block[p] & 255) | ((block[p+1] & 255) << 8)
                        | ((block[p+2] & 255) << 16) | ((block[p+3] & 255) << 24);
            }
            System.arraycopy(h, 0, v, 0, 8);
            System.arraycopy(IV, 0, v, 8, 8);
            v[12] ^= (int) bytes; v[13] ^= (int) (bytes >>> 32);
            if (last) { v[14] = ~v[14]; if (lastNode) v[15] = ~v[15]; }
            for (int[] s : SIGMA) {
                mix(0,4,8,12,m[s[0]],m[s[1]]); mix(1,5,9,13,m[s[2]],m[s[3]]);
                mix(2,6,10,14,m[s[4]],m[s[5]]); mix(3,7,11,15,m[s[6]],m[s[7]]);
                mix(0,5,10,15,m[s[8]],m[s[9]]); mix(1,6,11,12,m[s[10]],m[s[11]]);
                mix(2,7,8,13,m[s[12]],m[s[13]]); mix(3,4,9,14,m[s[14]],m[s[15]]);
            }
            for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
        }

        private void mix(int a, int b, int c, int d, int x, int y) {
            v[a] += v[b] + x; v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
            v[c] += v[d]; v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
            v[a] += v[b] + y; v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
            v[c] += v[d]; v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
        }
    }
}
