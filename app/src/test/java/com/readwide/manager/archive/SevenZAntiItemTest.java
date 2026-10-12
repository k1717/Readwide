package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic differential containers: expose contents, never apply backup deletions. */
public class SevenZAntiItemTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] CONTENT = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final byte[] ORIGINAL = {42};
    private static final String[] VISIBLE = {"1.bin", "keep/", "empty.bin", "2.bin"};

    @Test public void fixtureMarksOnlyExpectedEmptyStreams() throws Exception {
        File archive = mixed(false, false, false, false);
        try (SevenZFile reader = (SevenZFile) call("openSevenZFile", archive, null)) {
            int markers = 0, files = 0;
            for (SevenZArchiveEntry entry : reader.getEntries()) {
                if (entry.isAntiItem()) { markers++; assertFalse(entry.hasStream()); }
                else files++;
            }
            assertEquals(4, markers);
            assertEquals(4, files);
        }
    }

    @Test public void firstPartyListingKeepsRealEmptyFilesAndDirectories() throws Exception {
        List<ArchiveSupport.EntryInfo> entries = SevenZBcj2ArchiveReader.listEntries(
                mixed(false, false, false, false), null);
        assertPaths(entries);
        assertTrue(entries.get(1).directory);
        assertFalse(entries.get(2).directory);
    }

    @Test public void commonsAndPublicListingExcludeMarkersBeforeNameDeduplication() throws Exception {
        File archive = mixed(false, false, false, false);
        assertPaths(commonsList(archive));
        assertPaths(ArchiveSupport.listEntries(archive, null));
    }

    @Test public void selectedReadersChooseRealFileAfterSameNameMarker() throws Exception {
        File archive = mixed(false, false, false, false);
        for (boolean supplemental : new boolean[]{false, true}) {
            for (int page = 1; page <= 2; page++) {
                File output = temporary.newFile();
                boolean success = supplemental
                        ? SevenZBcj2ArchiveReader.extractSingleEntry(archive, page + ".bin", output, null)
                        : (Boolean) call("extractSingleSevenZEntry", archive, page + ".bin", output, null);
                assertTrue(success);
                assertArrayEquals(Arrays.copyOfRange(CONTENT, (page - 1) * 4, page * 4),
                        Files.readAllBytes(output.toPath()));
            }
        }
    }

    @Test public void firstPartyBulkSkipsDeletionAndTraversalMarkers() throws Exception {
        File archive = mixed(false, false, false, false), root = outputWithSentinels();
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, root, null, null, null));
        assertOutput(root);
    }

    @Test public void commonsBulkSkipsDeletionAndTraversalMarkers() throws Exception {
        File archive = mixed(false, false, false, false), root = outputWithSentinels();
        assertEquals(Boolean.TRUE, call("extractSevenZIntoDirectory", archive, root, null, null, null));
        assertOutput(root);
    }

    @Test public void publicBulkExtractsOnlyReadableContents() throws Exception {
        File archive = mixed(false, false, false, false);
        File root = new File(temporary.newFolder(), "output");
        assertTrue(ArchiveSupport.extractArchive(archive, root, false, null));
        assertFalse(new File(root, "gone").exists());
        assertTrue(new File(root, "keep").isDirectory());
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 0, 4),
                Files.readAllBytes(new File(root, "1.bin").toPath()));
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 4, 8),
                Files.readAllBytes(new File(root, "2.bin").toPath()));
    }

    @Test public void antiOnlyArchiveIsAnEmptyReadableViewAndNeverDeletes() throws Exception {
        File archive = antiOnly(), root = temporary.newFolder();
        File file = sentinel(root, "file0");
        File directory = new File(root, "file1");
        assertTrue(directory.mkdir());
        File child = sentinel(directory, "keep");
        assertTrue(SevenZBcj2ArchiveReader.listEntries(archive, null).isEmpty());
        assertTrue(commonsList(archive).isEmpty());
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, root, null, null, null));
        assertEquals(Boolean.TRUE, call("extractSevenZIntoDirectory", archive, root, null, null, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(file.toPath()));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(child.toPath()));
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader
                .openSpecialForwardReader(archive, null, temporary.newFolder())) {
            assertNotNull(reader);
            assertNull(reader.nextEntry());
        }
    }

    @Test public void selectedMarkerIsNotAnEmptyReplacementFile() throws Exception {
        File archive = antiOnly(), output = sentinel(temporary.newFolder(), "target");
        assertFalse(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "file0", output, null));
        assertEquals(Boolean.FALSE, call("extractSingleSevenZEntry", archive, "file0", output, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    @Test public void dedicatedForwardFactoriesKeepSolidOffsetsAndCleanSpools() throws Exception {
        File archive = mixed(false, false, false, false);
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        for (boolean nativeRoute : new boolean[]{false, true}) {
            File spool = temporary.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = nativeRoute
                    ? SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(archive, null, spool)
                    : SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool)) {
                assertForward(reader);
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void commonsForwardReaderSkipsMarkersWithoutSkippingRealStreams() throws Exception {
        File archive = mixed(false, false, false, false);
        Object prepared = call("prepareArchiveForRead", archive);
        SevenZFile sevenZ = null;
        boolean transferred = false;
        try {
            sevenZ = (SevenZFile) call("openSevenZFile", archive, null);
            Class<?> type = Class.forName(ArchiveSupport.class.getName() + "$SevenZForwardReader");
            Constructor<?> constructor = type.getDeclaredConstructor(prepared.getClass(), SevenZFile.class);
            constructor.setAccessible(true);
            ArchiveSupport.ForwardArchiveReader reader = (ArchiveSupport.ForwardArchiveReader)
                    constructor.newInstance(prepared, sevenZ);
            transferred = true;
            try (ArchiveSupport.ForwardArchiveReader owned = reader) { assertForward(owned); }
        } finally {
            if (!transferred) {
                try { if (sevenZ != null) sevenZ.close(); }
                finally { ((java.io.Closeable) prepared).close(); }
            }
        }
    }

    @Test public void aesPayloadAndEncodedHeaderRetainMarkerFiltering() throws Exception {
        for (boolean encoded : new boolean[]{false, true}) {
            File archive = mixed(true, encoded, false, false), output = temporary.newFile();
            assertPaths(SevenZBcj2ArchiveReader.listEntries(archive, Round4Fixtures.PASSWORD));
            assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "2.bin", output, Round4Fixtures.PASSWORD));
            assertArrayEquals(Arrays.copyOfRange(CONTENT, 4, 8), Files.readAllBytes(output.toPath()));
            try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(
                    archive, Round4Fixtures.PASSWORD, temporary.newFolder())) { assertForward(reader); }
        }
    }

    @Test public void numberedSplitArchiveRetainsMarkerFiltering() throws Exception {
        byte[] bytes = Files.readAllBytes(mixed(false, true, false, false).toPath());
        File directory = temporary.newFolder();
        File archive = Round4Fixtures.write(directory, "fixture.7z.001", Arrays.copyOfRange(bytes, 0, bytes.length / 2));
        Round4Fixtures.write(directory, "fixture.7z.002", Arrays.copyOfRange(bytes, bytes.length / 2, bytes.length));
        assertPaths(SevenZBcj2ArchiveReader.listEntries(archive, null));
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(
                archive, null, temporary.newFolder())) { assertForward(reader); }
    }

    @Test public void antiBitmapBeforeEmptyStreamStillUsesEmptyStreamIndexes() throws Exception {
        assertPaths(SevenZBcj2ArchiveReader.listEntries(mixed(false, false, true, false), null));
    }

    @Test public void filteringDoesNotBypassFolderIntegrityOrFallbackBoundary() throws Exception {
        File archive = mixed(false, false, false, true);
        File output = sentinel(temporary.newFolder(), "page");
        assertThrows(IOException.class,
                () -> call("tryExtractSevenZBcj2SingleEntry", archive, "2.bin", output, null));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    private File mixed(boolean aes, boolean encoded, boolean antiFirst, boolean badCrc) throws Exception {
        Round4Fixtures.Graph data = Round4Fixtures.graph(CONTENT, 0, aes);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(4); Round4Fixtures.streams(header, data, 0, true, badCrc);
        header.write(5); Round4Fixtures.number(header, 8);
        // Empty-stream indexes: anti 1.bin, keep/, anti gone/, empty.bin,
        // anti ../untouched, anti 2.bin. Two real solid members interleave them.
        if (antiFirst) property(header, 16, new byte[]{(byte) 0xac});
        property(header, 14, new byte[]{(byte) 0xbb});
        property(header, 15, new byte[]{(byte) 0x9c});
        if (!antiFirst) property(header, 16, new byte[]{(byte) 0xac});
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        names.write(0);
        for (String name : new String[]{"1.bin", "1.bin", "keep/", "gone/", "empty.bin", "2.bin", "../untouched", "2.bin"}) {
            names.write((name + "\0").getBytes(StandardCharsets.UTF_16LE));
        }
        property(header, 17, names.toByteArray());
        header.write(0); header.write(0);
        byte[] next = header.toByteArray();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(data.packed);
        if (encoded) {
            Round4Fixtures.Graph metadata = Round4Fixtures.graph(next, 0, aes);
            ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
            descriptor.write(23); Round4Fixtures.streams(descriptor, metadata, packed.size(), false, false);
            packed.write(metadata.packed); next = descriptor.toByteArray();
        }
        return Round5Fixtures.wrap(temporary.newFolder(), packed.toByteArray(), next);
    }

    private File antiOnly() throws Exception {
        ByteArrayOutputStream props = new ByteArrayOutputStream();
        property(props, 14, new byte[]{(byte) 0xc0});
        property(props, 15, new byte[]{(byte) 0x80}); // File, then directory.
        property(props, 16, new byte[]{(byte) 0xc0});
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        names.write(0); names.write("file0\0file1\0".getBytes(StandardCharsets.UTF_16LE));
        property(props, 17, names.toByteArray());
        return Round5Fixtures.emptySevenZ(temporary.newFolder(), 2, props.toByteArray());
    }

    private static void property(ByteArrayOutputStream out, int id, byte[] bytes) throws IOException {
        out.write(id); Round4Fixtures.number(out, bytes.length); out.write(bytes);
    }
    private File outputWithSentinels() throws IOException {
        File parent = temporary.newFolder(), root = new File(parent, "output");
        assertTrue(root.mkdir());
        File gone = new File(root, "gone"); assertTrue(gone.mkdir());
        sentinel(gone, "keep"); sentinel(parent, "untouched");
        return root;
    }
    private void assertOutput(File root) throws IOException {
        assertArrayEquals(ORIGINAL, Files.readAllBytes(new File(root, "gone/keep").toPath()));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(new File(root.getParentFile(), "untouched").toPath()));
        assertTrue(new File(root, "keep").isDirectory());
        assertTrue(new File(root, "empty.bin").isFile());
        assertEquals(0, new File(root, "empty.bin").length());
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 0, 4), Files.readAllBytes(new File(root, "1.bin").toPath()));
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 4, 8), Files.readAllBytes(new File(root, "2.bin").toPath()));
    }
    private static File sentinel(File directory, String name) throws IOException {
        File file = new File(directory, name); Files.write(file.toPath(), ORIGINAL); return file;
    }
    private static void assertPaths(List<ArchiveSupport.EntryInfo> entries) {
        List<String> paths = new ArrayList<>();
        for (ArchiveSupport.EntryInfo entry : entries) paths.add(entry.path);
        assertEquals(Arrays.asList(VISIBLE), paths);
    }
    private static void assertForward(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        assertNotNull(reader);
        for (String path : VISIBLE) {
            ArchiveSupport.ForwardEntry entry = reader.nextEntry();
            assertNotNull(entry); assertEquals(path, entry.path);
            if (path.equals("keep/")) { assertTrue(entry.directory); continue; }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[3];
            for (int n; (n = reader.read(buffer)) != -1;) content.write(buffer, 0, n);
            byte[] expected = path.equals("1.bin") ? Arrays.copyOfRange(CONTENT, 0, 4)
                    : path.equals("2.bin") ? Arrays.copyOfRange(CONTENT, 4, 8) : new byte[0];
            assertArrayEquals(expected, content.toByteArray());
        }
        assertNull(reader.nextEntry());
    }
    @SuppressWarnings("unchecked")
    private List<ArchiveSupport.EntryInfo> commonsList(File archive) throws Exception {
        return (List<ArchiveSupport.EntryInfo>) call("listSevenZEntries", archive, null);
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
