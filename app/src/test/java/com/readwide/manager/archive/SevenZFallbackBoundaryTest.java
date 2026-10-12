package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real synthetic containers at dispatch boundaries; no native backend or decoder mocks. */
public class SevenZFallbackBoundaryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] CONTENT = {1, 2, 3, 4};
    private static final byte[] ORIGINAL = {42};

    @Test public void overlongCoderIsTerminalAndRestoresSelectedOutput() throws Exception {
        File archive = malformedPayload(1), output = sentinel(temporary.newFolder(), "page");
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        IOException failure = assertThrows(IOException.class, () -> selected(archive, output));
        assertFalse(failure instanceof SevenZBcj2ArchiveReader.IntegrityException);
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
        assertArrayEquals(new String[]{"page"}, output.getParentFile().list());
    }

    @Test public void overlongCoderIsTerminalAndRestoresBulkFolder() throws Exception {
        File archive = malformedPayload(1), root = temporary.newFolder();
        File output = sentinel(root, "data.bin");
        IOException failure = assertThrows(IOException.class, () -> bulk(archive, root));
        assertFalse(failure instanceof SevenZBcj2ArchiveReader.IntegrityException);
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
        assertArrayEquals(new String[]{"data.bin"}, root.list());
    }

    @Test public void truncatedCoderCannotRequestAnotherBackend() throws Exception {
        File archive = malformedPayload(-1), output = sentinel(temporary.newFolder(), "page");
        assertThrows(IOException.class, () -> selected(archive, output));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    @Test public void occupiedSelectedDirectoryIsTerminalAndUntouched() throws Exception {
        File archive = valid(4, false, false), directory = temporary.newFolder();
        File keep = sentinel(directory, "keep");
        assertThrows(IOException.class, () -> selected(archive, directory));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(keep.toPath()));
    }

    @Test public void bulkPreflightFailureCannotRequestFallback() throws Exception {
        File archive = valid(4, false, false), root = temporary.newFolder();
        File occupied = new File(root, "data.bin");
        assertTrue(occupied.mkdir());
        File keep = sentinel(occupied, "keep");
        assertThrows(IOException.class, () -> bulk(archive, root));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(keep.toPath()));
    }

    @Test public void sharedWriteBudgetFailureRemainsTerminal() throws Exception {
        File archive = valid(4, false, false), root = temporary.newFolder();
        File output = sentinel(root, "data.bin");
        try (ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(1)) {
            assertThrows(IOException.class, () -> bulk(archive, root));
        }
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
        assertArrayEquals(new String[]{"data.bin"}, root.list());
    }

    @Test public void uncheckedOutputFailureIsReportedAsIoWithOriginalCause() throws Exception {
        File archive = valid(4, false, false), original = sentinel(temporary.newFolder(), "page");
        IllegalStateException injected = new IllegalStateException("output failure");
        File failing = new File(original.getPath()) {
            @Override public File getParentFile() { throw injected; }
        };
        IOException failure = assertThrows(IOException.class, () -> selected(archive, failing));
        assertSame(injected, failure.getCause());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(original.toPath()));
    }

    @Test public void ordinaryCopyStillDeclinesAllSupplementalRoutes() throws Exception {
        File archive = valid(0, false, false), root = temporary.newFolder();
        File output = sentinel(root, "page");
        assertNull(selected(archive, output));
        assertNull(bulk(archive, root));
        assertNull(call("tryListSevenZBcj2Entries", archive, null));
        assertNull(SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, root));
        assertNull(SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(archive, null, root));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
        assertArrayEquals(new String[]{"page"}, root.list());
    }

    @Test public void validSpecialArchiveStillExtractsThroughBothHelpers() throws Exception {
        File archive = valid(4, false, false), root = temporary.newFolder(), output = temporary.newFile();
        assertEquals(Boolean.TRUE, selected(archive, output));
        assertEquals(Boolean.TRUE, bulk(archive, root));
        assertArrayEquals(CONTENT, Files.readAllBytes(output.toPath()));
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(root, "data.bin").toPath()));
    }

    @Test public void corruptHeaderCannotBecomeNullSupplementalListing() throws Exception {
        File archive = valid(4, false, false);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[8] ^= 1; // Start-header CRC, before decoder selection.
        Files.write(archive.toPath(), bytes);
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> call("tryListSevenZBcj2Entries", archive, null));
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> selected(archive, temporary.newFile()));
    }

    @Test public void encryptedHeaderRequiresPasswordBeforeFallback() throws Exception {
        for (boolean split : new boolean[]{false, true}) {
            File archive = valid(4, true, true);
            if (split) archive = split(archive);
            final File input = archive;
            File spool = temporary.newFolder();
            assertThrows(ArchiveSupport.PasswordRequiredException.class,
                    () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(input, null));
            assertThrows(ArchiveSupport.PasswordRequiredException.class,
                    () -> call("tryListSevenZBcj2Entries", input, null));
            assertThrows(ArchiveSupport.PasswordRequiredException.class,
                    () -> SevenZBcj2ArchiveReader.openSpecialForwardReader(input, null, spool));
            assertThrows(ArchiveSupport.PasswordRequiredException.class,
                    () -> SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(input, null, spool));
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void sourceChangesAreTerminalForSuccessfulAndUnhandledHeaders() throws Exception {
        for (boolean supported : new boolean[]{false, true}) {
            File archive = supported ? valid(4, false, false) : unhandledHeader();
            IOException failure = assertThrows(IOException.class,
                    () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(changingStamp(archive), null));
            assertTrue(failure.getMessage().contains("source changed"));
            assertThrows(IOException.class,
                    () -> call("tryListSevenZBcj2Entries", changingStamp(archive), null));
            File spool = temporary.newFolder();
            assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.openSpecialForwardReader(
                    changingStamp(archive), null, spool));
            assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(
                    changingStamp(archive), null, spool));
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void stableUnhandledHeaderKeepsBroaderBackendEligibility() throws Exception {
        File archive = unhandledHeader(), spool = temporary.newFolder();
        assertFalse(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        assertNull(call("tryListSevenZBcj2Entries", archive, null));
        assertNull(SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool));
        assertNull(SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(archive, null, spool));
        assertEquals(0, spool.list().length);
    }

    @Test public void sharedForwardFactoryReadsSingleAndSplitEncryptedSources() throws Exception {
        for (boolean split : new boolean[]{false, true}) {
            File archive = valid(4, true, true);
            if (split) archive = split(archive);
            for (boolean nativeRoute : new boolean[]{false, true}) {
                File spool = temporary.newFolder();
                try (ArchiveSupport.ForwardArchiveReader reader = nativeRoute
                        ? SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(archive, Round4Fixtures.PASSWORD, spool)
                        : SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, Round4Fixtures.PASSWORD, spool)) {
                    assertNotNull(reader);
                    assertEquals("data.bin", reader.nextEntry().path);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[3];
                    for (int count; (count = reader.read(buffer)) != -1;) output.write(buffer, 0, count);
                    assertArrayEquals(CONTENT, output.toByteArray());
                    assertNull(reader.nextEntry());
                }
                assertEquals(0, spool.list().length);
            }
        }
    }

    private Object selected(File archive, File output) throws Exception {
        return call("tryExtractSevenZBcj2SingleEntry", archive, "data.bin", output, null);
    }
    private Object bulk(File archive, File root) throws Exception {
        return call("tryExtractSevenZBcj2IntoDirectory", archive, root, null, null, null);
    }
    private Object call(String name, Object... arguments) throws Exception {
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
    private File sentinel(File directory, String name) throws IOException {
        File output = new File(directory, name);
        Files.write(output.toPath(), ORIGINAL);
        return output;
    }
    private File valid(int width, boolean aes, boolean encodedHeader) throws Exception {
        return Round4Fixtures.sevenZ(temporary.newFolder(), CONTENT, width, aes, encodedHeader, false, false);
    }
    private File malformedPayload(int delta) throws Exception {
        Round4Fixtures.Graph graph = Round4Fixtures.graph(CONTENT, 4, false);
        graph.packed = Arrays.copyOf(graph.packed, graph.packed.length + delta);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(4); Round4Fixtures.streams(header, graph, 0, false, false);
        header.write(5); Round4Fixtures.number(header, 1);
        byte[] names = "data.bin\0".getBytes(StandardCharsets.UTF_16LE);
        header.write(17); Round4Fixtures.number(header, names.length + 1);
        header.write(0); header.write(names); header.write(0); header.write(0);
        // Header and packed CRCs are valid; only the coder's decoded size is inconsistent.
        return Round5Fixtures.wrap(temporary.newFolder(), graph.packed, header.toByteArray());
    }
    private File unhandledHeader() throws Exception {
        File archive = valid(0, false, false);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        bytes[6] = 1; // Unsupported container major; independent of start-header CRC.
        Files.write(archive.toPath(), bytes);
        return archive;
    }
    private File changingStamp(File archive) {
        return new File(archive.getPath()) {
            private int samples;
            @Override public long lastModified() {
                // Three initial samples establish/open the Source. Its final probe check
                // observes the change; canonical volume handles use an ordinary File.
                return super.lastModified() + (++samples > 3 ? 5000 : 0);
            }
        };
    }
    private File split(File archive) throws Exception {
        byte[] bytes = Files.readAllBytes(archive.toPath());
        File directory = temporary.newFolder();
        Round4Fixtures.write(directory, "fixture.7z.001", Arrays.copyOf(bytes, 35));
        return Round4Fixtures.write(directory, "fixture.7z.002", Arrays.copyOfRange(bytes, 35, bytes.length));
    }
}
