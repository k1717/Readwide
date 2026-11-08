package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;

public class Rar4HeaderChecksumTest {
    private static final byte[] SIGNATURE = {0x52, 0x61, 0x72, 0x21, 0x1a, 7, 0};
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void canonicalMainHeaderHasKnownChecksum() throws Exception {
        assertEquals(0x90cf, Rar4HeaderChecksum.calculate(0x73, 0, new byte[6]));
        Rar4HeaderChecksum.verify(0x90cf, 0x73, 0, new byte[6]);
    }

    @Test public void archiveCommentIsOutsideMainCrcButEncryptionVersionIsInside() throws Exception {
        byte[] body = new byte[15];
        body[6] = 29;
        Arrays.fill(body, 7, body.length, (byte) 0x75);
        byte[] encoded = header(0x73, 0x0202, body, 7);
        Rar4HeaderChecksum.verify(u16(encoded, 0), 0x73, 0x0202, body);
        body[14] ^= 1;
        Rar4HeaderChecksum.verify(u16(encoded, 0), 0x73, 0x0202, body);
        body[6] ^= 1;
        assertThrows(IOException.class,
                () -> Rar4HeaderChecksum.verify(u16(encoded, 0), 0x73, 0x0202, body));
    }

    @Test public void legacyFileCommentFollowsLargeNameSaltAndAllFourTimes() throws Exception {
        int flags = 0x8000 | 0x0008 | 0x0100 | 0x0200 | 0x0400 | 0x1000;
        byte[] body = fileBody(new byte[]{'a', 0, 1, 2}, true);
        body = join(body, new byte[8], new byte[]{(byte) 0x89, (byte) 0x9a}, new byte[16]);
        int covered = body.length;
        body = join(body, new byte[]{1, 2, 0x75, 0, 0, 7, 0});
        byte[] encoded = header(0x74, flags, body, covered);
        Rar4HeaderChecksum.verify(u16(encoded, 0), 0x74, flags, body);
        body[body.length - 1] ^= 1;
        Rar4HeaderChecksum.verify(u16(encoded, 0), 0x74, flags, body);
        final byte[] damaged = body.clone();
        damaged[covered - 1] ^= 1;
        assertThrows(IOException.class,
                () -> Rar4HeaderChecksum.verify(u16(encoded, 0), 0x74, flags, damaged));
    }

    @Test public void modernFileAndServiceCrcIncludesTrailingHeaderBytes() throws Exception {
        byte[] body = join(fileBody(new byte[]{'a'}, false), new byte[]{9, 8, 7});
        for (int type : new int[]{0x74, 0x7a}) {
            byte[] encoded = header(type, 0x8000, body, body.length);
            Rar4HeaderChecksum.verify(u16(encoded, 0), type, 0x8000, body);
            byte[] damaged = body.clone();
            damaged[damaged.length - 1] ^= 1;
            assertThrows(IOException.class,
                    () -> Rar4HeaderChecksum.verify(u16(encoded, 0), type, 0x8000, damaged));
        }
    }

    @Test public void crcCoverageRejectsTruncatedKnownCommentFields() throws Exception {
        assertThrows(IOException.class, () -> Rar4HeaderChecksum.calculate(0x73, 0, new byte[5]));
        assertThrows(IOException.class, () -> Rar4HeaderChecksum.calculate(0x73, 0x200, new byte[6]));
        byte[] name = fileBody(new byte[]{'a'}, false);
        name[19] = 2;
        assertThrows(IOException.class, () -> Rar4HeaderChecksum.calculate(0x74, 8, name));
        assertThrows(IOException.class, () -> Rar4HeaderChecksum.calculate(0x74, 0x108, new byte[32]));
        byte[] salt = join(fileBody(new byte[]{'a'}, false), new byte[7]);
        assertThrows(IOException.class, () -> Rar4HeaderChecksum.calculate(0x74, 0x408, salt));
        byte[] timeFlags = join(fileBody(new byte[]{'a'}, false), new byte[1]);
        assertThrows(IOException.class, () -> Rar4HeaderChecksum.calculate(0x74, 0x1008, timeFlags));
        byte[] timeData = join(fileBody(new byte[]{'a'}, false), new byte[]{0, (byte) 0x90});
        assertThrows(IOException.class, () -> Rar4HeaderChecksum.calculate(0x74, 0x1008, timeData));
    }

