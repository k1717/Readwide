package com.readwide.manager.archive;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

/** Source regressions; fixture payloads are created in-process, with no external tar tool. */
public class TarEntryIndexTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Before public void before() { TarEntryIndex.clear(); }
    @After public void after() { TarEntryIndex.clear(); }

    @Test public void listingAndReverseReadsShareMetadataIndex() throws Exception {
        File archive = fixture("pages.tar", false, false, "chapter/2.jpg", "two");
        ArchiveSupport.listEntries(archive, null);
        TarEntryIndex.Index first = TarEntryIndex.get(archive);
        File out = temp.newFile("page");
        assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive, "chapter/2.jpg", out, null).success);
        assertEquals("two", Files.readString(out.toPath()));
        assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive, "chapter/1.jpg", out, null).success);
        assertEquals("one", Files.readString(out.toPath()));
        assertSame(first, TarEntryIndex.get(archive));
        assertFalse(ArchiveSupport.isForwardImageReadableType(archive));
        assertTrue(ArchiveSupport.isForwardImageReadableType(ArchiveSupport.Type.TAR_GZ));
    }

    @Test public void paxLongUnicodeNameKeepsCorrectPayloadOffset() throws Exception {
        String path = "chapter/" + "긴이름".repeat(40) + "/page.jpg";
        File archive = fixture("pax.tar", false, false, path, "pax-data");
        File out = temp.newFile("pax-out");
        assertEquals(Boolean.TRUE, TarEntryIndex.extract(archive, path, out));
        assertEquals("pax-data", Files.readString(out.toPath()));
    }

    @Test public void linksBeforeRegularMembersDoNotAbortTarOrGzipExtraction() throws Exception {
        for (boolean compressed : new boolean[]{false, true}) {
            File archive = fixture(compressed ? "links.tar.gz" : "links.tar", compressed, true,
                    "chapter/2.jpg", "two");
            File out = new File(temp.getRoot(), "links-out-" + compressed);
            assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive, "chapter/2.jpg", out, null).success);
            assertEquals("two", Files.readString(out.toPath()));
            assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive, "link.jpg", out, null).success);
        }
    }

    @Test public void duplicateNormalizedPathsUseFirstReadableMember() throws Exception {
        File archive = fixture("duplicate.tar", false, false, "./chapter/1.jpg", "second");
        File out = temp.newFile("duplicate-out");
        assertEquals(Boolean.TRUE, TarEntryIndex.extract(archive, "chapter/1.jpg", out));
        assertEquals("one", Files.readString(out.toPath()));
        assertEquals(3L, TarEntryIndex.get(archive).listing.get(0).size);
    }

    @Test public void changedMetadataAndExplicitReleaseInvalidateIndex() throws Exception {
        File archive = fixture("changed.tar", false, false, "chapter/2.jpg", "two");
        TarEntryIndex.Index first = TarEntryIndex.get(archive);
        assertTrue(archive.setLastModified(archive.lastModified() + 5000));
        TarEntryIndex.Index second = TarEntryIndex.get(archive);
        assertNotSame(first, second);
        ArchiveSupport.releaseViewerArchiveIndex(archive);
        assertNotSame(second, TarEntryIndex.get(archive));
    }

    @Test public void fourthArchiveEvictsLeastRecentlyUsedIndexWithoutRetainingHandles() throws Exception {
        File firstFile = fixture("first.tar", false, false, "chapter/2.jpg", "two");
        TarEntryIndex.Index first = TarEntryIndex.get(firstFile);
        for (int i = 0; i < 3; i++) TarEntryIndex.get(fixture("other-" + i + ".tar", false, false, "x", "x"));
        assertNotSame(first, TarEntryIndex.get(firstFile));
        assertTrue(firstFile.delete()); // No cached TarFile/RandomAccessFile handle, including on Windows.
    }

    @Test public void staleIndexAndBudgetFailuresPreserveExistingOutput() throws Exception {
        File archive = fixture("truncate.tar", false, false, "chapter/2.jpg", "two");
        TarEntryIndex.Index index = TarEntryIndex.get(archive);
        File out = temp.newFile("existing");
        Files.writeString(out.toPath(), "existing");
        try (ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(1)) {
            try { TarEntryIndex.extract(index, archive, "chapter/2.jpg", out); fail("Expected budget failure"); }
            catch (TarEntryIndex.ExtractionException expected) { }
        }
        assertEquals("existing", Files.readString(out.toPath()));
        try (RandomAccessFile file = new RandomAccessFile(archive, "rw")) { file.setLength(1025); }
        try { TarEntryIndex.extract(index, archive, "chapter/2.jpg", out); fail("Expected changed archive failure"); }
        catch (TarEntryIndex.ExtractionException expected) { }
        assertEquals("existing", Files.readString(out.toPath()));
    }

    @Test public void cancellationDoesNotReturnSuccessOrReplaceOutput() throws Exception {
        File archive = fixture("cancel.tar", false, false, "chapter/2.jpg", "two");
        File out = temp.newFile("cancel-out");
        Files.writeString(out.toPath(), "existing");
        Thread.currentThread().interrupt();
        try {
            try { TarEntryIndex.extract(archive, "chapter/2.jpg", out); fail("Expected interruption"); }
            catch (IOException expected) { }
        } finally { Thread.interrupted(); }
        assertEquals("existing", Files.readString(out.toPath()));
    }

    @Test public void bulkExtractionSkipsLinksAndSpecialMembers() throws Exception {
        for (boolean compressed : new boolean[]{false, true}) {
            File archive = fixture(compressed ? "bulk-links.tar.gz" : "bulk-links.tar", compressed,
                    true, "chapter/2.jpg", "two");
            File output = new File(temp.getRoot(), "bulk-" + compressed);
            assertTrue(ArchiveSupport.extractArchive(archive, output, false, null));
            assertEquals("one", Files.readString(new File(output, "chapter/1.jpg").toPath()));
            assertEquals("two", Files.readString(new File(output, "chapter/2.jpg").toPath()));
            assertFalse(new File(output, "link.jpg").exists());
            assertFalse(new File(output, "pipe").exists());
        }
    }

    @Test public void corruptHeaderIsTerminalForIndexedAndCompressedListing() throws Exception {
        for (boolean compressed : new boolean[]{false, true}) {
            File archive = damagedFixture(compressed, false);
            try { ArchiveSupport.listEntries(archive, null); fail("Expected header failure"); }
            catch (ArchiveSupport.TarIntegrityException expected) { }
        }
    }

    @Test public void corruptHeaderIsRejectedBeforeForwardEntryPublication() throws Exception {
        for (boolean compressed : new boolean[]{false, true}) {
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(
                    damagedFixture(compressed, false), null)) {
                assertNotNull(reader);
                try { reader.nextEntry(); fail("Expected header failure"); }
                catch (ArchiveSupport.TarIntegrityException expected) { }
            }
        }
    }

    @Test public void truncatedStreamingPayloadPreservesExistingTarget() throws Exception {
        File archive = damagedFixture(true, true);
        File output = temp.newFile();
        Files.writeString(output.toPath(), "existing");
        try { extractStreaming(archive, output); fail("Expected truncated member"); }
        catch (TarEntryIndex.ExtractionException expected) { }
        assertEquals("existing", Files.readString(output.toPath()));
    }

    @Test public void streamingBudgetFailurePreservesExistingTarget() throws Exception {
        File archive = fixture("stream-budget.tar.gz", true, false, "chapter/2.jpg", "two");
        File output = temp.newFile();
        Files.writeString(output.toPath(), "existing");
        try (ArchiveExtractionByteBudget.Scope ignored = ArchiveExtractionByteBudget.begin(1)) {
            try { extractStreaming(archive, output); fail("Expected budget failure"); }
            catch (TarEntryIndex.ExtractionException expected) { }
        }
        assertEquals("existing", Files.readString(output.toPath()));
    }

    // Exercise the writer, not the public preview wrapper's disposable-output deletion policy.
    private static void extractStreaming(File archive, File output) throws Exception {
        java.lang.reflect.Method method = ArchiveSupport.class.getDeclaredMethod("extractSingleTarEntry",
                File.class, String.class, File.class, ArchiveSupport.Type.class);
        method.setAccessible(true);
        try { assertEquals(Boolean.TRUE, method.invoke(null, archive, "chapter/1.jpg", output,
                ArchiveSupport.Type.TAR_GZ)); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    private File damagedFixture(boolean gzip, boolean truncated) throws Exception {
        File plain = fixture("original-" + System.nanoTime() + ".tar", false, false, "other", "two");
        byte[] bytes = Files.readAllBytes(plain.toPath());
        if (truncated) bytes = java.util.Arrays.copyOf(bytes, 513); // Header plus one of three payload bytes.
        else bytes[0] ^= 1; // Change a pathname byte without updating the header checksum.
        File archive = temp.newFile("damaged-" + System.nanoTime() + (gzip ? ".tar.gz" : ".tar"));
        try (OutputStream file = new FileOutputStream(archive);
             OutputStream output = gzip ? new GZIPOutputStream(file) : file) {
            output.write(bytes);
        }
        return archive;
    }

    private File fixture(String name, boolean gzip, boolean links, String finalName, String finalData) throws Exception {
        File archive = temp.newFile(name);
        try (OutputStream file = new FileOutputStream(archive);
             OutputStream payload = gzip ? new GZIPOutputStream(file) : file;
             TarArchiveOutputStream tar = new TarArchiveOutputStream(payload, "UTF-8")) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            if (links) {
                for (byte flag : new byte[]{TarConstants.LF_SYMLINK, TarConstants.LF_LINK}) {
                    TarArchiveEntry link = new TarArchiveEntry("link.jpg", flag);
                    link.setLinkName("outside.jpg");
                    tar.putArchiveEntry(link); tar.closeArchiveEntry();
                }
                tar.putArchiveEntry(new TarArchiveEntry("pipe", TarConstants.LF_FIFO));
                tar.closeArchiveEntry();
            }
            member(tar, "chapter/1.jpg", "one");
            member(tar, finalName, finalData);
        }
        return archive;
    }

    private static void member(TarArchiveOutputStream tar, String name, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry();
    }
}
