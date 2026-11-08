package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic AES stored members; this does not establish producer compatibility. */
public class Rar3EncryptedStoredDiscardTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final char[] PASSWORD = "discard-password".toCharArray();
    private static final byte[] SALT = {2, 7, 1, 8, 2, 8, 1, 8};

    @Test public void aesAndSplitSkipsNeedNoSpoolAcrossBlockBoundaries() throws Exception {
        for (int size : new int[]{0, 1, 15, 16, 17, 31, 32, 33, 65537}) {
            for (boolean split : new boolean[]{false, true}) {
                File blocked = temporary.newFile();
                try (ArchiveSupport.ForwardArchiveReader reader = open(fixture(size, split), PASSWORD, blocked)) {
                    reader.nextEntry();
                    assertTrue(reader.drainCurrentEntry(size));
                    assertNull(reader.nextEntry());
                }
                assertEquals(0, blocked.length());
            }
        }
    }

    @Test public void missingAndWrongPasswordsRetireWithoutOutput() throws Exception {
        for (boolean split : new boolean[]{false, true}) {
            for (char[] password : new char[][]{null, new char[0], "wrong".toCharArray()}) {
                assertFailure(fixture(33, split), password, ArchiveSupport.PasswordRequiredException.class);
            }
        }
    }

    @Test public void badFinalCrcKeepsPasswordFailureClassification() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(33, true);
        int last = entries.size() - 1;
        entries.set(last, withCrc(entries.get(last), entries.get(last).dataCrc ^ 1));
        assertFailure(entries, PASSWORD, ArchiveSupport.PasswordRequiredException.class);
    }

    @Test public void badIntermediateCiphertextCrcKeepsSplitClassification() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(33, true);
        entries.set(0, withCrc(entries.get(0), entries.get(0).dataCrc ^ 1));
        IOException failure = assertFailure(entries, PASSWORD, RarSplitStoredFailure.ExtractionException.class);
        assertEquals(RarSplitStoredFailure.Code.CRC_MISMATCH,
                ((RarSplitStoredFailure.ExtractionException) failure).failure().code);
    }

    @Test public void absentIntermediateCheckStillVerifiesFinalPlaintext() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(17, true);
        for (int i = 0; i + 1 < entries.size(); i++) entries.set(i, withCrc(entries.get(i), 0xffffffffL));
        try (ArchiveSupport.ForwardArchiveReader reader = open(entries, PASSWORD, temporary.newFile())) {
            reader.nextEntry();
            assertNull(reader.nextEntry());
        }
    }

    @Test public void unreadPaddingVolumeCannotDisappearAfterPlaintextCompletes() throws Exception {
        List<RarArchiveReader.RarEntry> entries = paddingFixture();
        FileOperationProgress progress = new FileOperationProgress("tail", null);
        final boolean[] changed = {false};
        progress.setListener(snapshot -> {
            if (snapshot.doneBytes == 1 && !changed[0]) {
                changed[0] = true;
                try { Files.write(entries.get(2).sourceArchive.toPath(), new byte[0]); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
        });
        Rar3LogicalSolidArchiveExtractor.Decoder decoder = decoder(entries);
        try {
            IOException failure = assertThrows(IOException.class, () -> decoder.extractNext(null, progress));
            assertTrue(changed[0]);
            assertTrue(failure.getMessage(), failure.getMessage().contains("past its volume"));
            assertThrows(IOException.class, () -> decoder.extractNext(null, null));
        } finally { decoder.retire(); }
    }

    @Test public void packedCrcAfterPlaintextLimitIsStillChecked() throws Exception {
        List<RarArchiveReader.RarEntry> entries = paddingFixture();
        entries.set(1, withCrc(entries.get(1), entries.get(1).dataCrc ^ 1));
        IOException failure = assertFailure(entries, PASSWORD, RarSplitStoredFailure.ExtractionException.class);
        assertTrue(failure.getMessage().contains("packed-volume checksum"));
    }

    @Test public void cancellationOnFinalPlaintextByteRetiresAndClearsPasswordCopy() throws Exception {
        Rar3LogicalSolidArchiveExtractor.Decoder decoder = decoder(paddingFixture());
        FileOperationProgress progress = new FileOperationProgress("cancel", null);
        progress.setListener(snapshot -> { if (snapshot.doneBytes > 0 && !snapshot.cancelled) progress.cancel(); });
        try {
            assertThrows(InterruptedIOException.class, () -> decoder.extractNext(null, progress));
            assertThrows(IOException.class, () -> decoder.extractNext(null, null));
            Field owned = decoder.getClass().getDeclaredField("password");
            owned.setAccessible(true);
            for (char c : (char[]) owned.get(decoder)) assertEquals(0, c);
            assertArrayEquals("discard-password".toCharArray(), PASSWORD);
        } finally { decoder.retire(); }
    }

    @Test public void drainBoundCanBeRetriedWithCorrectLimit() throws Exception {
        try (ArchiveSupport.ForwardArchiveReader reader = open(fixture(17, true), PASSWORD, temporary.newFile())) {
            reader.nextEntry();
            assertThrows(IOException.class, () -> reader.drainCurrentEntry(16));
            assertTrue(reader.drainCurrentEntry(17));
            assertNull(reader.nextEntry());
        }
    }

    @Test public void actualReadKeepsVerifiedSpoolAndWrongPasswordPublishesNothing() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(33, true);
        File directory = temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = open(entries, PASSWORD, directory)) {
            reader.nextEntry();
            byte[] bytes = new byte[33];
            assertEquals(33, reader.read(bytes));
            assertArrayEquals(data(33), bytes);
            assertEquals(1, directory.list().length);
            assertTrue(reader.drainCurrentEntry(0));
            assertEquals(0, directory.list().length);
        }
        try (ArchiveSupport.ForwardArchiveReader reader = open(entries, "wrong".toCharArray(), directory)) {
            reader.nextEntry();
            byte[] bytes = {42};
            assertThrows(ArchiveSupport.PasswordRequiredException.class, () -> reader.read(bytes));
            assertArrayEquals(new byte[]{42}, bytes);
        }
        assertEquals(0, directory.list().length);
    }

    private IOException assertFailure(List<RarArchiveReader.RarEntry> entries, char[] password,
            Class<? extends IOException> type) throws Exception {
        File absent = new File(temporary.getRoot(), "unused");
        try (ArchiveSupport.ForwardArchiveReader reader = open(entries, password, absent)) {
            reader.nextEntry();
            IOException failure = assertThrows(type, () -> reader.drainCurrentEntry(Long.MAX_VALUE));
            assertThrows(IOException.class, reader::nextEntry);
            assertThrows(IOException.class, () -> reader.read(new byte[1]));
            return failure;
        } finally { assertFalse(absent.exists()); }
    }

    private ArchiveSupport.ForwardArchiveReader open(List<RarArchiveReader.RarEntry> entries, char[] password, File directory) throws Exception {
        ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(entries, password, directory);
        assertNotNull(reader);
        return reader;
    }

    private Rar3LogicalSolidArchiveExtractor.Decoder decoder(List<RarArchiveReader.RarEntry> entries) {
        Rar3LogicalSolidArchiveExtractor.Step step = new Rar3LogicalSolidArchiveExtractor.Step(entries.get(0), entries, true);
        return new Rar3LogicalSolidArchiveExtractor.Decoder(
                new Rar3LogicalSolidArchiveExtractor.Plan(Arrays.asList(step), step.entry.unpackedSize), PASSWORD);
    }

    private List<RarArchiveReader.RarEntry> fixture(int size, boolean split) throws Exception {
        byte[] plain = data(size), packed = encrypt(Arrays.copyOf(plain, (size + 15) / 16 * 16));
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        if (split) for (int cut : new int[]{1, 15, 16, 17, 65535, 65536}) if (cut < packed.length) cuts.add(cut);
        cuts.add(packed.length);
        if (packed.length == 0) return parts(plain, packed, new int[]{0, 0});
        return parts(plain, packed, cuts.stream().mapToInt(Integer::intValue).toArray());
    }

    private List<RarArchiveReader.RarEntry> paddingFixture() throws Exception {
        byte[] padded = new byte[48];
        Arrays.fill(padded, (byte) 0xa5);
        padded[0] = 42;
        return parts(new byte[]{42}, encrypt(padded), new int[]{0, 16, 32, 48});
    }

    private List<RarArchiveReader.RarEntry> parts(byte[] plain, byte[] packed, int[] cuts) throws Exception {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        for (int i = 0; i + 1 < cuts.length; i++) {
            byte[] bytes = Arrays.copyOfRange(packed, cuts[i], cuts[i + 1]);
            boolean last = i + 2 == cuts.length;
            RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("stored.bin", false,
                    plain.length, bytes.length, 0, 4, 0x30, true, i > 0, !last,
                    RarArchiveReader.EncryptionInfo.rar4Unsupported(SALT.clone()), crc(last ? plain : bytes), 0);
            entry.sourceArchive = temporary.newFile();
            Files.write(entry.sourceArchive.toPath(), bytes);
            entries.add(entry);
        }
        return entries;
    }

    private static RarArchiveReader.RarEntry withCrc(RarArchiveReader.RarEntry old, long crc) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(old.path, false, old.unpackedSize,
                old.packedSize, old.dataOffset, 4, 0x30, old.solid, old.splitBefore, old.splitAfter, old.encryption, crc, 0);
        entry.sourceArchive = old.sourceArchive;
        return entry;
    }

    private static byte[] encrypt(byte[] plain) throws Exception {
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(PASSWORD, SALT);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(parameters.key, "AES"), new IvParameterSpec(parameters.iv));
        return cipher.doFinal(plain);
    }
    private static byte[] data(int size) { byte[] bytes = new byte[size]; for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31); return bytes; }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
}