    @Test public void absentExtendedTimeDataRemainsAcceptedAtHeaderEnd() throws Exception {
        byte[] body = fileBody(new byte[]{'a'}, false);
        byte[] encoded = header(0x74, 0x1008, body, body.length);
        Rar4HeaderChecksum.verify(u16(encoded, 0), 0x74, 0x1008, body);
    }

    @Test public void skippedHistoricalAndUnknownBlocksKeepTheirPreviousPolicy() throws Exception {
        for (int type : new int[]{0x72, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7c}) {
            Rar4HeaderChecksum.verify(0x1234, type, 0x8000, new byte[4]);
        }
    }

    @Test public void readerRejectsBadMainBeforePasswordClassification() throws Exception {
        byte[] main = header(0x73, 0x80, new byte[6], 6);
        main[7] ^= 1;
        File archive = archive(main, new byte[32]);
        IOException error = assertThrows(IOException.class, () -> RarArchiveReader.readEntries(archive, null));
        assertFalse(error instanceof ArchiveSupport.PasswordRequiredException);
        assertTrue(error.getMessage().contains("CRC"));
    }

    @Test public void readerRetainsOnlyValidatedPrefixAfterFileOrEndCrcFailure() throws Exception {
        byte[] first = header(0x74, 0x8000, fileBody(new byte[]{'a'}, false), 26);
        for (byte[] broken : new byte[][]{
                header(0x74, 0x8000, fileBody(new byte[]{'b'}, false), 26),
                header(0x7a, 0x8000, fileBody(new byte[]{'b'}, false), 26),
                header(0x7b, 0, new byte[0], 0)}) {
            broken[0] ^= 1;
            File archive = archive(header(0x73, 0, new byte[6], 6), first, broken);
            RarArchiveReader.EntryScan scan = RarArchiveReader.scanEntries(archive, null);
            assertNotNull(scan.failure);
            assertTrue(scan.failure.getMessage().contains("CRC"));
            assertEquals(1, scan.entries.size());
            assertEquals("a", scan.entries.get(0).path);
            assertEquals(1, scan.forSelectedEntry("a").size());
            assertThrows(IOException.class, () -> scan.forSelectedEntry("b"));
            assertThrows(IOException.class, scan::requireComplete);
        }
    }

    @Test public void readerAcceptsInlineArchiveAndFileComments() throws Exception {
        byte[] comment = {1, 2, 0x75, 0, 0, 7, 0};
        byte[] main = join(new byte[6], comment);
        byte[] file = join(fileBody(new byte[]{'a'}, false), comment);
        File archive = archive(header(0x73, 2, main, 6), header(0x74, 0x8008, file, 26),
                header(0x7b, 0, new byte[0], 0));
        assertEquals("a", RarArchiveReader.readEntries(archive, null).get(0).path);
    }

    @Test public void rewriterSourceScansRejectBadFileHeaderBeforeUsingOffset() throws Exception {
        byte[] main = header(0x73, 0, new byte[6], 6);
        byte[] file = header(0x74, 0x8000, fileBody(new byte[]{'a'}, false), 26);
        file[7 + 18] ^= 1;
        File archive = archive(main, file);
        for (Class<?> owner : new Class<?>[]{Rar4SplitPayloadRewriter.class,
                Rar4EncryptedSplitPayloadRewriter.class}) {
            Method scan = owner.getDeclaredMethod("findFileHeaderForDataOffset", File.class,
                    long.class, com.readwide.manager.util.FileOperationProgress.class);
            IOException error = assertThrows(IOException.class,
                    () -> invoke(scan, archive, (long) SIGNATURE.length + main.length + file.length, null));
            assertTrue(error.getMessage().contains("CRC"));
        }
        IOException error = assertThrows(IOException.class,
                () -> Rar4EncryptedPayloadRewriter.buildDecryptedCopy(archive, new char[]{'p'}, null));
        assertTrue(error.getMessage().contains("CRC"));
    }

    @Test public void encryptedHeaderMainSourceCannotBeSilentlyRechecksummed() throws Exception {
        byte[] main = header(0x73, 0x80, new byte[6], 6);
        main[7] ^= 1;
        File archive = archive(main, new byte[32]);
        Method scan = Rar4HeaderEncryptedArchiveRewriter.class.getDeclaredMethod(
                "readEncryptedMainHeader", RandomAccessFile.class);
        try (RandomAccessFile input = new RandomAccessFile(archive, "r")) {
            IOException error = assertThrows(IOException.class, () -> invoke(scan, input));
            assertFalse(error instanceof ArchiveSupport.PasswordRequiredException);
            assertTrue(error.getMessage().contains("CRC"));
        }
    }

