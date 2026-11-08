package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Rar4BlockSizeTest {
    private static final int FILE = 0x74;
    private static final int NEWSUB = 0x7a;
    private static final int LARGE = 0x0100;
    private static final int LONG_BLOCK = 0x8000;
    private static final byte[] SIGNATURE = {0x52, 0x61, 0x72, 0x21, 0x1a, 7, 0};

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unsignedLowWordIsIdenticalAtBodyAndFullHeaderOffsets() throws Exception {
        for (long size : new long[]{0, 0xffff_ffffL}) {
            for (int offset : new int[]{0, 7}) {
                assertEquals(size, Rar4BlockSize.packedSize(0, atOffset(body(size, false, ""), offset), offset));
            }
        }
    }

    @Test public void largePackedSizeRetainsHighWordThroughSignedLongMaximum() throws Exception {
        for (long size : new long[]{0, 0xffff_ffffL, 0x1_0000_0000L, Long.MAX_VALUE}) {
            for (int offset : new int[]{0, 7}) {
                assertEquals(size, Rar4BlockSize.packedSize(LARGE,
                        atOffset(body(size, true, ""), offset), offset));
            }
        }
    }

    @Test public void largePackedSizeRejectsUnsignedValuesAboveSignedLongMaximum() throws Exception {
        for (long bits : new long[]{Long.MIN_VALUE, -1L}) {
            for (int offset : new int[]{0, 7}) {
                byte[] data = atOffset(body(bits, true, ""), offset);
                assertThrows(IOException.class, () -> Rar4BlockSize.packedSize(LARGE, data, offset));
            }
        }
    }

    @Test public void fixedFieldsMustFitAfterEachBodyOffset() throws Exception {
        for (int offset : new int[]{0, 7}) {
            byte[] shortRegular = new byte[offset + 24];
            byte[] shortLarge = new byte[offset + 32];
            assertThrows(IOException.class, () -> Rar4BlockSize.packedSize(0, shortRegular, offset));
            assertThrows(IOException.class, () -> Rar4BlockSize.packedSize(LARGE, shortLarge, offset));
        }
    }

    @Test public void invalidBodyOffsetsAreReportedAsIoFailures() throws Exception {
        byte[] data = new byte[40];
        for (int offset : new int[]{-1, Integer.MIN_VALUE, data.length, data.length + 1, Integer.MAX_VALUE}) {
            assertThrows(IOException.class, () -> Rar4BlockSize.packedSize(LARGE, data, offset));
        }
    }

    @Test public void trailingBytesAreNotHighPackedSizeWithoutLargeFlag() throws Exception {
        byte[] data = body(0xffff_ffffL, true, "");
        Arrays.fill(data, 25, 33, (byte) 0xff);
        for (int offset : new int[]{0, 7}) {
            assertEquals(0xffff_ffffL, Rar4BlockSize.packedSize(0, atOffset(data, offset), offset));
        }
    }

    @Test public void plainAndEncryptedFramingUseTheSameFileAndServiceSize() throws Exception {
        for (long size : new long[]{0, 0xffff_ffffL, 0x1_0000_0000L, Long.MAX_VALUE}) {
            byte[] body = body(size, true, "");
            for (int type : new int[]{FILE, NEWSUB}) {
                assertEquals(size, plainSize(type, LONG_BLOCK | LARGE, body));
                assertEquals(size, encryptedSize(type, LONG_BLOCK | LARGE, body));
            }
        }
    }

    @Test public void bothFramingPathsRejectTruncatedLargeFields() throws Exception {
        for (int type : new int[]{FILE, NEWSUB}) {
            byte[] body = new byte[32];
            assertThrows(IOException.class, () -> plainSize(type, LONG_BLOCK | LARGE, body));
            assertThrows(IOException.class, () -> encryptedSize(type, LONG_BLOCK | LARGE, body));
        }
    }

    @Test public void bothFramingPathsRejectOverflowingServiceSize() throws Exception {
        byte[] body = body(Long.MIN_VALUE, true, "");
        assertThrows(IOException.class, () -> plainSize(NEWSUB, LONG_BLOCK | LARGE, body));
        assertThrows(IOException.class, () -> encryptedSize(NEWSUB, LONG_BLOCK | LARGE, body));
    }

    @Test public void historicalAuxiliaryBlocksKeepTheirUnsignedLowWordFraming() throws Exception {
        byte[] body = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        assertEquals(0xffff_ffffL, plainSize(0x78, LONG_BLOCK | LARGE, body));
        assertEquals(0xffff_ffffL, encryptedSize(0x78, LONG_BLOCK | LARGE, body));
        assertEquals(0, plainSize(0x78, LARGE, body));
        assertEquals(0, encryptedSize(0x78, LARGE, body));
    }

    @Test public void servicePayloadPastVolumeCannotExposeFollowingPayloadAsFileHeader() throws Exception {
        byte[] service = body(0x1_0000_0000L, true, "RR");
        byte[] fakeFile = body(0, false, "fake.bin");
        File archive = archive(header(0x73, 0, new byte[6]),
                header(NEWSUB, LONG_BLOCK | LARGE, service), header(FILE, LONG_BLOCK, fakeFile),
                header(0x7b, 0, new byte[0]));
        RarArchiveReader.EntryScan scan = RarArchiveReader.scanEntries(archive, null);
        assertNotNull(scan.failure);
        assertFalse(scan.failure instanceof ArchiveSupport.PasswordRequiredException);
        assertEquals(0, scan.entries.size());
        assertThrows(IOException.class, scan::requireComplete);
    }

    @Test public void smallServicePayloadIsSkippedWithoutPublishingAnEntry() throws Exception {
        byte[] service = body(3, true, "RR");
        byte[] file = body(1, false, "page.bin");
        File archive = archive(header(0x73, 0, new byte[6]),
                header(NEWSUB, LONG_BLOCK | LARGE, service), new byte[]{7, 8, 9},
                header(FILE, LONG_BLOCK, file), new byte[]{42}, header(0x7b, 0, new byte[0]));
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(archive, null);
        assertEquals(1, entries.size());
        assertEquals("page.bin", entries.get(0).path);
        assertEquals(1, entries.get(0).packedSize);
    }

    @Test public void probeCannotFindPasswordMarkerInsideOversizedFileOrServicePayload() throws Exception {
        for (int type : new int[]{FILE, NEWSUB}) {
            File archive = archive(header(0x73, 0, new byte[6]),
                    header(type, LONG_BLOCK | LARGE, body(0x1_0000_0000L, true, "a")),
                    header(0x73, 0x80, new byte[6]));
            assertFalse(RarHeaderEncryptionDetector.hasEncryptedHeaders(archive));
            assertEquals(0, RarHeaderEncryptionDetector.headerEncryptedRarVersion(archive));
        }
    }

    @Test public void probeSkipsSmallServicePayloadBeforeLaterPasswordMarker() throws Exception {
        File archive = archive(header(0x73, 0, new byte[6]),
                header(NEWSUB, LONG_BLOCK | LARGE, body(3, true, "RR")), new byte[]{7, 8, 9},
                header(0x73, 0x80, new byte[6]));
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(archive));
        assertEquals(4, RarHeaderEncryptionDetector.headerEncryptedRarVersion(archive));
    }

    @Test public void bothSplitScannersRejectFileHeaderInsideOversizedServicePayload() throws Exception {
        byte[] main = header(0x73, 0, new byte[6]);
        byte[] service = header(NEWSUB, LONG_BLOCK | LARGE, body(0x1_0000_0000L, true, "RR"));
        byte[] fakeFile = header(FILE, LONG_BLOCK, body(0, false, "fake.bin"));
        File archive = archive(main, service, fakeFile, header(0x7b, 0, new byte[0]));
        long fakeDataOffset = (long) SIGNATURE.length + main.length + service.length + fakeFile.length;
        for (Class<?> owner : new Class<?>[]{Rar4SplitPayloadRewriter.class,
                Rar4EncryptedSplitPayloadRewriter.class}) {
            Method method = owner.getDeclaredMethod("findFileHeaderForDataOffset", File.class,
                    long.class, com.readwide.manager.util.FileOperationProgress.class);
            assertThrows(IOException.class, () -> invoke(method, archive, fakeDataOffset, null));
        }
    }

    @Test public void encryptedServicePastVolumeRequestsPasswordAndRemovesPartialOutput() throws Exception {
        char[] password = {'p'};
        byte[] salt = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] service = header(NEWSUB, LONG_BLOCK | LARGE, body(0x1_0000_0000L, true, "RR"));
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(password, salt);
        byte[] encrypted;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(parameters.key, "AES"),
                    new IvParameterSpec(parameters.iv));
            encrypted = cipher.doFinal(Arrays.copyOf(service, (service.length + 15) & ~15));
        } finally {
            Arrays.fill(parameters.key, (byte) 0);
            Arrays.fill(parameters.iv, (byte) 0);
        }
        File archive = archive(header(0x73, 0x80, new byte[6]), salt, encrypted);
        File output = new File(temporary.newFolder(), "rejected.rar");
        assertThrows(ArchiveSupport.PasswordRequiredException.class,
                () -> Rar4HeaderEncryptedArchiveRewriter.writeDecryptedHeaderCopy(archive, output, password, null));
        assertFalse(output.exists());
        assertTrue(archive.isFile());
    }

    private static long plainSize(int type, int flags, byte[] body) throws Exception {
        Method method = Rar4EncryptedPayloadRewriter.class.getDeclaredMethod(
                "dataSizeForHeader", int.class, int.class, byte[].class);
        return (Long) invoke(method, type, flags, body);
    }

    private static long encryptedSize(int type, int flags, byte[] body) throws Exception {
        Method method = Rar4HeaderEncryptedArchiveRewriter.class.getDeclaredMethod(
                "payloadSize", int.class, int.class, byte[].class);
        return (Long) invoke(method, type, flags, header(type, flags, body));
    }

    private static Object invoke(Method method, Object... arguments) throws Exception {
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception) throw (Exception) wrapped.getCause();
            throw wrapped;
        }
    }

    private File archive(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        for (byte[] part : parts) out.write(part);
        File file = new File(temporary.newFolder(), "service.rar");
        Files.write(file.toPath(), out.toByteArray());
        return file;
    }

    private static byte[] body(long packedSize, boolean large, String name) {
        byte[] filename = name.getBytes(StandardCharsets.US_ASCII);
        int fixedSize = large ? 33 : 25;
        byte[] body = new byte[fixedSize + filename.length];
        put32(body, 0, packedSize);
        put32(body, 4, packedSize);
        body[8] = 2;
        body[17] = 29;
        body[18] = 0x30;
        body[19] = (byte) filename.length;
        body[20] = (byte) (filename.length >>> 8);
        if (large) {
            put32(body, 25, packedSize >>> 32);
            put32(body, 29, packedSize >>> 32);
        }
        System.arraycopy(filename, 0, body, fixedSize, filename.length);
        return body;
    }

    private static byte[] atOffset(byte[] body, int offset) {
        byte[] data = new byte[offset + body.length];
        Arrays.fill(data, 0, offset, (byte) 0x5a);
        System.arraycopy(body, 0, data, offset, body.length);
        return data;
    }

    private static byte[] header(int type, int flags, byte[] body) {
        byte[] encoded = new byte[7 + body.length];
        encoded[2] = (byte) type;
        encoded[3] = (byte) flags;
        encoded[4] = (byte) (flags >>> 8);
        encoded[5] = (byte) encoded.length;
        encoded[6] = (byte) (encoded.length >>> 8);
        System.arraycopy(body, 0, encoded, 7, body.length);
        CRC32 crc = new CRC32();
        crc.update(encoded, 2, encoded.length - 2);
        encoded[0] = (byte) crc.getValue();
        encoded[1] = (byte) (crc.getValue() >>> 8);
        return encoded;
    }

    private static void put32(byte[] data, int offset, long value) {
        for (int i = 0; i < 4; i++) data[offset + i] = (byte) (value >>> (i * 8));
    }
}
