package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Synthetic containers for supplemental dispatch and retained source ownership. */
public class SevenZSupplementalExtractionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] CONTENT = Round4Fixtures.data(129);
    private static final byte[] ORIGINAL = {31, 41, 59};

    @Test public void selectedDispatchAdoptsEncodedHeaderSourceOnlyOnce() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) {
            CountingFile archive = new CountingFile(fixture(encrypted, false, true, false));
            File output = temporary.newFile();
            char[] password = encrypted ? Round4Fixtures.PASSWORD : null;
            assertEquals(Boolean.TRUE, selected(archive, "data.bin", output, password));
            assertEquals(1, archive.adoptions);
            assertArrayEquals(CONTENT, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void bulkDispatchAdoptsEncodedHeaderSourceOnlyOnce() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) {
            CountingFile archive = new CountingFile(fixture(encrypted, false, true, false));
            File root = temporary.newFolder();
            char[] password = encrypted ? Round4Fixtures.PASSWORD : null;
            assertEquals(Boolean.TRUE, bulk(archive, root, password, null));
            assertEquals(1, archive.adoptions);
            assertArrayEquals(CONTENT, Files.readAllBytes(new File(root, "data.bin").toPath()));
        }
    }

    @Test public void splitEncryptedSolidPayloadKeepsBothExtractionRoutes() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) {
            File archive = split(fixture(encrypted, true, false, false));
            char[] password = encrypted ? Round4Fixtures.PASSWORD.clone() : null;
            File output = temporary.newFile(), root = temporary.newFolder();
            assertEquals(Boolean.TRUE, selected(archive, "2.bin", output, password));
            assertEquals(Boolean.TRUE, bulk(archive, root, password, null));
            int first = CONTENT.length / 2;
            byte[] second = Arrays.copyOfRange(CONTENT, first, CONTENT.length);
            assertArrayEquals(second, Files.readAllBytes(output.toPath()));
            assertArrayEquals(Arrays.copyOf(CONTENT, first),
                    Files.readAllBytes(new File(root, "1.bin").toPath()));
            assertArrayEquals(second, Files.readAllBytes(new File(root, "2.bin").toPath()));
            if (encrypted) assertArrayEquals(Round4Fixtures.PASSWORD, password);
        }
    }

    @Test public void ordinaryCopyDeclinesSupplementalButDirectApisStillExtract() throws Exception {
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), CONTENT, 0,
                false, false, false, false);
        File root = temporary.newFolder(), output = sentinel(temporary.newFolder(), "page");
        assertNull(selected(archive, "data.bin", output, null));
        assertNull(bulk(archive, root, null, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
        assertEquals(0, root.list().length);
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, root, null, null, null));
        assertArrayEquals(CONTENT, Files.readAllBytes(output.toPath()));
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(root, "data.bin").toPath()));
    }

    @Test public void selectedMissingMemberReturnsFalseWithoutRequestingFallback() throws Exception {
        File archive = fixture(false, false, false, false);
        File output = sentinel(temporary.newFolder(), "page");
        assertEquals(Boolean.FALSE, selected(archive, "missing.bin", output, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    @Test public void unhandledHeaderDeclinesWithoutChangingOutputs() throws Exception {
        File archive = fixture(false, false, false, false);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[6] = 1;
        Files.write(archive.toPath(), bytes);
        File output = sentinel(temporary.newFolder(), "page"), root = temporary.newFolder();
        assertNull(selected(archive, "data.bin", output, null));
        assertNull(bulk(archive, root, null, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
        assertEquals(0, root.list().length);
    }

    @Test public void encryptedHeaderPasswordFailuresRemainTerminalBeforeOutput() throws Exception {
        File archive = fixture(true, false, false, false);
        File output = sentinel(temporary.newFolder(), "page"), root = temporary.newFolder();
        for (char[] password : new char[][]{null, "wrong-password".toCharArray()}) {
            assertThrows(IOException.class, () -> selected(archive, "data.bin", output, password));
            assertThrows(IOException.class, () -> bulk(archive, root, password, null));
            assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
            assertEquals(0, root.list().length);
        }
    }

    @Test public void payloadIntegrityFailureAfterEncodedHeaderKeepsRollback() throws Exception {
        File archive = fixture(true, false, false, true);
        File output = sentinel(temporary.newFolder(), "page"), root = temporary.newFolder();
        File member = sentinel(root, "data.bin");
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> selected(archive, "data.bin", output, Round4Fixtures.PASSWORD));
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> bulk(archive, root, Round4Fixtures.PASSWORD, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(member.toPath()));
        assertArrayEquals(new String[]{"page"}, output.getParentFile().list());
        assertArrayEquals(new String[]{"data.bin"}, root.list());
    }

    @Test public void sourceChangeAfterProbeCannotBeAdoptedBySelectedExtraction() throws Exception {
        File archive = fixture(false, false, false, false);
        File original = sentinel(temporary.newFolder(), "page");
        File output = new File(original.getPath()) {
            private boolean changed;
            @Override public File getParentFile() {
                if (!changed) {
                    changed = true;
                    assertTrue(archive.setLastModified(archive.lastModified() + 5000));
                }
                return super.getParentFile();
            }
        };
        assertThrows(IOException.class, () -> selected(archive, "data.bin", output, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(original.toPath()));
        assertArrayEquals(new String[]{"page"}, original.getParentFile().list());
    }

    @Test public void sourceChangeDuringBulkPreflightKeepsOutputUntouched() throws Exception {
        File archive = fixture(false, false, false, false), directory = temporary.newFolder();
        File output = sentinel(directory, "data.bin");
        File root = new File(directory.getPath()) {
            @Override public File getCanonicalFile() throws IOException {
                assertTrue(archive.setLastModified(archive.lastModified() + 5000));
                return super.getCanonicalFile();
            }
        };
        assertThrows(IOException.class, () -> bulk(archive, root, null, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
        assertArrayEquals(new String[]{"data.bin"}, directory.list());
    }

    @Test public void cancellationAfterProbeReturnsFalseBeforeOutput() throws Exception {
        File archive = fixture(false, false, false, false), directory = temporary.newFolder();
        File output = sentinel(directory, "data.bin");
        FileOperationProgress progress = new FileOperationProgress("7z", null);
        File root = new File(directory.getPath()) {
            @Override public File getCanonicalFile() throws IOException {
                progress.cancel();
                return super.getCanonicalFile();
            }
        };
        assertEquals(Boolean.FALSE, bulk(archive, root, null, progress));
        assertTrue(progress.isCancelled());
        assertFalse(Thread.currentThread().isInterrupted());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
        assertArrayEquals(new String[]{"data.bin"}, directory.list());
    }

    private File fixture(boolean encrypted, boolean twoFiles, boolean headerOnly, boolean badCrc)
            throws Exception {
        return Round4Fixtures.sevenZ(temporary.newFolder(), CONTENT, 4, encrypted,
                true, badCrc, twoFiles, headerOnly);
    }

    private File split(File archive) throws IOException {
        byte[] bytes = Files.readAllBytes(archive.toPath());
        File directory = temporary.newFolder();
        Round4Fixtures.write(directory, "fixture.7z.001", Arrays.copyOf(bytes, 35));
        return Round4Fixtures.write(directory, "fixture.7z.002", Arrays.copyOfRange(bytes, 35, bytes.length));
    }

    private static File sentinel(File directory, String name) throws IOException {
        File file = new File(directory, name);
        Files.write(file.toPath(), ORIGINAL);
        return file;
    }

    private static Object selected(File archive, String entry, File output, char[] password) throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod("tryExtractSevenZBcj2SingleEntry",
                File.class, String.class, File.class, char[].class);
        return invoke(method, archive, entry, output, password);
    }

    private static Object bulk(File archive, File root, char[] password, FileOperationProgress progress)
            throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod("tryExtractSevenZBcj2IntoDirectory",
                File.class, File.class, char[].class, FileOperationProgress.class,
                ArchiveExtractionProgressTracker.class);
        return invoke(method, archive, root, password, progress, null);
    }

    private static Object invoke(Method method, Object... arguments) throws Exception {
        method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }

    private static final class CountingFile extends File {
        int adoptions;
        CountingFile(File file) { super(file.getPath()); }
        @Override public File getCanonicalFile() throws IOException {
            // Single-file source adoption canonicalizes once; later stamp checks use the path.
            adoptions++;
            return super.getCanonicalFile();
        }
    }
}
