package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileRenameTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void ordinaryRenamePreservesContents() throws Exception {
        File source = temp.newFile("book.txt"); write(source, "book");
        assertTrue(FileSystemOps.renameInPlace(source, "renamed.txt"));
        assertFalse(source.exists());
        assertText(new File(temp.getRoot(), "renamed.txt"), "book");
    }

    @Test public void caseOnlyDirectoryRenamePreservesNestedFiles() throws Exception {
        File source = temp.newFolder("chapter"); write(new File(source, "page"), "page");
        assertTrue(FileSystemOps.renameInPlace(source, "CHAPTER"));
        assertText(new File(temp.getRoot(), "CHAPTER/page"), "page");
        assertTrue(java.util.Arrays.asList(temp.getRoot().list()).contains("CHAPTER"));
        assertEquals(0, recoveryEntries().length);
    }

    @Test public void ordinaryRenameCannotReplaceExistingFile() throws Exception {
        File source = temp.newFile("book.txt"), target = temp.newFile("target.txt");
        write(source, "book"); write(target, "target");
        assertFalse(FileSystemOps.renameInPlace(source, target.getName()));
        assertText(source, "book"); assertText(target, "target");
    }

    @Test public void danglingDestinationIsOccupied() throws Exception {
        File source = temp.newFile("book.txt"), target = new File(temp.getRoot(), "target.txt");
        write(source, "book"); link(target, new File(temp.getRoot(), "missing"));
        assertFalse(FileSystemOps.renameInPlace(source, target.getName()));
        assertText(source, "book"); assertTrue(Files.isSymbolicLink(target.toPath()));
    }

    @Test public void caseOnlyDanglingTargetIsNotAnAliasOfSource() throws Exception {
        File source = temp.newFile("book.txt"), target = new File(temp.getRoot(), "BOOK.txt");
        Assume.assumeFalse("Requires distinct case-sensitive names", target.exists());
        write(source, "book"); link(target, new File(temp.getRoot(), "missing"));
        assertFalse(FileSystemOps.renameInPlace(source, target.getName()));
        assertText(source, "book"); assertTrue(Files.isSymbolicLink(target.toPath()));
        assertEquals(0, recoveryEntries().length);
    }

    @Test public void renamingDanglingSourceMovesOnlyTheLink() throws Exception {
        File source = new File(temp.getRoot(), "link"), missing = new File(temp.getRoot(), "missing");
        link(source, missing);
        assertTrue(FileSystemOps.renameInPlace(source, "renamed"));
        assertFalse(Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS));
        File target = new File(temp.getRoot(), "renamed");
        assertEquals(missing.toPath(), Files.readSymbolicLink(target.toPath()));
        assertFalse(missing.exists());
    }

    @Test public void deniedFirstHopLeavesOriginalName() throws Exception {
        File actual = temp.newFile("book.txt"); write(actual, "book");
        File source = new File(actual.getPath()) {
            @Override public boolean renameTo(File target) { throw new SecurityException("denied"); }
        };
        assertFalse(FileSystemOps.renameInPlace(source, "BOOK.txt"));
        assertText(actual, "book"); assertEquals(0, recoveryEntries().length);
    }

    @Test public void lateTargetCollisionKeepsBothOriginalAndCompetingData() throws Exception {
        File actual = temp.newFile("book.txt"), target = new File(temp.getRoot(), "BOOK.txt");
        write(actual, "book"); File[] staged = {null};
        File source = new File(actual.getPath()) {
            @Override public boolean renameTo(File temporary) {
                boolean moved = super.renameTo(temporary);
                if (moved) {
                    staged[0] = temporary;
                    try { write(target, "competing"); } catch (IOException failure) { throw new AssertionError(failure); }
                }
                return moved;
            }
        };
        assertFalse(FileSystemOps.renameInPlace(source, target.getName()));
        assertText(target, "competing");
        // On insensitive storage the competing target also occupies the original
        // name, so recovery must stay temporary. Sensitive storage restores it.
        assertText(staged[0].exists() ? staged[0] : actual, "book");
    }

    @Test public void rollbackDoesNotClobberDanglingOriginalName() throws Exception {
        File probe = new File(temp.getRoot(), "probe"), missing = new File(temp.getRoot(), "missing");
        link(probe, missing); Files.delete(probe.toPath());
        File actual = temp.newFile("book.txt"), target = new File(temp.getRoot(), "BOOK.txt");
        write(actual, "book"); File[] staged = {null};
        File source = new File(actual.getPath()) {
            @Override public boolean renameTo(File temporary) {
                boolean moved = super.renameTo(temporary);
                if (moved) {
                    staged[0] = temporary;
                    try {
                        Files.createSymbolicLink(actual.toPath(), missing.toPath());
                        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) write(target, "competing");
                    } catch (IOException failure) { throw new AssertionError(failure); }
                }
                return moved;
            }
        };
        assertFalse(FileSystemOps.renameInPlace(source, target.getName()));
        assertTrue(Files.isSymbolicLink(actual.toPath()));
        assertEquals(missing.toPath(), Files.readSymbolicLink(actual.toPath()));
        assertText(staged[0], "book"); assertFalse(missing.exists());
    }

    @Test public void deniedInspectionReturnsFailureWithoutMutation() throws Exception {
        File actual = temp.newFile("book.txt"); write(actual, "book");
        File source = new File(actual.getPath()) {
            @Override public Path toPath() { throw new SecurityException("denied"); }
        };
        assertFalse(FileSystemOps.renameInPlace(source, "renamed.txt"));
        assertText(actual, "book"); assertFalse(new File(temp.getRoot(), "renamed.txt").exists());
    }

    @Test public void exactNameNoOpDoesNotCallRename() throws Exception {
        File actual = temp.newFile("book.txt");
        File source = new File(actual.getPath()) {
            @Override public boolean renameTo(File target) { throw new AssertionError("not a rename"); }
        };
        assertTrue(FileSystemOps.renameInPlace(source, "book.txt"));
    }

    @Test public void invalidSingleEntryNamesNeverChangeSource() throws Exception {
        File source = temp.newFile("book.txt"); write(source, "book");
        for (String name : new String[]{"", ".", "..", "../book", "dir/book", "dir\\book", "bad\0name"}) {
            assertFalse(name, FileSystemOps.renameInPlace(source, name));
        }
        assertText(source, "book"); assertEquals(1, temp.getRoot().list().length);
    }

    private File[] recoveryEntries() { return temp.getRoot().listFiles((dir, name) -> name.startsWith(".rwrename_")); }
    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static void assertText(File file, String text) throws IOException {
        assertEquals(text, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    private static void link(File path, File target) throws IOException {
        try { Files.createSymbolicLink(path.toPath(), target.toPath()); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Host cannot create symbolic links", unavailable);
        }
    }
}
