package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Exercise each dedicated Java bulk path without native fallback. */
@RunWith(Parameterized.class)
public class ArchiveDirectoryCollisionTest {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> formats() {
        return Arrays.asList(new Object[][]{{"tar"}, {"zip"}, {"zip-commons"}, {"7z"}});
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final String format;
    public ArchiveDirectoryCollisionTest(String format) { this.format = format; }

    private File fixture(String name) throws Exception {
        File file = temporary.newFile();
        if (format.equals("tar")) {
            try (TarArchiveOutputStream out = new TarArchiveOutputStream(new FileOutputStream(file))) {
                out.putArchiveEntry(new TarArchiveEntry(name)); out.closeArchiveEntry();
            }
        } else if (format.equals("7z")) {
            try (SevenZOutputFile out = new SevenZOutputFile(file)) {
                SevenZArchiveEntry entry = new SevenZArchiveEntry();
                entry.setName(name); entry.setDirectory(true);
                out.putArchiveEntry(entry); out.closeArchiveEntry();
            }
        } else {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
                out.putNextEntry(new ZipEntry(name)); out.closeEntry();
            }
        }
        return file;
    }

    private boolean extract(File archive, File root) throws Exception {
        String methodName = format.equals("tar") ? "extractTarIntoDirectory"
                : format.equals("7z") ? "extractSevenZIntoDirectory"
                : format.equals("zip-commons") ? "extractZipWithCommonsCompress" : "extractZipIntoDirectory";
        Method method = Arrays.stream(ArchiveSupport.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName)).findFirst().get();
        method.setAccessible(true);
        try {
            return (boolean) method.invoke(null, archive, root,
                    format.equals("tar") ? ArchiveSupport.Type.TAR : null, null, null);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof IOException) return false;
            throw error;
        }
    }

    @Test public void existingFileCannotSatisfyDirectoryEntry() throws Exception {
        File root = temporary.newFolder(), sentinel = new File(root, "chapter");
        Files.write(sentinel.toPath(), new byte[]{42});
        assertFalse(extract(fixture("chapter/"), root));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(sentinel.toPath()));
    }

    @Test public void fileParentCannotSatisfyNestedDirectory() throws Exception {
        File root = temporary.newFolder(), sentinel = new File(root, "chapter");
        Files.write(sentinel.toPath(), new byte[]{42});
        assertFalse(extract(fixture("chapter/section/"), root));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(sentinel.toPath()));
    }

    @Test public void existingAndNewDirectoriesRemainAccepted() throws Exception {
        File root = temporary.newFolder();
        assertTrue(new File(root, "chapter").mkdir());
        assertTrue(extract(fixture("chapter/"), root));
        assertTrue(extract(fixture("chapter/section/"), root));
        assertTrue(new File(root, "chapter/section").isDirectory());
    }
}
