package com.readwide.manager.archive;

import java.io.*;
import java.util.zip.*;
import org.junit.Test;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.tukaani.xz.*;
import static org.junit.Assert.*;

public class SevenZAdditionalCodersTest {
    private static final byte[] DATA = {1,2,3,4,5,6,7,8,9,10,11,12};

    @Test public void modernBcjFiltersTransformAndRoundTripWithStartOffsets() throws Exception {
        for (int method : new int[]{0x0a, 0x0b}) {
            byte[] bytes = new byte[4096];
            new java.util.Random(123).nextBytes(bytes);
            for (int at = 0; at < bytes.length; at += 16) {
                int instruction = method == 0x0a ? 0x94000001 : 0x000000ef;
                for (int b = 0; b < 4; b++) bytes[at + b] = (byte)(instruction >>> (8 * b));
            }
            for (int start : new int[]{0, 256, -256}) {
                FilterOptions options;
                if (method == 0x0a) {
                    ARM64Options arm = new ARM64Options(); arm.setStartOffset(start); options = arm;
                } else {
                    RISCVOptions riscv = new RISCVOptions(); riscv.setStartOffset(start); options = riscv;
                }
                ByteArrayOutputStream packed = new ByteArrayOutputStream();
                try (FinishableOutputStream out = options.getOutputStream(new FinishableWrapperOutputStream(packed))) {
                    out.write(bytes);
                }
                assertFalse("Fixture must exercise address conversion", java.util.Arrays.equals(bytes, packed.toByteArray()));
                byte[] props = start == 0 ? null
                        : new byte[]{(byte)start, (byte)(start >>> 8), (byte)(start >>> 16), (byte)(start >>> 24)};
                assertDecoded(new byte[]{(byte)method}, props, packed.toByteArray(), bytes);
            }
        }
    }

    @Test public void modernBcjPropertiesFailBeforeInputIsRead() throws Exception {
        InputStream unread = new InputStream() { @Override public int read() { throw new AssertionError("Unexpected read"); } };
        for (int method : new int[]{0x0a, 0x0b}) {
            for (byte[] props : new byte[][]{new byte[1], new byte[3], new byte[5], {1,0,0,0}}) {
                try { SevenZAdditionalCoders.open(new byte[]{(byte)method}, props, unread); fail("Invalid BCJ properties"); }
                catch (IOException expected) { }
            }
        }
    }

    @Test public void modernBcjClassificationUsesExactMethodIds() throws Exception {
        assertTrue(SevenZAdditionalCoders.isModernBcj(new byte[]{0x0a}));
        assertTrue(SevenZAdditionalCoders.isModernBcj(new byte[]{0x0b}));
        for (byte[] id : new byte[][]{null, new byte[0], {0x0a,0}, {0x0c}, {3,3,5,1}}) {
            assertFalse(SevenZAdditionalCoders.isModernBcj(id));
        }
        assertNull(SevenZAdditionalCoders.open(new byte[]{0x0a,0}, null, new ByteArrayInputStream(DATA)));
    }

    @Test public void rawDeflateAndBzip2RoundTrip() throws Exception {
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(6, true);
        try (DeflaterOutputStream out = new DeflaterOutputStream(deflated, deflater)) { out.write(DATA); }
        finally { deflater.end(); }
        assertDecoded(new byte[]{4,1,8}, null, deflated.toByteArray(), DATA);
        ByteArrayOutputStream bz = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream out = new BZip2CompressorOutputStream(bz)) { out.write(DATA); }
        assertDecoded(new byte[]{4,2,2}, new byte[0], bz.toByteArray(), DATA);
    }

    @Test public void deflate64StoredBlockRoundTrip() throws Exception {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(new byte[]{1,12,0,(byte)0xf3,(byte)0xff}); packed.write(DATA);
        assertDecoded(new byte[]{4,1,9}, null, packed.toByteArray(), DATA);
    }

    @Test public void deltaDistancesOneAnd256RoundTrip() throws Exception {
        byte[] bytes = new byte[1024];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)(i * 31);
        for (int distance : new int[]{1,256}) {
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            try (FinishableOutputStream out = new DeltaOptions(distance).getOutputStream(new FinishableWrapperOutputStream(packed))) {
                out.write(bytes);
            }
            assertDecoded(new byte[]{3}, new byte[]{(byte)(distance-1)}, packed.toByteArray(), bytes);
        }
    }

    @Test public void allSixBcjFiltersRoundTripIncludingStartOffset() throws Exception {
        FilterOptions[] options = {new X86Options(), new PowerPCOptions(), new IA64Options(),
                new ARMOptions(), new ARMThumbOptions(), new SPARCOptions()};
        byte[][] ids = {{3,3,1,3},{3,3,2,5},{3,3,4,1},{3,3,5,1},{3,3,7,1},{3,3,8,5}};
        byte[] bytes = new byte[4096]; new java.util.Random(123).nextBytes(bytes);
        ((X86Options)options[0]).setStartOffset(256);
        for (int i = 0; i < options.length; i++) {
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            try (FinishableOutputStream out = options[i].getOutputStream(new FinishableWrapperOutputStream(packed))) { out.write(bytes); }
            assertDecoded(ids[i], i == 0 ? new byte[]{0,1,0,0} : null, packed.toByteArray(), bytes);
        }
    }

    @Test public void invalidPropertiesAndUnknownCodersDoNotReadInput() throws Exception {
        InputStream unread = new InputStream() { @Override public int read() { throw new AssertionError("Unexpected input read"); } };
        byte[][] ids = {{4,1,8},{4,1,9},{4,2,2},{3},{3,3,1,3}};
        for (byte[] id : ids) {
            try { SevenZAdditionalCoders.open(id, new byte[]{1,2}, unread); fail("Invalid properties"); }
            catch (IOException expected) { }
        }
        assertNull(SevenZAdditionalCoders.open(new byte[]{99}, null, unread));
    }

    @Test public void unalignedBcjOffsetFails() throws Exception {
        try { SevenZAdditionalCoders.open(new byte[]{3,3,5,1}, new byte[]{1,0,0,0}, new ByteArrayInputStream(DATA)); fail("Unaligned ARM offset"); }
        catch (IOException expected) { }
    }

    @Test public void truncatedDeflateAndBzip2Fail() throws Exception {
        for (byte[] id : new byte[][]{{4,1,8},{4,1,9},{4,2,2}}) {
            try (InputStream input = SevenZAdditionalCoders.open(id, null, new ByteArrayInputStream(new byte[]{1}))) {
                while (input.read() != -1) { }
                fail("Truncated coder stream");
            } catch (IOException expected) { }
        }
    }

    private static void assertDecoded(byte[] id, byte[] props, byte[] packed, byte[] expected) throws Exception {
        boolean[] closed = {false};
        InputStream source = new ByteArrayInputStream(packed) { @Override public void close() { closed[0] = true; } };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = SevenZAdditionalCoders.open(id, props, source)) {
            assertNotNull(input);
            byte[] buffer = new byte[7]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer,0,read);
        }
        assertArrayEquals(expected, output.toByteArray());
        assertTrue(closed[0]);
    }
}
