package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SevenZCommonsSourceChangeTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] DATA = Round4Fixtures.data(131073);
    private static final byte[] ORIGINAL = {42};

    @Test public void unchangedCopyWithoutPayloadChecksumsStillExtracts() throws Exception {
        File archive = fixture(), directory = new File(temporary.newFolder(), "output");
        assertTrue(ArchiveSupport.extractArchiveDetailed(archive, directory, false, null, null).success);
        assertArrayEquals(DATA, Files.readAllBytes(new File(directory, "data.bin").toPath()));
        File output = temporary.newFile();
        assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive, "data.bin", output, null).success);
        assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
    }

    @Test public void finalPayloadCallbackCannotAcceptChangedOrdinarySource() throws Exception {
        rejectBulkMutation(fixture(), null, DATA.length, false);
    }

    @Test public void finalPayloadCallbackKeepsExistingDestinationTree() throws Exception {
        rejectBulkMutation(fixture(), null, DATA.length, true);
    }

    @Test public void sourceChangedAfterListingCannotPublishANewDestination() throws Exception {
        File archive = fixture(), directory = new File(temporary.newFolder(), "output");
        boolean[] mutated = {false};
        FileOperationProgress progress = new FileOperationProgress("7z", snapshot -> {
            if (!mutated[0] && snapshot.totalBytes == DATA.length) {
                mutated[0] = true;
                touch(archive);
            }
        });
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, directory, false, null, progress);
        assertTrue(mutated[0]);
        assertFalse(result.success);
        assertTrue(result.detail, result.detail != null && result.detail.contains("source changed"));
        assertFalse(directory.exists());
    }

    @Test public void currentSplitPartChangeCannotAcceptChecksumFreeOutput() throws Exception {
        File[] parts = split(fixture());
        rejectBulkMutation(parts[2], parts[1], 65536, false);
    }

    @Test public void previouslyConsumedSplitPartIsRechecked() throws Exception {
        File[] parts = split(fixture());
        rejectBulkMutation(parts[2], parts[0], 65536, true);
    }

    @Test public void selectedSourceChangeAfterProbeRestoresExistingOutput() throws Exception {
        rejectSelectedMutation(false);
    }

    @Test public void sourceChangeWithOutputFailureCannotReachFallback() throws Exception {
        rejectSelectedMutation(true);
    }

    @Test public void unchangedMissingSelectedMemberKeepsFalseResult() throws Exception {
        File output = temporary.newFile();
        Files.write(output.toPath(), ORIGINAL);
        assertFalse((Boolean) call("extractSingleSevenZEntryWithFallback", fixture(),
                ArchiveSupport.Type.SEVEN_Z, "missing.bin", output, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    @Test public void unresolvedSnapshotCannotBecomeValidAfterMissingVolumeAppears() throws Exception {
        File[] parts = split(fixture());
        byte[] saved = Files.readAllBytes(parts[1].toPath());
        assertTrue(parts[1].delete());
        ArchiveSourceSnapshot source = ArchiveSourceSnapshot.capture(parts[2]);
        Files.write(parts[1].toPath(), saved);
        IOException failure = assertThrows(IOException.class,
                () -> call("requireExtractionSourceUnchanged", source, parts[2], null));
        assertTrue(failure instanceof ArchiveSourceSnapshot.CaptureException);
        assertNotNull(failure.getCause());
        assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                ArchiveFailureClassifier.classify(failure));
    }

    @Test public void cancellationDuringFinalValidationRemainsCancellation() throws Exception {
        File archive = fixture();
        ArchiveSourceSnapshot source = ArchiveSourceSnapshot.capture(archive);
        FileOperationProgress progress = new FileOperationProgress("7z", null);
        File observed = new File(archive.getPath()) {
            @Override public boolean canRead() {
                progress.cancel();
                return super.canRead();
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> call("requireExtractionSourceUnchanged", source, observed, progress));
        assertTrue(progress.isCancelled());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test(timeout = 5000) public void finalValidationDoesNotPauseAfterItsSourceCheck() throws Exception {
        File archive = fixture();
        ArchiveSourceSnapshot source = ArchiveSourceSnapshot.capture(archive);
        FileOperationProgress progress = new FileOperationProgress("7z", null);
        File observed = new File(archive.getPath()) {
            @Override public boolean canRead() {
                progress.setPaused(true);
                return super.canRead();
            }
        };
        try {
            call("requireExtractionSourceUnchanged", source, observed, progress);
            assertTrue(progress.isPaused());
        } finally {
            progress.setPaused(false);
        }
    }

    private void rejectBulkMutation(File selected, File changedPart, long trigger,
                                    boolean overwrite) throws Exception {
        File changed = changedPart == null ? selected : changedPart;
        File directory = new File(temporary.newFolder(), "output");
        File sentinel = new File(directory, "keep");
        if (overwrite) {
            assertTrue(directory.mkdir());
            Files.write(sentinel.toPath(), ORIGINAL);
        }
        boolean[] mutated = {false};
        FileOperationProgress progress = new FileOperationProgress("7z", snapshot -> {
            if (!mutated[0] && snapshot.doneBytes >= trigger) {
                mutated[0] = true;
                touch(changed);
            }
        });
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                selected, directory, overwrite, null, progress);
        assertTrue("The fixture must reach payload extraction", mutated[0]);
        assertFalse(result.success);
        assertTrue(result.detail, result.detail != null && result.detail.contains("source changed"));
        if (overwrite) {
            assertArrayEquals(ORIGINAL, Files.readAllBytes(sentinel.toPath()));
            assertArrayEquals(new String[]{"keep"}, directory.list());
        } else {
            assertFalse(directory.exists());
        }
        assertArrayEquals(overwrite ? new String[]{"output"} : new String[0], directory.getParentFile().list());
    }

    private void rejectSelectedMutation(boolean failOutput) throws Exception {
        File archive = fixture(), original = temporary.newFile();
        Files.write(original.toPath(), ORIGINAL);
        boolean[] mutated = {false};
        File output = new File(original.getPath()) {
            @Override public File getAbsoluteFile() {
                if (!mutated[0]) {
                    mutated[0] = true;
                    touch(archive);
                    if (failOutput) throw new SecurityException("injected output access failure");
                }
                return super.getAbsoluteFile();
            }
        };
        IOException failure;
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(original)) {
            failure = assertThrows(IOException.class, () -> call("extractSingleSevenZEntryWithFallback",
                    archive, ArchiveSupport.Type.SEVEN_Z, "data.bin", output, null));
        }
        assertTrue(mutated[0]);
        assertTrue(failure.getMessage().contains("source changed"));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(original.toPath()));
    }

    private File fixture() throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(4);
        // COPY with no packed, folder or substream CRC. Header CRCs remain valid.
        header.write(6); Round4Fixtures.number(header, 0); Round4Fixtures.number(header, 1);
        header.write(9); Round4Fixtures.number(header, DATA.length); header.write(0);
        header.write(7); header.write(11); Round4Fixtures.number(header, 1); header.write(0);
        Round4Fixtures.number(header, 1); header.write(1); header.write(0);
        header.write(12); Round4Fixtures.number(header, DATA.length); header.write(0);
        header.write(8); header.write(0); header.write(0);
        header.write(5); Round4Fixtures.number(header, 1);
        byte[] name = "data.bin\0".getBytes(StandardCharsets.UTF_16LE);
        header.write(17); Round4Fixtures.number(header, name.length + 1);
        header.write(0); header.write(name); header.write(0); header.write(0);
        File archive = Round5Fixtures.wrap(temporary.newFolder(), DATA, header.toByteArray());
        assertFalse("COPY must retain the ordinary Commons route",
                SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        return archive;
    }

    private File[] split(File archive) throws IOException {
        byte[] bytes = Files.readAllBytes(archive.toPath());
        File directory = temporary.newFolder();
        int[] cuts = {0, 48, 32 + DATA.length, bytes.length};
        File[] parts = new File[3];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = Round4Fixtures.write(directory, "book.7z.00" + (i + 1),
                    Arrays.copyOfRange(bytes, cuts[i], cuts[i + 1]));
        }
        return parts;
    }

    private static void touch(File file) {
        assertTrue(file.setLastModified(file.lastModified() + 5000));
    }

    private static Object call(String name, Object... arguments) throws Exception {
        Method method = Arrays.stream(ArchiveSupport.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().get();
        method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }
}
