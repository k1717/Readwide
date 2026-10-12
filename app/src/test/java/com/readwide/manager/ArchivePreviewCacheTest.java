package com.readwide.manager;

import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ArchivePreviewCacheTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();

    @Test public void cacheInventoryAndSizeIgnoreLinkedAndCyclicTrees() throws Exception {
        File root = temporary.newFolder();
        File own = new File(root, "own.avif"); java.nio.file.Files.write(own.toPath(), new byte[]{1, 2, 3});
        File outside = temporary.newFolder();
        java.nio.file.Files.write(new File(outside, "other.avif").toPath(), new byte[20]);
        link(new File(root, "outside"), outside); link(new File(root, "cycle"), root);
        try {
            assertEquals(3L, ((Long) call(ArchivePreviewCache.class, "sizeOf", new Class<?>[]{File.class}, root)).longValue());
            java.util.List<File> files = new java.util.ArrayList<>();
            call(ArchiveImageEntryCache.class, "collectFiles", new Class<?>[]{File.class, java.util.List.class}, root, files);
            assertEquals(java.util.Collections.singletonList(own), files);
        } finally {
            java.nio.file.Files.deleteIfExists(new File(root, "cycle").toPath());
            java.nio.file.Files.deleteIfExists(new File(root, "outside").toPath());
        }
    }

    @Test public void cacheCleanupNeverFollowsLinksAndRunsAfterCancellation() throws Exception {
        File outside = temporary.newFolder();
        File keep = new File(outside, "keep"); java.nio.file.Files.write(keep.toPath(), new byte[]{9});
        for (Class<?> owner : new Class<?>[]{ArchivePreviewCache.class, ArchiveImageEntryCache.class}) {
            File root = temporary.newFolder();
            link(new File(root, "outside"), outside); link(new File(root, "cycle"), root);
            link(new File(root, "dangling"), new File(outside, "missing"));
            Thread.currentThread().interrupt();
            try {
                call(owner, "deleteRecursively", new Class<?>[]{File.class}, root);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
                java.nio.file.Files.deleteIfExists(new File(root, "cycle").toPath());
                java.nio.file.Files.deleteIfExists(new File(root, "outside").toPath());
                java.nio.file.Files.deleteIfExists(new File(root, "dangling").toPath());
            }
            assertFalse(root.exists());
            org.junit.Assert.assertArrayEquals(new byte[]{9}, java.nio.file.Files.readAllBytes(keep.toPath()));
        }
    }

    @Test public void unreadableInventoryCannotPublishAPartialFileList() throws Exception {
        File original = temporary.newFolder();
        File unreadable = new File(original.getPath()) {
            @Override public File[] listFiles() { return null; }
        };
        java.util.List<File> files = new java.util.ArrayList<>();
        org.junit.Assert.assertThrows(java.io.IOException.class, () -> call(
                ArchiveImageEntryCache.class, "collectFiles", new Class<?>[]{File.class, java.util.List.class}, unreadable, files));
        assertEquals(Long.MAX_VALUE, ((Long) call(
                ArchivePreviewCache.class, "sizeOf", new Class<?>[]{File.class}, unreadable)).longValue());
    }

    @Test public void failedPruningDoesNotCountUnremovedDirectoryAsReclaimed() throws Exception {
        File parent = temporary.newFolder();
        File retained = new File(parent, "retained"); assertTrue(retained.mkdir());
        File removable = new File(parent, "removable"); assertTrue(removable.mkdir());
        assertTrue(retained.setLastModified(1000)); assertTrue(removable.setLastModified(2000));
        File blocked = new File(retained.getPath()) { @Override public boolean delete() { return false; } };
        File root = new File(parent.getPath()) {
            @Override public File[] listFiles() { return new File[]{blocked, removable}; }
        };
        call(ArchivePreviewCache.class, "pruneRoot",
                new Class<?>[]{File.class, String.class, int.class, long.class, long.class},
                root, "active", 1, Long.MAX_VALUE, Long.MAX_VALUE);
        assertTrue(retained.exists());
        assertFalse(removable.exists());
    }

    private static void link(File link, File target) throws java.io.IOException {
        try { java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath()); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.Assume.assumeNoException("Host cannot create symbolic links", unavailable);
        }
    }

    private static Object call(Class<?> owner, String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method method = owner.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }

    @Test public void whitespaceInEntryIdentityNeverAliasesAnotherPreview() {
        assertNotEquals(ArchivePreviewCache.cacheFileNameForEntry("page.jpg"),
                ArchivePreviewCache.cacheFileNameForEntry(" page.jpg"));
        assertNotEquals(ArchivePreviewCache.cacheFileNameForEntry("page.jpg"),
                ArchivePreviewCache.cacheFileNameForEntry("page.jpg "));
        assertEquals(" chapter/page.jpg ", ArchivePreviewCache.normalizeEntryPath(" chapter/page.jpg "));
    }

    @Test
    public void entryCacheNameKeepsFullPathHashToAvoidSanitizedNameCollisions() {
        String nested = ArchivePreviewCache.cacheFileNameForEntry("a/b.png");
        String flat = ArchivePreviewCache.cacheFileNameForEntry("a_b.png");

        assertTrue(nested.endsWith("_b.png"));
        assertTrue(flat.endsWith("_a_b.png"));
        assertNotEquals(nested, flat);
    }

    @Test
    public void archiveFingerprintChangesWhenArchiveMetadataChanges() throws Exception {
        File temp = File.createTempFile("archive-preview-cache", ".zip");
        try {
            java.nio.file.Files.write(temp.toPath(), new byte[] {1, 2, 3});
            temp.setLastModified(1700000000000L);
            String first = ArchivePreviewCache.archiveFingerprint(temp);

            java.nio.file.Files.write(temp.toPath(), new byte[] {1, 2, 3, 4});
            temp.setLastModified(1700000001000L);
            String second = ArchivePreviewCache.archiveFingerprint(temp);

            assertNotEquals(first, second);
            assertEquals(24, first.length());
            assertEquals(24, second.length());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    @Test
    public void sensitiveArchiveImageReadyMarkersRequireCurrentSessionVerification() {
        String cacheKey = "/tmp/readwide-preview/page.png";

        assertTrue(ArchiveImageEntryCache.canReuseReadyMarkerForCache(false, null, cacheKey));
        assertFalse(ArchiveImageEntryCache.canReuseReadyMarkerForCache(true, null, cacheKey));

        Set<String> verified = new HashSet<>();
        assertFalse(ArchiveImageEntryCache.canReuseReadyMarkerForCache(true, verified, cacheKey));
        assertTrue(ArchiveImageEntryCache.shouldDiscardUnverifiedSensitiveReadyCache(true, verified, cacheKey));

        verified.add(cacheKey);
        assertTrue(ArchiveImageEntryCache.canReuseReadyMarkerForCache(true, verified, cacheKey));
        assertFalse(ArchiveImageEntryCache.shouldDiscardUnverifiedSensitiveReadyCache(true, verified, cacheKey));
    }

    @Test
    public void forwardReadyFileGateDoesNotBypassSensitiveSessionVerification() throws Exception {
        File ready = File.createTempFile("readwide-sensitive-ready", ".image");
        File marker = new File(ready.getAbsolutePath() + ".ready");
        try {
            java.nio.file.Files.write(ready.toPath(), new byte[] {1, 2, 3});
            assertTrue(marker.createNewFile());

            assertFalse(ArchiveImageEntryCache.shouldReuseReadyImageFile(
                    "page.image", ready, true, null));

            Set<String> verified = new HashSet<>();
            verified.add(ready.getAbsolutePath());
            assertTrue(ArchiveImageEntryCache.shouldReuseReadyImageFile(
                    "page.image", ready, true, verified));
            assertTrue(ArchiveImageEntryCache.shouldReuseReadyImageFile(
                    "page.image", ready, false, null));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            marker.delete();
            //noinspection ResultOfMethodCallIgnored
            ready.delete();
        }
    }
}
