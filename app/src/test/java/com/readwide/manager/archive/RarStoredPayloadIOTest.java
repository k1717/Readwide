package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class RarStoredPayloadIOTest {
    @Test public void blakeOnlyAndCrcPlusBlakeAreBothChecked() throws Exception {
        byte[] data = RarBlake2spTest.sequence(65);
        byte[] hash = RarBlake2spTest.hex("fff24d3cc729d395daf978b0157306cb495797e6c8dca1731d2f6f81b849baae");
        File out = File.createTempFile("rar-blake", ".bin");
        try {
            for (long crc : new long[]{-1, crc(data)}) {
                write(out, data);
                RarStoredPayloadIO.verifyCrc(hashEntry(crc, hash, null), out);
                byte[] bad = hash.clone(); bad[31] ^= 1;
                try { RarStoredPayloadIO.verifyCrc(hashEntry(crc, bad, null), out); fail("Bad hash accepted"); }
                catch (IOException expected) { assertFalse(out.exists()); }
            }
        } finally { out.delete(); }
    }

    @Test public void blakeHashMacDoesNotRequirePasswordCheckField() throws Exception {
        byte[] data = RarBlake2spTest.sequence(65);
        byte[] hash = RarBlake2spTest.hex("fff24d3cc729d395daf978b0157306cb495797e6c8dca1731d2f6f81b849baae");
        Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets("pw".toCharArray(), 1, new byte[16]);
        javax.crypto.Mac oracle = javax.crypto.Mac.getInstance("HmacSHA256");
        oracle.init(new javax.crypto.spec.SecretKeySpec(secrets.hashKey, "HmacSHA256"));
        byte[] mac = oracle.doFinal(hash);
        assertArrayEquals(mac, Rar5Crypto.tweakBlake2sp(hash, secrets));
        RarArchiveReader.EncryptionInfo enc = new RarArchiveReader.EncryptionInfo(0,2,1,
                new byte[16], new byte[16], new byte[0]);
        RarStoredPayloadIO.DataCheck check = new RarStoredPayloadIO.DataCheck(hashEntry(-1,mac,enc));
        check.update(data, 0, data.length);
        assertTrue(check.matches(secrets));
        RarStoredPayloadIO.DataCheck wrong = new RarStoredPayloadIO.DataCheck(hashEntry(-1,mac,enc));
        data[0] ^= 1; wrong.update(data,0,data.length);
        assertFalse(wrong.matches(secrets));
    }

    private static RarArchiveReader.RarEntry hashEntry(long crc, byte[] hash,
            RarArchiveReader.EncryptionInfo enc) {
        return new RarArchiveReader.RarEntry("hash.bin",false,65,65,0,5,0,false,false,false,
                enc,crc,0,0,hash);
    }

    @Test
    public void copyPlainEntryToFile_copiesBoundedRange() throws Exception {
        File source = File.createTempFile("rar-stored-source", ".bin");
        File out = File.createTempFile("rar-stored-out", ".bin");
        try {
            write(source, new byte[] {9, 1, 2, 3, 4, 8});
            try (RandomAccessFile raf = new RandomAccessFile(source, "r")) {
                raf.seek(1L);
                RarStoredPayloadIO.copyPlainEntryToFile(raf, 4L, out, null);
            }
            assertArrayEquals(new byte[] {1, 2, 3, 4}, java.nio.file.Files.readAllBytes(out.toPath()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    @Test
    public void copySegmentsToFile_concatenatesSegments() throws Exception {
        File first = File.createTempFile("rar-segment-a", ".bin");
        File second = File.createTempFile("rar-segment-b", ".bin");
        File out = File.createTempFile("rar-segments-out", ".bin");
        try {
            write(first, new byte[] {0, 1, 2, 3});
            write(second, new byte[] {4, 5, 6, 7, 8});
            List<RarCryptoStreams.EncryptedSegment> segments = new ArrayList<>();
            segments.add(new RarCryptoStreams.EncryptedSegment(first, 1L, 3L));
            segments.add(new RarCryptoStreams.EncryptedSegment(second, 0L, 4L));
            RarStoredPayloadIO.copySegmentsToFile(segments, out, null);
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7}, java.nio.file.Files.readAllBytes(out.toPath()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            first.delete();
            //noinspection ResultOfMethodCallIgnored
            second.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    @Test
    public void verifyCrc_removesOutputOnPlainMismatch() throws Exception {
        File out = File.createTempFile("rar-crc-out", ".bin");
        write(out, new byte[] {1, 2, 3});
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                "file.bin",
                false,
                3L,
                3L,
                0L,
                4,
                0,
                false,
                false,
                false,
                null,
                crc(new byte[] {9, 9, 9}),
                0L);
        try {
            RarStoredPayloadIO.verifyCrc(entry, out);
        } catch (IOException expected) {
            assertFalse(out.exists());
            return;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
        throw new AssertionError("CRC mismatch should throw");
    }

    @Test
    public void verifyCrc_acceptsMatchingCrc() throws Exception {
        byte[] data = new byte[] {1, 2, 3, 4};
        File out = File.createTempFile("rar-crc-ok", ".bin");
        try {
            write(out, data);
            RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                    "file.bin",
                    false,
                    data.length,
                    data.length,
                    0L,
                    4,
                    0,
                    false,
                    false,
                    false,
                    null,
                    crc(data),
                    0L);
            RarStoredPayloadIO.verifyCrc(entry, out);
            assertTrue(out.exists());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    @Test
    public void passwordCheckDoesNotBypassPlaintextDataCrc() throws Exception {
        File out = File.createTempFile("rar-password-crc", ".bin");
        try {
            write(out, new byte[] {1, 2, 3});
            RarArchiveReader.EncryptionInfo encryption = new RarArchiveReader.EncryptionInfo(
                    0, 1, 8, new byte[16], new byte[16], new byte[12]);
            RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                    "data.bin", false, 3, 16, 0, 5, 0, false, false, false,
                    encryption, crc(new byte[] {9, 9, 9}), 0);
            assertTrue(RarStoredPayloadIO.hasPlaintextCrc(entry));
            try {
                RarStoredPayloadIO.verifyCrc(entry, out);
                throw new AssertionError("Password-check flag must not skip data integrity");
            } catch (IOException expected) {
                assertFalse(out.exists());
            }
        } finally { out.delete(); }
    }

    @Test
    public void tweakedChecksumFlagIsIndependentOfPasswordCheckBytes() {
        RarArchiveReader.EncryptionInfo encryption = new RarArchiveReader.EncryptionInfo(
                0, 2, 8, new byte[16], new byte[16], new byte[0]);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                "data.bin", false, 3, 16, 0, 5, 0, false, false, false, encryption, 0, 0);
        assertFalse(RarStoredPayloadIO.hasPlaintextCrc(entry));
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void tweakedChecksumWithoutCrcOrPasswordCheckRemainsUnsupported() throws Exception {
        RarArchiveReader.EncryptionInfo encryption = new RarArchiveReader.EncryptionInfo(
                0, 2, 8, new byte[16], new byte[16], new byte[0]);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                "data.bin", false, 3, 16, 0, 5, 0, false, false, false, encryption, -1, 0);
        RarStoredPayloadIO.requireSupportedDataCheck(entry);
    }

    @Test
    public void tweakedCrcCanVerifyWithoutPasswordCheckData() throws Exception {
        byte[] payload = new byte[] {7, 8, 9};
        Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets("pw".toCharArray(), 1, new byte[16]);
        RarArchiveReader.EncryptionInfo encryption = new RarArchiveReader.EncryptionInfo(
                0, 2, 1, new byte[16], new byte[16], new byte[0]);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                "data.bin", false, 3, 16, 0, 5, 0, false, false, false, encryption,
                Rar5Crypto.tweakCrc32(crc(payload), secrets), 0);
        assertTrue(RarStoredPayloadIO.crcMatches(entry, crc(payload), secrets));
        assertFalse(RarStoredPayloadIO.crcMatches(entry, crc(payload) ^ 1, secrets));
        try {
            RarStoredPayloadIO.crcMatches(entry, crc(payload), null);
            fail("Password-check presence must never bypass CRC key verification");
        } catch (IOException expected) { }
    }

    private static long crc(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data, 0, data.length);
        return crc32.getValue() & 0xffffffffL;
    }
}
