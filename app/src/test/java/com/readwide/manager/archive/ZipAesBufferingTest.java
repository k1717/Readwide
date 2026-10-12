package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.Arrays;
import net.lingala.zip4j.crypto.AESEncrypter;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.AesVersion;
import org.junit.Test;

/** Streaming boundary/ownership cases, not an archive interoperability benchmark. */
public class ZipAesBufferingTest {
    private static final char[] PASSWORD = "buffer-test".toCharArray();
    private static final class Source extends ByteArrayInputStream {
        int reads, maximum = Integer.MAX_VALUE;
        IOException closeFailure;
        Source(byte[] data) { super(data); }
        @Override public synchronized int read(byte[] bytes, int offset, int length) {
            reads++;
            return super.read(bytes, offset, Math.min(length, maximum));
        }
        @Override public void close() throws IOException { if (closeFailure != null) throw closeFailure; }
    }
    private static Source source(byte[] plain, AesKeyStrength strength) throws Exception {
        AESEncrypter encryption = new AESEncrypter(PASSWORD.clone(), strength, true);
        byte[] data = plain.clone(); encryption.encryptData(data);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(encryption.getSaltBytes()); packed.write(encryption.getDerivedPasswordVerifier());
        packed.write(data); packed.write(encryption.getFinalMac(), 0, 10);
        return new Source(packed.toByteArray());
    }
    private static InputStream open(Source source, AesKeyStrength strength) throws Exception {
        Class<?> metadataType = Class.forName(ZipxAesArchiveReader.class.getName() + "$AesMetadata");
        Constructor<?> metadata = metadataType.getDeclaredConstructors()[0]; metadata.setAccessible(true);
        Object aes = metadata.newInstance(AesVersion.TWO, strength, 0);
        Class<?> type = Class.forName(ZipxAesArchiveReader.class.getName() + "$WinZipAesInputStream");
        Constructor<?> constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(source, (long) source.available(), aes, PASSWORD, null);
    }

    @Test public void blockAndBufferBoundariesRoundTripForWriterStrengths() throws Exception {
        // The pinned Zip4j fixture writer only supports AES-128 and AES-256.
        for (AesKeyStrength strength : new AesKeyStrength[]{
                AesKeyStrength.KEY_STRENGTH_128, AesKeyStrength.KEY_STRENGTH_256}) {
            for (int size : new int[]{0,1,15,16,17,65535,65536,65537,131089}) {
                byte[] data = Round4Fixtures.data(size);
                try (InputStream stream = open(source(data, strength), strength)) {
                    assertArrayEquals(data, stream.readAllBytes());
                    assertEquals(-1, stream.read());
                }
            }
        }
    }

    @Test public void shortPhysicalReadsStillPreserveCtrAlignment() throws Exception {
        byte[] data = Round4Fixtures.data(70003);
        Source source = source(data, AesKeyStrength.KEY_STRENGTH_256); source.maximum = 7;
        try (InputStream stream = open(source, AesKeyStrength.KEY_STRENGTH_256)) {
            assertArrayEquals(data, stream.readAllBytes());
        }
    }

    @Test public void bulkReadIsNotLimitedToOneAesBlock() throws Exception {
        byte[] data = Round4Fixtures.data(131073), buffer = new byte[65536];
        Source source = source(data, AesKeyStrength.KEY_STRENGTH_256);
        try (InputStream stream = open(source, AesKeyStrength.KEY_STRENGTH_256)) {
            assertEquals(buffer.length, stream.read(buffer));
            assertArrayEquals(Arrays.copyOf(data, buffer.length), buffer);
            assertArrayEquals(Arrays.copyOfRange(data, buffer.length, data.length), stream.readAllBytes());
            assertTrue("Header, three ciphertext batches and authentication tail", source.reads <= 7);
        }
    }

    @Test public void mixedScalarAndBulkReadsKeepBufferedBytes() throws Exception {
        byte[] data = Round4Fixtures.data(65539);
        try (InputStream stream = open(source(data, AesKeyStrength.KEY_STRENGTH_128), AesKeyStrength.KEY_STRENGTH_128)) {
            assertEquals(data[0] & 255, stream.read());
            assertEquals(data[1] & 255, stream.read());
            assertArrayEquals(Arrays.copyOfRange(data, 2, data.length), stream.readAllBytes());
        }
    }

    @Test public void badAuthenticationAfterMultipleBatchesIsTerminal() throws Exception {
        Source original = source(Round4Fixtures.data(131089), AesKeyStrength.KEY_STRENGTH_256);
        byte[] bytes = original.readAllBytes(); bytes[bytes.length - 1] ^= 1;
        try (InputStream stream = open(new Source(bytes), AesKeyStrength.KEY_STRENGTH_256)) {
            IOException failure = assertThrows(IOException.class, stream::readAllBytes);
            assertSame(failure, assertThrows(IOException.class, stream::read));
        }
    }

    @Test public void failedCloseStillWipesPlaintextBuffer() throws Exception {
        Source source = source(Round4Fixtures.data(100), AesKeyStrength.KEY_STRENGTH_256);
        InputStream stream = open(source, AesKeyStrength.KEY_STRENGTH_256);
        stream.read(); source.closeFailure = new IOException("close");
        assertSame(source.closeFailure, assertThrows(IOException.class, stream::close));
        Field buffer = stream.getClass().getDeclaredField("decryptedBlock"); buffer.setAccessible(true);
        for (byte value : (byte[]) buffer.get(stream)) assertEquals(0, value);
        stream.close();
    }
}