    @Test public void everyRewriterUsesLegacyCommentCoverageWhenWriting() throws Exception {
        byte[] file = fileBody(new byte[]{'a'}, false);
        byte[] commentedFile = join(file, new byte[]{1, 2, 0x75, 0, 0, 7, 0});
        byte[] main = join(new byte[6], new byte[]{1, 2, 0x75, 0, 0, 7, 0});
        for (Class<?> owner : new Class<?>[]{Rar4SplitPayloadRewriter.class,
                Rar4EncryptedSplitPayloadRewriter.class, Rar4EncryptedPayloadRewriter.class,
                Rar4HeaderEncryptedArchiveRewriter.class}) {
            Method writer = owner.getDeclaredMethod("writeRar4Header", FileOutputStream.class,
                    int.class, int.class, byte[].class);
            File result = temporary.newFile();
            try (FileOutputStream out = new FileOutputStream(result)) {
                invoke(writer, out, 0x73, 2, main);
                invoke(writer, out, 0x74, 0x8008, commentedFile);
            }
            assertArrayEquals(join(header(0x73, 2, main, 6),
                    header(0x74, 0x8008, commentedFile, file.length)), Files.readAllBytes(result.toPath()));
        }
    }

    @Test public void decryptedHeaderCheckKeepsCommentCoverageAndMalformedRejection() throws Exception {
        Method verify = Rar4HeaderEncryptedArchiveRewriter.class.getDeclaredMethod(
                "hasValidHeaderCrc", byte[].class);
        byte[] body = fileBody(new byte[]{'a'}, false);
        byte[] commented = join(body, new byte[]{1, 2, 0x75, 0, 0, 7, 0});
        byte[] valid = header(0x74, 0x8008, commented, body.length);
        assertEquals(Boolean.TRUE, invoke(verify, (Object) valid));
        valid[7 + 19] = (byte) 255;
        assertEquals(Boolean.FALSE, invoke(verify, (Object) valid));
    }

    @Test public void decryptedCrcFailureStillRequestsPasswordAndRemovesOutput() throws Exception {
        char[] password = {'p'};
        byte[] salt = {1, 2, 3, 4, 5, 6, 7, 8};
        Rar3Crypto.Parameters keys = Rar3Crypto.deriveParameters(password, salt);
        byte[] damaged = Arrays.copyOf(header(0x7b, 0, new byte[0], 0), 16);
        damaged[0] ^= 1;
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(keys.key, "AES"),
                new javax.crypto.spec.IvParameterSpec(keys.iv));
        File archive = archive(header(0x73, 0x80, new byte[6], 6), salt, cipher.doFinal(damaged));
        File output = new File(temporary.getRoot(), "rejected-header.rar");
        assertThrows(ArchiveSupport.PasswordRequiredException.class,
                () -> Rar4HeaderEncryptedArchiveRewriter.writeDecryptedHeaderCopy(archive, output, password, null));
        assertFalse(output.exists());
    }

    private File archive(byte[]... blocks) throws IOException {
        File file = temporary.newFile();
        Files.write(file.toPath(), join(SIGNATURE, join(blocks)));
        return file;
    }

    private static byte[] fileBody(byte[] name, boolean large) throws IOException {
        byte[] fixed = new byte[large ? 33 : 25];
        fixed[17] = 29;
        fixed[18] = 0x30;
        fixed[19] = (byte) name.length;
        fixed[20] = (byte) (name.length >>> 8);
        return join(fixed, name);
    }

    private static byte[] header(int type, int flags, byte[] body, int covered) throws IOException {
        byte[] encoded = new byte[7 + body.length];
        encoded[2] = (byte) type;
        encoded[3] = (byte) flags;
        encoded[4] = (byte) (flags >>> 8);
        encoded[5] = (byte) encoded.length;
        encoded[6] = (byte) (encoded.length >>> 8);
        System.arraycopy(body, 0, encoded, 7, body.length);
        CRC32 crc = new CRC32();
        crc.update(encoded, 2, 5 + covered);
        encoded[0] = (byte) crc.getValue();
        encoded[1] = (byte) (crc.getValue() >>> 8);
        return encoded;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }

    private static Object invoke(Method method, Object... args) throws Exception {
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception) throw (Exception) wrapped.getCause();
            throw wrapped;
        }
    }
}
