package com.readwide.manager.archive;

import org.junit.Test;
import static org.junit.Assert.*;

public class RarBlake2spTest {
    // Official unkeyed sequential-byte KATs (CC0 BLAKE2 reference package):
    // https://github.com/BLAKE2/BLAKE2/blob/master/testvectors/blake2-kat.json
    @Test public void officialKnownAnswersAtBlockBoundaries() {
        int[] lengths = {0,1,63,64,65,255};
        String[] answers = {
            "dd0e891776933f43c7d032b08a917e25741f8aa9a12c12e1cac8801500f2ca4f",
            "a6b9eecc25227ad788c99d3f236debc8da408849e9a5178978727a81457f7239",
            "1024c940be7341449b5010522b509f65bbdc1287b455c2bb7f72b2c92fd0d189",
            "52603b6cbfad4966cb044cb267568385cf35f21e6c45cf30aed19832cb51e9f5",
            "fff24d3cc729d395daf978b0157306cb495797e6c8dca1731d2f6f81b849baae",
            "25059f10605e67adfe681350666e15ae976a5a571c13cf5bc8053f430e120a52"
        };
        for (int i = 0; i < lengths.length; i++) {
            byte[] input = sequence(lengths[i]);
            for (int step : new int[]{1,13,64,511}) {
                RarBlake2sp hash = new RarBlake2sp();
                for (int at = 0; at < input.length; at += step) {
                    hash.update(input, at, Math.min(step, input.length - at));
                }
                assertArrayEquals(hex(answers[i]), hash.digest());
            }
        }
    }

    @Test public void allEightLanesAndRepeatedStripesAreChunkIndependent() {
        for (int length : new int[]{511,512,513,1024,1025,65537}) {
            byte[] input = sequence(length);
            RarBlake2sp whole = new RarBlake2sp(); whole.update(input, 0, input.length);
            byte[] expected = whole.digest();
            for (int step : new int[]{1,63,64,65,511,512,513}) {
                RarBlake2sp chunked = new RarBlake2sp();
                for (int at = 0; at < length; at += step) chunked.update(input, at, Math.min(step, length-at));
                assertArrayEquals(expected, chunked.digest());
            }
        }
    }

    @Test public void finalizationAndRangeMisuseAreRejected() {
        RarBlake2sp hash = new RarBlake2sp();
        try { hash.update(new byte[2], 1, Integer.MAX_VALUE); fail(); }
        catch (IndexOutOfBoundsException expected) { }
        hash.digest();
        try { hash.digest(); fail(); } catch (IllegalStateException expected) { }
        try { hash.update(new byte[0], 0, 0); fail(); } catch (IllegalStateException expected) { }
    }

    static byte[] sequence(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) i;
        return bytes;
    }

    static byte[] hex(String value) {
        byte[] bytes = new byte[value.length()/2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i*2,i*2+2),16);
        return bytes;
    }
}
