package com.readwide.manager.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class ArchiveWhitespaceIdentityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void zipListingAndExtractionKeepWhitespaceDistinct() throws Exception {
        File archive = temporary.newFile("identity.zip");
        String[] names = {"page.bin", " page.bin", "page.bin "};
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                zip.write(new byte[] {(byte) (i + 1)});
                zip.closeEntry();
            }
        }
        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
        assertEquals(names.length, entries.size());
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], entries.get(i).path);
            File output = new File(temporary.getRoot(), "output" + i);
            assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive, names[i], output, null).success);
            assertArrayEquals(new byte[] {(byte) (i + 1)}, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void preservingWhitespaceDoesNotRelaxTraversalChecks() {
        assertNull(ArchiveSupport.sanitizeEntryPathForList("../page.bin"));
        assertNull(ArchiveSupport.sanitizeEntryPathForList("folder/../page.bin"));
        assertNull(ArchiveSupport.sanitizeEntryPathForList("/page.bin"));
        assertEquals(" page.bin ", ArchiveSupport.sanitizeEntryPathForList(" page.bin "));
    }

    @Test public void normalizedNamesKeepTheirIdentityWithoutAnotherStringCopy() {
        String path = new String("folder/ page.bin ");
        assertSame(path, ArchiveSupport.sanitizeEntryPathForList(path));
        assertEquals("folder/./page.bin", ArchiveSupport.sanitizeEntryPathForList("folder/./page.bin"));
        assertEquals("folder/name\n.bin", ArchiveSupport.sanitizeEntryPathForList("folder/name\n.bin"));
        assertEquals("folder/page.bin", ArchiveSupport.sanitizeEntryPathForList(".\\./folder\\\\page.bin"));
    }

    @Test public void longRelativePrefixesAndSeparatorRunsAreNormalized() {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < 8192; i++) path.append("./");
        path.append("folder");
        for (int i = 0; i < 8192; i++) path.append('/');
        path.append("page.bin");
        assertEquals("folder/page.bin", ArchiveSupport.sanitizeEntryPathForList(path.toString()));
        assertNull(ArchiveSupport.sanitizeEntryPathForList(path.append("/../escape").toString()));
    }

    @Test public void allSharedJavaPathConsumersRejectUnsafeNames() throws Exception {
        String[] unsafe = {"", "././", ".//page", "././/page", "/absolute", "\\\\server\\page",
                "../page", "folder//..//page", "folder/..", "C:/page\nname", "z:\rname",
                "folder/\0page"};
        for (String path : unsafe) assertNull(path, ArchiveSupport.sanitizeEntryPathForList(path));
        for (Class<?> type : new Class<?>[]{AlzipArchiveReader.class, EggArchiveReader.class,
                RarArchiveReader.class, LightweightZipArchiveReader.class, ArchiveExtractionProgressTracker.class}) {
            String method = type == ArchiveExtractionProgressTracker.class ? "normalizeEntryPath" : "sanitizeEntryPath";
            java.lang.reflect.Method sanitize = type.getDeclaredMethod(method, String.class);
            sanitize.setAccessible(true);
            for (String path : unsafe) assertNull(type.getSimpleName() + ": " + path, sanitize.invoke(null, path));
            assertEquals("folder/ page.bin ", sanitize.invoke(null, "./folder// page.bin "));
        }
    }

    @Test public void boundedPathCombinationsRetainLegacyNormalization() {
        String[] pieces = {".", "..", "/", "\\", "name", " ", "C:", "한글"};
        for (String a : pieces) for (String b : pieces) for (String c : pieces) {
            String path = a + b + c;
            assertEquals(path, legacyPath(path), ArchiveSupport.sanitizeEntryPathForList(path));
        }
    }

    private static String legacyPath(String path) {
        path = path.replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        while (path.contains("//")) path = path.replace("//", "/");
        if (path.isEmpty() || path.startsWith("/") || path.equals("..") || path.startsWith("../")
                || path.contains("/../") || path.endsWith("/..") || path.matches("^[A-Za-z]:.*")) return null;
        return path;
    }
}
