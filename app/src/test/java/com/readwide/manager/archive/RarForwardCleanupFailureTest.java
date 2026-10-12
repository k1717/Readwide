package com.readwide.manager.archive;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Existing decoded spools plus injected stream/filesystem failures; no JNI substitute is exercised. */
@RunWith(Parameterized.class)
public final class RarForwardCleanupFailureTest {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> variants() {
        return Arrays.asList(new Object[][]{{"RAR3 checked"},{"RAR3 logical"},{"RAR3 PPMd"},{"RAR5"}});
    }
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final String variant;
    public RarForwardCleanupFailureTest(String variant) { this.variant = variant; }
    @Test public void checkedCloseStillDeletesSpool() throws Exception { check(new IOException("close"), null, false); }
    @Test public void runtimeCloseStillDeletesSpool() throws Exception { check(new IllegalStateException("close"), null, false); }
    @Test public void errorCloseStillDeletesSpool() throws Exception { check(new AssertionError("close"), null, false); }
    @Test public void readFailureSurvivesErrorDuringCleanup() throws Exception { check(new AssertionError("close"), new IOException("read"), false); }
    @Test public void repeatedSameFailureDoesNotSelfSuppress() throws Exception {
        IOException shared = new IOException("same read and close failure"); check(shared, shared, false);
    }
    @Test public void deletionFailureDoesNotMaskCloseAndRemainsRetryable() throws Exception { check(new IOException("close"), null, true); }

    private void check(Throwable closeFailure, IOException readFailure, boolean deletionFailure) throws Exception {
        File directory = temporary.newFolder("spools");
        ArchiveSupport.ForwardArchiveReader reader = open(directory);
        assertNotNull(reader);
        Field inputField = reader.getClass().getDeclaredField("input"); inputField.setAccessible(true);
        Field spoolField = reader.getClass().getDeclaredField("spool"); spoolField.setAccessible(true);
        File spool = null;
        try {
            assertNotNull(reader.nextEntry()); assertEquals(1, reader.read(new byte[1]));
            spool = (File) spoolField.get(reader);
            InputStream original = (InputStream) inputField.get(reader);
            final int[] closes = {0};
            inputField.set(reader, new FilterInputStream(original) {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (readFailure != null) throw readFailure;
                    return super.read(bytes, offset, length);
                }
                @Override public void close() throws IOException {
                    closes[0]++; super.close();
                    if (closes[0] > 1) return;
                    if (closeFailure instanceof IOException) throw (IOException) closeFailure;
                    if (closeFailure instanceof RuntimeException) throw (RuntimeException) closeFailure;
                    throw (Error) closeFailure;
                }
            });
            if (deletionFailure) spoolField.set(reader, new File(spool.getPath()) {
                @Override public boolean delete() { throw new SecurityException("injected deletion failure"); }
            });
            Throwable caught = null;
            try { if (readFailure == null) reader.close(); else reader.read(new byte[1]); }
            catch (Throwable failure) { caught = failure; }
            assertSame("Cleanup must retain the initiating failure", readFailure == null ? closeFailure : readFailure, caught);
            assertEquals(1, closes[0]);
            if (deletionFailure) {
                assertEquals(1, caught.getSuppressed().length);
                assertTrue(spool.exists());
                spoolField.set(reader, spool); reader.close();
            }
            assertArrayEquals("No decoded spool may leak", new String[0], directory.list());
            reader.close(); reader.close();
            assertEquals(2, closes[0]);
            assertNull(inputField.get(reader));
            assertThrows(IOException.class, () -> reader.read(new byte[1]));
            assertThrows(IOException.class, reader::nextEntry);
        } finally {
            if (spool != null) spoolField.set(reader, spool);
            reader.close();
        }
    }
    @SuppressWarnings("unchecked")
    private ArchiveSupport.ForwardArchiveReader open(File directory) throws Exception {
        if (variant.equals("RAR3 PPMd")) {
            Rar3PpmdEngineFixtureProbeTest fixture = new Rar3PpmdEngineFixtureProbeTest();
            fixture.temp = temporary;
            Method create = Rar3PpmdEngineFixtureProbeTest.class.getDeclaredMethod("writeEmbeddedFixture");
            create.setAccessible(true);
            return Rar3PpmdSolidArchiveExtractor.openForwardReader((File) create.invoke(fixture), directory, false);
        }
        if (variant.equals("RAR5")) {
            Field hex = Rar5CompressedDecoderTest.class.getDeclaredField("SOLID_CBR_HEX"); hex.setAccessible(true);
            String value = (String) hex.get(null); byte[] bytes = new byte[value.length() / 2];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            File archive = temporary.newFile("solid.rar"); java.nio.file.Files.write(archive.toPath(), bytes);
            return Rar5CompressedArchiveExtractor.openForwardReader(archive, null, directory, false);
        }
        if (variant.equals("RAR3 logical")) {
            Rar3LogicalSolidArchiveExtractorTest fixture = new Rar3LogicalSolidArchiveExtractorTest();
            fixture.temporary = temporary;
            Method create = Rar3LogicalSolidArchiveExtractorTest.class.getDeclaredMethod("fixture", boolean.class, boolean.class, boolean.class);
            create.setAccessible(true);
            List<RarArchiveReader.RarEntry> entries = (List<RarArchiveReader.RarEntry>) create.invoke(fixture, false, true, false);
            return Rar3LogicalSolidArchiveExtractor.openForwardReader(entries, null, directory);
        }
        Rar3FirstPartyArchiveExtractorTest fixture = new Rar3FirstPartyArchiveExtractorTest();
        fixture.tempFolder = temporary;
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        Method create = Rar3FirstPartyArchiveExtractorTest.class.getDeclaredMethod("addCheckedRun", List.class, String.class);
        create.setAccessible(true); create.invoke(fixture, entries, "cleanup");
        return Rar3CheckedForwardReader.open(entries, directory, false);
    }
}
