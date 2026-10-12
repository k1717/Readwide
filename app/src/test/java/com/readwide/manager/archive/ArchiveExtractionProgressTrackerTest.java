package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.InterruptedIOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Source regressions only; no execution or measured UI-speed claim. */
public class ArchiveExtractionProgressTrackerTest {
    private static ArchiveSupport.EntryInfo entry(String path, boolean directory) {
        return new ArchiveSupport.EntryInfo(path, directory, 0, 0);
    }

    @Test public void nestedEmptyDirectoryIncludesImplicitAncestors() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        ArchiveExtractionProgressTracker tracker = ArchiveExtractionProgressTracker.create(progress,
                Arrays.asList(entry("a/b/", true), entry("c/", true)));
        tracker.onDirectory("a/b/");
        assertEquals("b", progress.snapshot().folder);
        assertEquals(2, progress.snapshot().folderIndex);
        assertEquals(3, progress.snapshot().folderTotal);
        tracker.onDirectory("a/");
        assertEquals(2, progress.snapshot().folderIndex);
        tracker.onDirectory("c/");
        assertEquals(3, progress.snapshot().folderIndex);
    }

    @Test public void siblingFilesDoNotRepublishEachAncestor() throws Exception {
        List<FileOperationProgress.Snapshot> updates = new ArrayList<>();
        FileOperationProgress progress = new FileOperationProgress("extract", updates::add);
        ArchiveExtractionProgressTracker tracker = ArchiveExtractionProgressTracker.create(progress,
                Arrays.asList(entry("a/b/c/1", false), entry("a/b/c/2", false)));
        updates.clear();
        tracker.onFile("a/b/c/1");
        assertEquals(4, updates.size()); // Deepest folder, folder count, detail, file count.
        assertEquals("c", updates.get(0).folder);
        assertEquals(3, progress.snapshot().folderIndex);
        updates.clear();
        tracker.onFile("a/b/c/2");
        assertEquals(2, updates.size()); // Only the file detail and count changed.
        assertEquals(2, progress.snapshot().itemIndex);
        assertEquals(2, progress.snapshot().itemTotal);
    }

    @Test public void rootFileClearsStaleFolderWithoutResettingCount() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        ArchiveExtractionProgressTracker tracker = ArchiveExtractionProgressTracker.create(progress,
                Arrays.asList(entry("chapter/1", false), entry("root.txt", false)));
        tracker.onFile("chapter/1");
        tracker.onFile("root.txt");
        assertEquals("", progress.snapshot().folder);
        assertEquals(1, progress.snapshot().folderIndex);
        assertEquals(1, progress.snapshot().folderTotal);
        assertEquals("root.txt", progress.snapshot().detail);
    }

    @Test public void outerQueueFolderProgressIsPreserved() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.setFolder("archive queue");
        progress.setFolderProgress(2, 5);
        ArchiveExtractionProgressTracker tracker = ArchiveExtractionProgressTracker.create(progress,
                Arrays.asList(entry("a/b/", true), entry("a/b/file", false)));
        tracker.onDirectory("a/b/");
        tracker.onFile("a/b/file");
        assertEquals("archive queue", progress.snapshot().folder);
        assertEquals(2, progress.snapshot().folderIndex);
        assertEquals(5, progress.snapshot().folderTotal);
        assertEquals(1, progress.snapshot().itemTotal);
    }

    @Test public void aliasesShareFoldersAndUnlistedFoldersDoNotInflateTheCount() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        ArchiveExtractionProgressTracker tracker = ArchiveExtractionProgressTracker.create(progress,
                Arrays.asList(null, entry("../unsafe/", true), entry("./a//b/", true),
                        entry("a/b/file", false), entry("c/", true)));
        tracker.onDirectory("a\\b\\");
        tracker.onDirectory("unlisted/");
        tracker.onDirectory("../unsafe/");
        tracker.onFile("./a//b/file");
        assertEquals(2, progress.snapshot().folderIndex);
        assertEquals(3, progress.snapshot().folderTotal);
        assertEquals(1, progress.snapshot().itemTotal);
    }

    @Test public void cancelledSetupDoesNotClearExistingProgress() {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.setItemProgress(2, 3);
        progress.cancel();
        assertThrows(InterruptedIOException.class, () -> ArchiveExtractionProgressTracker.create(
                progress, Collections.singletonList(entry("a/b", false))));
        assertEquals(2, progress.snapshot().itemIndex);
    }

    @Test public void cancellationDuringEnumerationStopsBeforeTheNextEntry() {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        List<ArchiveSupport.EntryInfo> entries = new AbstractList<ArchiveSupport.EntryInfo>() {
            @Override public int size() { return 100; }
            @Override public ArchiveSupport.EntryInfo get(int index) {
                if (index > 0) fail("Cancelled setup must not keep enumerating");
                progress.cancel();
                return entry("a/b/c", false);
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> ArchiveExtractionProgressTracker.create(progress, entries));
    }

    @Test public void optionalTrackerAndEmptyListingKeepExistingContracts() throws Exception {
        assertNull(ArchiveExtractionProgressTracker.create(null, Collections.emptyList()));
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        assertNull(ArchiveExtractionProgressTracker.create(progress, null));
        ArchiveExtractionProgressTracker tracker = ArchiveExtractionProgressTracker.create(
                progress, Collections.emptyList());
        tracker.onDirectory("missing/");
        assertEquals(0, progress.snapshot().folderTotal);
        assertEquals(0, progress.snapshot().itemTotal);
    }
}
