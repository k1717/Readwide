package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ArchiveOverwriteReplacementTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void replaceExistingDirectoryWithTempInstallsNewTreeAndRemovesBackup() throws Exception {
        File parent = tempFolder.getRoot();
        File destination = new File(parent, "book");
        File oldFile = new File(destination, "old.txt");
        File temp = new File(parent, ".book_extract_test");
        File newFile = new File(temp, "new.txt");

        assertTrue(destination.mkdirs());
        Files.write(oldFile.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        assertTrue(temp.mkdirs());
        Files.write(newFile.toPath(), "new".getBytes(StandardCharsets.UTF_8));

        Method method = ArchiveSupport.class.getDeclaredMethod(
                "replaceExistingDirectoryWithTemp", File.class, File.class);
        method.setAccessible(true);
        boolean ok = (Boolean) method.invoke(null, destination, temp);

        assertTrue(ok);
        assertFalse(temp.exists());
        assertFalse(oldFile.exists());
        File installed = new File(destination, "new.txt");
        assertTrue(installed.isFile());
        assertEquals("new", new String(Files.readAllBytes(installed.toPath()), StandardCharsets.UTF_8));
        File[] backups = parent.listFiles(file -> file.getName().contains("backup"));
        assertTrue(backups == null || backups.length == 0);
    }
}
