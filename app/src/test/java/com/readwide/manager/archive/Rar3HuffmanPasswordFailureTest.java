package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic invalid-plaintext cases; producer password checks are separate. */
public class Rar3HuffmanPasswordFailureTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] OLD = {42, 43};
    private static final char[] PASSWORD = "test".toCharArray();
    private static final byte[] SALT = {1,2,3,4,5,6,7,8};

    @Test public void invalidSymbolsAndTablesHaveADataOnlyType() throws Exception {
        assertThrows(RarCanonicalHuffman.InvalidDataException.class,
                () -> RarCanonicalHuffman.fromCodeLengths(new int[]{16}));
        assertThrows(RarCanonicalHuffman.InvalidDataException.class,
                () -> RarCanonicalHuffman.fromCodeLengths(new int[]{1,1,1}));
        assertThrows(RarCanonicalHuffman.InvalidDataException.class,
                () -> RarCanonicalHuffman.fromCodeLengths(new int[]{0}).decode(new RarBitInput(new byte[]{0})));
        assertEquals("Invalid RAR Huffman code", invalidCode().getMessage());
    }

    @Test public void callerRangesAndPhysicalEofAreNotDataFailures() throws Exception {
        IOException range = assertThrows(IOException.class,
                () -> RarCanonicalHuffman.fromCodeLengths(new int[]{1}, -1, 1));
        assertFalse(range instanceof RarCanonicalHuffman.InvalidDataException);
        RarCanonicalHuffman table = RarCanonicalHuffman.fromCodeLengths(new int[]{1});
        assertThrows(EOFException.class, () -> table.decode(new RarBitInput(new byte[0])));
    }

    @Test public void sourceFailuresKeepIdentityEvenWithHuffmanWords() throws Exception {
        RarCanonicalHuffman table = RarCanonicalHuffman.fromCodeLengths(new int[]{1});
        for (IOException source : new IOException[]{new IOException("Invalid RAR Huffman code"),
                new InterruptedIOException("read interrupted")}) {
            InputStream input = new InputStream() {
                @Override public int read() throws IOException { throw source; }
            };
            assertSame(source, assertThrows(IOException.class, () -> table.decode(new RarBitInput(input, 1))));
        }
    }

    @Test public void onlyTypedFailureInEncryptedEntryAllowsPasswordRetry() throws Exception {
        RarArchiveReader.RarEntry encrypted = fixture(true, false).get(0);
        RarArchiveReader.RarEntry plain = fixture(false, false).get(0);
        IOException data = invalidCode();
        assertSame(data, Rar3PackedPayload.decodeFailure(plain, data, null));
        IOException retry = Rar3PackedPayload.decodeFailure(encrypted, data, null);
        assertTrue(retry instanceof ArchiveSupport.PasswordRequiredException);
        assertSame(data, retry.getCause());
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, ArchiveFailureClassifier.classify(retry));
        for (IOException ordinary : new IOException[]{new IOException("Invalid RAR Huffman code"),
                new EOFException("Truncated RAR packed bit stream"),
                new RarArchiveReader.UnsupportedRarFeatureException("Unsupported RAR filter")}) {
            assertSame(ordinary, Rar3PackedPayload.decodeFailure(encrypted, ordinary, null));
        }
    }

    @Test public void cleanupOrWrappedIoPreventsPasswordMapping() throws Exception {
        RarArchiveReader.RarEntry entry = fixture(true, false).get(0);
        for (Throwable cleanup : new Throwable[]{new IOException("output close"),
                new SecurityException("rollback denied"), new OutOfMemoryError("cleanup allocation")}) {
            IOException failure = invalidCode(); failure.addSuppressed(cleanup);
            assertSame(failure, Rar3PackedPayload.decodeFailure(entry, failure, null));
            assertSame(cleanup, failure.getSuppressed()[0]);
            assertEquals(ArchiveSupport.ExtractionFailure.FAILED, ArchiveFailureClassifier.classify(failure));
        }
        IOException wrapped = invalidCode(); wrapped.initCause(new IOException("source failure"));
        assertSame(wrapped, Rar3PackedPayload.decodeFailure(entry, wrapped, null));
    }

    @Test public void cancellationWinsBeforePasswordMapping() throws Exception {
        RarArchiveReader.RarEntry entry = fixture(true, false).get(0);
        IOException failure = invalidCode();
        InterruptedIOException cancelled = new InterruptedIOException("cancelled during close");
        IOException cleanup = new IOException("close"); cleanup.addSuppressed(cancelled);
        failure.addSuppressed(cleanup);
        assertSame(cancelled, assertThrows(InterruptedIOException.class,
                () -> Rar3PackedPayload.decodeFailure(entry, failure, null)));
        FileOperationProgress progress = new FileOperationProgress("test", null); progress.cancel();
        assertThrows(InterruptedIOException.class,
                () -> Rar3PackedPayload.decodeFailure(entry, invalidCode(), progress));
        IOException interruptedData = invalidCode();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> Rar3PackedPayload.decodeFailure(entry, interruptedData, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void independentSingleAndSplitRejectWithoutReplacingOutput() throws Exception {
        for (boolean encrypted : new boolean[]{false,true}) for (boolean split : new boolean[]{false,true}) {
            if (!encrypted && !split) continue;
            List<RarArchiveReader.RarEntry> entries = fixture(encrypted, split);
            File out = original();
            IOException failure = assertThrows(IOException.class, () -> Rar3IndependentPayloadExtractor.tryExtract(
                    entries.get(0), entries, out, PASSWORD, null));
            assertClassification(encrypted, failure);
            assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void logicalBulkUsesSameClassificationAfterRollback() throws Exception {
        for (boolean encrypted : new boolean[]{false,true}) {
            List<RarArchiveReader.RarEntry> entries = fixture(encrypted, true);
            File directory = temp.newFolder(), out = new File(directory, "bad.bin");
            Files.write(out.toPath(), OLD);
            IOException failure = assertThrows(IOException.class, () -> Rar3LogicalSolidArchiveExtractor.tryExtractArchive(
                    entries, directory, PASSWORD, null, null));
            assertClassification(encrypted, failure);
            assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
            assertArrayEquals(new String[]{"bad.bin"}, directory.list());
        }
    }

    @Test public void selectedSolidPrimerFailureKeepsTargetUntouched() throws Exception {
        for (boolean encrypted : new boolean[]{false,true}) {
            List<RarArchiveReader.RarEntry> entries = fixture(encrypted, true);
            RarArchiveReader.RarEntry last = fixture(encrypted, false).get(0);
            RarArchiveReader.RarEntry target = new RarArchiveReader.RarEntry("after.bin", false, 2,
                    last.packedSize, 0, 4, 0x33, true, false, false, last.encryption, crc(OLD), 0);
            target.sourceArchive = last.sourceArchive; entries.add(target);
            File out = original();
            IOException failure = assertThrows(IOException.class, () -> Rar3LogicalSolidArchiveExtractor.tryExtractEntry(
                    target, entries, out, PASSWORD, null));
            assertClassification(encrypted, failure);
            assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
        }
    }

    @Test public void forwardReadAndDrainRetireWithoutPublishing() throws Exception {
        for (boolean encrypted : new boolean[]{false,true}) for (boolean split : new boolean[]{false,true}) {
            for (boolean discard : new boolean[]{false,true}) {
                List<RarArchiveReader.RarEntry> entries = fixture(encrypted, split);
                File spool = temp.newFolder();
                try (ArchiveSupport.ForwardArchiveReader reader = Rar3LogicalSolidArchiveExtractor.openForwardReader(
                        entries, PASSWORD, spool, false)) {
                    assertNotNull(reader); reader.nextEntry(); byte[] buffer = OLD.clone();
                    IOException failure = assertThrows(IOException.class, () -> {
                        if (discard) reader.drainCurrentEntry(2); else reader.read(buffer);
                    });
                    assertClassification(encrypted, failure);
                    assertArrayEquals(OLD, buffer);
                    assertArrayEquals(new String[0], spool.list());
                    assertThrows(IOException.class, reader::nextEntry);
                }
            }
        }
    }

    @Test public void failedRollbackKeepsOriginalBackupAndDoesNotRequestPassword() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true, false);
        File directory = temp.newFolder();
        File out = new File(directory, "previous.bin") {
            @Override public boolean delete() { return false; }
        };
        Files.write(out.toPath(), OLD);
        IOException failure = assertThrows(IOException.class, () -> Rar3IndependentPayloadExtractor.tryExtract(
                entries.get(0), entries, out, PASSWORD, null));
        assertTrue(failure instanceof RarCanonicalHuffman.InvalidDataException);
        assertTrue(failure.getSuppressed().length > 0);
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, ArchiveFailureClassifier.classify(failure));
        File[] backups = directory.listFiles(file -> file.getName().startsWith(".readwide-backup-"));
        assertNotNull(backups); assertEquals(1, backups.length);
        assertArrayEquals(OLD, Files.readAllBytes(new File(backups[0], "original").toPath()));
    }

    @Test public void physicalTruncationAndOutputDirectoryFailuresStayNonPassword() throws Exception {
        List<RarArchiveReader.RarEntry> entries = fixture(true, false);
        File directory = temp.newFolder();
        IOException outputFailure = assertThrows(IOException.class, () -> Rar3IndependentPayloadExtractor.tryExtract(
                entries.get(0), entries, directory, PASSWORD, null));
        assertFalse(outputFailure instanceof ArchiveSupport.PasswordRequiredException);
        assertTrue(directory.isDirectory());
        Files.write(entries.get(0).sourceArchive.toPath(), new byte[0]);
        File out = original();
        IOException sourceFailure = assertThrows(IOException.class, () -> Rar3IndependentPayloadExtractor.tryExtract(
                entries.get(0), entries, out, PASSWORD, null));
        assertFalse(sourceFailure instanceof ArchiveSupport.PasswordRequiredException);
        assertArrayEquals(OLD, Files.readAllBytes(out.toPath()));
    }

    private static void assertClassification(boolean encrypted, IOException failure) {
        assertEquals(encrypted, failure instanceof ArchiveSupport.PasswordRequiredException);
        assertEquals(encrypted ? ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED : ArchiveSupport.ExtractionFailure.FAILED,
                ArchiveFailureClassifier.classify(failure));
    }

    private static RarCanonicalHuffman.InvalidDataException invalidCode() throws Exception {
        RarCanonicalHuffman table = RarCanonicalHuffman.fromCodeLengths(new int[]{1});
        return assertThrows(RarCanonicalHuffman.InvalidDataException.class,
                () -> table.decode(new RarBitInput(new byte[]{(byte) 0x80})));
    }

    private List<RarArchiveReader.RarEntry> fixture(boolean encrypted, boolean split) throws Exception {
        // A table of all zero lengths is accepted until its first attempted symbol.
        byte[] packed = new byte[32];
        RarArchiveReader.EncryptionInfo encryption = null;
        if (encrypted) {
            Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(PASSWORD, SALT);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(parameters.key, "AES"), new IvParameterSpec(parameters.iv));
            packed = cipher.doFinal(packed);
            encryption = RarArchiveReader.EncryptionInfo.rar4Unsupported(SALT.clone());
        }
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        if (split) {
            byte[] first = Arrays.copyOf(packed, 7);
            entries.add(part(first, false, true, crc(first), encryption));
            entries.add(part(Arrays.copyOfRange(packed, 7, packed.length), true, false, crc(OLD), encryption));
        } else entries.add(part(packed, false, false, crc(OLD), encryption));
        return entries;
    }

    private RarArchiveReader.RarEntry part(byte[] bytes, boolean before, boolean after,
            long crc, RarArchiveReader.EncryptionInfo encryption) throws IOException {
        File source = temp.newFile(); Files.write(source.toPath(), bytes);
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("bad.bin", false,
                2, bytes.length, 0, 4, 0x33, false, before, after, encryption, crc, 0);
        entry.sourceArchive = source;
        return entry;
    }
    private File original() throws IOException {
        File out = temp.newFile(); Files.write(out.toPath(), OLD); return out;
    }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
}
