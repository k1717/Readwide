package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class FileEntryNameTest {
    @Test public void acceptsSingleFolderNames() {
        assertTrue(FileSystemOps.isSingleEntryName("Books"));
        assertTrue(FileSystemOps.isSingleEntryName("읽을 책"));
        assertTrue(FileSystemOps.isSingleEntryName(".books"));
    }

    @Test public void rejectsParentAndCurrentDirectories() {
        assertFalse(FileSystemOps.isSingleEntryName(""));
        assertFalse(FileSystemOps.isSingleEntryName("."));
        assertFalse(FileSystemOps.isSingleEntryName(".."));
    }

    @Test public void rejectsPathsAndNul() {
        assertFalse(FileSystemOps.isSingleEntryName("../Books"));
        assertFalse(FileSystemOps.isSingleEntryName("/Books"));
        assertFalse(FileSystemOps.isSingleEntryName("nested/Books"));
        assertFalse(FileSystemOps.isSingleEntryName("..\\Books"));
        assertFalse(FileSystemOps.isSingleEntryName("book\0name"));
    }
}
