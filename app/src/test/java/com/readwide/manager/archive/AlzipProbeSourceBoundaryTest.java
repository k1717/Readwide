package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic candidate/transaction boundaries, not producer or timing validation. */
public class AlzipProbeSourceBoundaryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] PLAIN = {1, 2, 3, 4};
    private static final char[] PASSWORD = "secret".toCharArray();

    @Test public void changedActiveOrEarlierVolumeCannotBecomeMatchOrPasswordMismatch() throws Exception {
        for (boolean egg : new boolean[]{false, true}) {
            for (boolean match : new boolean[]{false, true}) {
                for (boolean earlier : new boolean[]{false, true}) {
                    File prefix = temp.newFile(), payload = encryptedPayload();
                    Files.write(prefix.toPath(), new byte[]{99});
                    FileOperationProgress progress = token();
                    try (SplitVolumeInput input = source(prefix, payload)) {
                        int[] reads = {0};
                        intercept(input, payload, 1, () -> { reads[0]++; changeTimestamp(earlier ? prefix : payload); });
                        IOException failure = assertThrows(IOException.class, () ->
                                probe(egg, input, entry(egg, 1, match, true), progress));
                        assertTrue(failure.getMessage().contains("Split volume changed"));
                        assertEquals(1, reads[0]);
                        assertEquals(0, progress.snapshot().doneBytes);
                    }
                }
            }
        }
    }

    @Test public void staleVolumeIsRejectedBeforeCandidateReads() throws Exception {
        for (boolean egg : new boolean[]{false, true}) {
            File prefix = temp.newFile(), payload = encryptedPayload();
            Files.write(prefix.toPath(), new byte[]{99});
            try (SplitVolumeInput input = source(prefix, payload)) {
                int[] reads = {0};
                intercept(input, payload, 1, () -> reads[0]++);
                changeTimestamp(prefix);
                IOException failure = assertThrows(IOException.class, () ->
                        probe(egg, input, entry(egg, 1, true, true), token()));
                assertTrue(failure.getMessage().contains("Split volume changed"));
                assertEquals(0, reads[0]);
            }
        }
    }

    @Test public void unchangedCandidateKeepsMatchMismatchAndDoesNotPublishProgress() throws Exception {
        for (boolean egg : new boolean[]{false, true}) {
            for (boolean match : new boolean[]{false, true}) {
                for (FileOperationProgress progress : new FileOperationProgress[]{null, token()}) {
                    File payload = encryptedPayload();
                    try (SplitVolumeInput input = source(payload)) {
                        assertEquals(match, probe(egg, input, entry(egg, 0, match, true), progress));
                        assertEquals(0, input.getFilePointer());
                    }
                    if (progress != null) assertEquals(0, progress.snapshot().doneBytes);
                }
            }
        }
    }

    @Test public void cancellationDuringFinalCandidateValidationIsNotAResult() throws Exception {
        for (boolean egg : new boolean[]{false, true}) {
            for (boolean match : new boolean[]{false, true}) {
                File payload = encryptedPayload(); FileOperationProgress progress = token();
                boolean[] armed = {false}; int[] checks = {0};
                File observed = observed(payload, armed, checks, progress, true);
                try (SplitVolumeInput input = source(observed)) {
                    intercept(input, payload, 0, () -> armed[0] = true);
                    assertThrows(InterruptedIOException.class, () ->
                            probe(egg, input, entry(egg, 0, match, true), progress));
                }
                assertEquals(1, checks[0]); assertEquals(0, progress.snapshot().doneBytes);
                assertFalse(Thread.currentThread().isInterrupted());
            }
        }
    }

    @Test public void physicalReadFailureKeepsOriginalIdentity() throws Exception {
        for (boolean egg : new boolean[]{false, true}) {
            File payload = encryptedPayload(); IOException failure = new IOException("physical read");
            try (SplitVolumeInput input = source(payload)) {
                intercept(input, payload, 0, () -> { throw failure; });
                assertSame(failure, assertThrows(IOException.class, () ->
                        probe(egg, input, entry(egg, 0, true, true), token())));
            }
        }
    }

    @Test(timeout = 5000) public void finalProbeObservationDoesNotWaitAfterSourceValidation() throws Exception {
        for (boolean egg : new boolean[]{false, true}) {
            for (boolean match : new boolean[]{false, true}) {
                File payload = encryptedPayload(); FileOperationProgress progress = token();
                boolean[] armed = {false}; int[] checks = {0};
                File observed = new File(payload.getPath()) {
                    @Override public File getCanonicalFile() { return this; }
                    @Override public long lastModified() {
                        if (armed[0]) { checks[0]++; progress.setPaused(true); }
                        return super.lastModified();
                    }
                };
                try (SplitVolumeInput input = source(observed)) {
                    intercept(input, payload, 0, () -> armed[0] = true);
                    assertEquals(match, probe(egg, input, entry(egg, 0, match, true), progress));
                    assertTrue(progress.isPaused()); assertFalse(progress.isCancelled());
                    assertEquals(1, checks[0]);
                } finally { progress.setPaused(false); }
            }
        }
    }

    @Test public void cancellationDuringFinalEntryValidationRollsBackBothFormats() throws Exception {
        finalEntryBoundary(true);
    }

    @Test public void activeTokenDuringFinalEntryValidationCommitsBothFormats() throws Exception {
        finalEntryBoundary(false);
    }

    private void finalEntryBoundary(boolean cancel) throws Exception {
        for (boolean egg : new boolean[]{false, true}) {
            File payload = temp.newFile(); Files.write(payload.toPath(), PLAIN);
            File directory = temp.newFolder(), target = new File(directory, "entry");
            Files.write(target.toPath(), new byte[]{42});
            FileOperationProgress progress = token(); boolean[] armed = {false}; int[] checks = {0};
            progress.setListener(snapshot -> { if (snapshot.doneBytes == PLAIN.length) armed[0] = true; });
            File observed = observed(payload, armed, checks, progress, cancel);
            try (SplitVolumeInput input = source(observed)) {
                Object entry = entry(egg, 0, true, false);
                if (cancel) assertThrows(InterruptedIOException.class, () -> extract(egg, input, entry, target, progress));
                else extract(egg, input, entry, target, progress);
            }
            assertEquals(1, checks[0]);
            assertArrayEquals(cancel ? new byte[]{42} : PLAIN, Files.readAllBytes(target.toPath()));
            assertEquals(1, directory.list().length);
        }
    }

    private static File observed(File file, boolean[] armed, int[] checks,
                                 FileOperationProgress progress, boolean cancel) {
        return new File(file.getPath()) {
            @Override public File getCanonicalFile() { return this; }
            @Override public long lastModified() {
                if (armed[0]) { checks[0]++; if (cancel) progress.cancel(); }
                return super.lastModified();
            }
        };
    }

    private File encryptedPayload() throws IOException {
        File file = temp.newFile();
        Files.write(file.toPath(), new AlzipZipCryptoTest.Encryptor(PASSWORD).encrypt(PLAIN));
        return file;
    }

    private static void changeTimestamp(File file) throws IOException {
        long previous = file.lastModified();
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(previous + 10000));
        assertTrue(file.lastModified() != previous);
    }

    private static SplitVolumeInput source(File... files) throws IOException {
        List<SplitVolumeInput.Segment> segments = new ArrayList<>();
        for (File file : files) segments.add(new SplitVolumeInput.Segment(file, 0, file.length()));
        return new SplitVolumeInput(segments);
    }

    @FunctionalInterface private interface ReadAction { void run() throws IOException; }

    private static void intercept(SplitVolumeInput input, File physical, long offset, ReadAction action) throws Exception {
        // Prime the correct segment, then replace only its handle. Cursor and
        // captured metadata remain real; the payload read deterministically fires the hook.
        input.seek(offset); assertTrue(input.read() >= 0); input.seek(0);
        Field field = SplitVolumeInput.class.getDeclaredField("activeFile"); field.setAccessible(true);
        ((RandomAccessFile) field.get(input)).close();
        field.set(input, new RandomAccessFile(physical, "r") {
            boolean fired;
            @Override public int read(byte[] bytes, int start, int length) throws IOException {
                int count = super.read(bytes, start, length);
                if (count > 0 && !fired) { fired = true; action.run(); }
                return count;
            }
        });
    }

    private static Object entry(boolean egg, long offset, boolean match, boolean encrypted) throws Exception {
        CRC32 crc = new CRC32(); crc.update(PLAIN);
        long expectedCrc = match ? crc.getValue() : crc.getValue() ^ 1L;
        if (!egg) {
            Class<?> type = Class.forName(AlzipArchiveReader.class.getName() + "$AlzEntry");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class, boolean.class, int.class,
                    boolean.class, boolean.class, long.class, long.class, long.class, long.class, byte[].class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance("entry", false, 0, encrypted, false, expectedCrc,
                    (long) PLAIN.length, (long) PLAIN.length, offset, null, 0L);
        }
        Class<?> type = Class.forName(EggArchiveReader.class.getName() + "$EggEntry");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object entry = constructor.newInstance();
        set(entry, "path", "entry"); set(entry, "uncompressedSize", (long) PLAIN.length);
        Class<?> blockType = Class.forName(EggArchiveReader.class.getName() + "$EggBlock");
        Constructor<?> blockConstructor = blockType.getDeclaredConstructor(); blockConstructor.setAccessible(true);
        Object block = blockConstructor.newInstance();
        set(block, "owner", entry); set(block, "dataOffset", offset); set(block, "crc", expectedCrc);
        set(block, "compSize", (long) PLAIN.length); set(block, "uncompSize", (long) PLAIN.length);
        Field blocks = type.getDeclaredField("blocks"); blocks.setAccessible(true);
        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) blocks.get(entry);
        list.add(block);
        return entry;
    }

    private static boolean probe(boolean egg, SplitVolumeInput input, Object entry, FileOperationProgress progress)
            throws Exception {
        Class<?> reader = egg ? EggArchiveReader.class : AlzipArchiveReader.class;
        Method method = reader.getDeclaredMethod("passwordMatches", SplitVolumeInput.class, entry.getClass(),
                AlzipZipCrypto.class, FileOperationProgress.class);
        method.setAccessible(true);
        AlzipZipCrypto crypto = new AlzipZipCrypto(PASSWORD);
        try { return (Boolean) invoke(method, input, entry, crypto, progress); }
        finally { crypto.destroy(); }
    }

    private static void extract(boolean egg, SplitVolumeInput input, Object entry, File target,
                                FileOperationProgress progress) throws Exception {
        Class<?> reader = egg ? EggArchiveReader.class : AlzipArchiveReader.class;
        Method method = reader.getDeclaredMethod(egg ? "writeEntry" : "extractEntryPayload",
                SplitVolumeInput.class, entry.getClass(), File.class, char[].class, FileOperationProgress.class);
        method.setAccessible(true);
        if (egg) {
            // The public non-solid EGG loop owns the guard outside writeEntry.
            try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(target)) {
                invoke(method, input, entry, target, null, progress); guard.commit();
            }
        } else invoke(method, input, entry, target, null, progress);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }

    private static Object invoke(Method method, Object... args) throws Exception {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new AssertionError(failure);
        }
    }

    private static FileOperationProgress token() { return new FileOperationProgress("probe", null); }
}
