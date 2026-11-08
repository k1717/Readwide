package com.readwide.manager.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SharedStoragePathsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void internalSharedFilesQualifyWithoutRemovableStorage() throws Exception {
        File internal = temporary.newFolder("internal");
        assertTrue(SharedStoragePaths.containsAny(internal, Collections.emptyList(),
                Collections.singletonList(new File(internal, "Books/book.txt"))));
        assertTrue(SharedStoragePaths.containsAny(internal, null, Collections.singletonList(internal)));
    }

    @Test public void anyAdditionalVolumeCanQualify() throws Exception {
        File internal = temporary.newFolder("internal");
        File sd = temporary.newFolder("sd");
        File usb = temporary.newFolder("usb");
        File ssd = temporary.newFolder("ssd");
        for (File root : Arrays.asList(sd, usb, ssd)) {
            assertTrue(SharedStoragePaths.containsAny(internal, Arrays.asList(sd, usb, ssd),
                    Arrays.asList(null, new File(root, "Books/new.txt"))));
        }
    }

    @Test public void siblingPrefixAndPrivatePathsDoNotQualify() throws Exception {
        File internal = temporary.newFolder("internal");
        assertFalse(SharedStoragePaths.containsAny(internal, Collections.emptyList(),
                Collections.singletonList(new File(temporary.getRoot(), "internal-other/book.txt"))));
        assertFalse(SharedStoragePaths.containsAny(internal, Collections.emptyList(),
                Collections.singletonList(new File(temporary.getRoot(), "private/book.txt"))));
    }

    @Test public void parentTraversalCannotEscapeRecognizedRoot() throws Exception {
        File internal = temporary.newFolder("internal");
        assertFalse(SharedStoragePaths.containsAny(internal, null,
                Collections.singletonList(new File(internal, "../private/book.txt"))));
        assertTrue(SharedStoragePaths.containsAny(internal, null,
                Collections.singletonList(new File(internal, "Books/../book.txt"))));
    }

    @Test public void rootsDeduplicateAliasesAndExcludePrimaryAndNonDirectories() throws Exception {
        File internal = temporary.newFolder("internal");
        File usb = temporary.newFolder("usb");
        File regular = temporary.newFile("not-volume");
        List<File> roots = new ArrayList<>();
        SharedStoragePaths.addReadableRoot(roots, internal, internal);
        SharedStoragePaths.addReadableRoot(roots, usb, internal);
        SharedStoragePaths.addReadableRoot(roots, new File(usb, "."), internal);
        SharedStoragePaths.addReadableRoot(roots, regular, internal);
        SharedStoragePaths.addReadableRoot(roots, new File(temporary.getRoot(), "detached"), internal);
        SharedStoragePaths.addReadableRoot(roots, null, internal);
        assertEquals(Collections.singletonList(usb.getCanonicalFile()), roots);
    }

    @Test public void appFilesSuffixDoesNotTruncateAndroidNamedVolume() throws Exception {
        File root = temporary.newFolder("AndroidBackupUSB");
        File app = new File(root, "Android/data/com.readwide.manager/files");
        assertEquals(root.getAbsoluteFile(),
                SharedStoragePaths.rootFromAppFiles(app, "com.readwide.manager"));
    }

    @Test public void unrelatedAppPathsAreNotGuessedAsVolumeRoots() throws Exception {
        File root = temporary.newFolder("usb");
        assertNull(SharedStoragePaths.rootFromAppFiles(new File(root, "Android/books"), "com.readwide.manager"));
        assertNull(SharedStoragePaths.rootFromAppFiles(new File(root, "Android/data/other/files"), "com.readwide.manager"));
        assertNull(SharedStoragePaths.rootFromAppFiles(new File(root, "Android/data/com.readwide.manager/files/child"), "com.readwide.manager"));
    }

    @Test public void absentPathsAreHandledWithoutExceptions() {
        assertFalse(SharedStoragePaths.containsAny(null, null, null));
        assertFalse(SharedStoragePaths.containsAny(null, Arrays.asList((File) null), Arrays.asList((File) null)));
        assertNull(SharedStoragePaths.rootFromAppFiles(null, "com.readwide.manager"));
        assertNull(SharedStoragePaths.rootFromAppFiles(new File("files"), null));
    }
}
