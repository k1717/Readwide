package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** No-stream files have no FolderStream to own their replacement transaction. */
public class SevenZEmptyAcceptanceTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] ORIGINAL = {8, 9, 10};

    @Test public void detailCancellationDoesNotReplaceExistingEmptyEntryTarget() throws Exception {
        checkDetailCancellation(false);
    }

    @Test public void trackedDetailCancellationDoesNotCreateEmptyEntryTarget() throws Exception {
        checkDetailCancellation(true);
    }

    private void checkDetailCancellation(boolean tracked) throws Exception {
        File archive = fixture(), root = temporary.newFolder(), target = new File(root, "file0");
        if (!tracked) Files.write(target.toPath(), ORIGINAL);
        FileOperationProgress[] holder = new FileOperationProgress[1];
        boolean[] notified = {false};
        holder[0] = new FileOperationProgress("7z", snapshot -> {
            if (!notified[0] && "file0".equals(snapshot.detail)) {
                notified[0] = true;
                holder[0].cancel();
            }
        });
        ArchiveExtractionProgressTracker tracker = tracked ? ArchiveExtractionProgressTracker.create(
                holder[0], SevenZBcj2ArchiveReader.listEntries(archive, null)) : null;
        assertThrows(InterruptedIOException.class, () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(
                archive, root, null, holder[0], tracker));
        assertTrue(notified[0]);
        if (tracked) assertFalse(target.exists());
        else assertArrayEquals(ORIGINAL, Files.readAllBytes(target.toPath()));
        assertEquals(tracked ? 0 : 1, root.list().length);
    }

    @Test public void sourceCheckCancellationRollsBackEmptyReplacement() throws Exception {
        checkAcceptance(1);
    }

    @Test public void changedSourceRollsBackEmptyReplacement() throws Exception {
        checkAcceptance(2);
    }

    @Test public void unchangedSourceWithActiveTokenCommitsEmptyReplacement() throws Exception {
        checkAcceptance(0);
    }

    @Test public void unchangedSourceWithoutTokenCommitsEmptyReplacement() throws Exception {
        checkAcceptance(3);
    }

    private void checkAcceptance(int mode) throws Exception {
        File archive = fixture(), root = temporary.newFolder(), target = new File(root, "file0");
        Files.write(target.toPath(), ORIGINAL);
        FileOperationProgress progress = mode == 3 ? null : new FileOperationProgress("7z", null);
        boolean[] checked = {false};
        File observed = new File(archive.getPath()) {
            @Override public boolean canRead() {
                boolean readable = super.canRead();
                if (target.isFile() && target.length() == 0) {
                    checked[0] = true;
                    if (mode == 1) progress.cancel();
                }
                return readable;
            }
            @Override public long lastModified() {
                return super.lastModified() + (mode == 2 && checked[0] ? 10000 : 0);
            }
        };
        if (mode == 1 || mode == 2) {
            IOException failure = assertThrows(IOException.class,
                    () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(observed, root, null, progress, null));
            if (mode == 1) assertTrue(failure instanceof InterruptedIOException);
            else assertTrue(failure.getMessage().contains("source changed"));
            assertArrayEquals(ORIGINAL, Files.readAllBytes(target.toPath()));
        } else {
            assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(observed, root, null, progress, null));
            assertTrue(target.isFile());
            assertEquals(0, target.length());
        }
        assertTrue(checked[0]);
        assertArrayEquals(new String[]{"file0"}, root.list());
    }

    private File fixture() throws IOException {
        return Round5Fixtures.emptySevenZ(temporary.newFolder(), 1, Round5Fixtures.emptyProperties(1, true));
    }
}
