package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

public class MainImageOpenControllerTest {
    @Test
    public void visibleImageSequencePreservesCurrentMainListOrder() {
        // Use getAbsolutePath() throughout: on Windows "/tmp/book/x" resolves
        // to a drive-letter backslash path, so hardcoded POSIX strings would
        // never match what production code derives from the same File.
        File png010 = new File("/tmp/book/010.png");
        File jpg002 = new File("/tmp/book/002.jpg");
        File txt = new File("/tmp/book/readme.txt");
        File webp001 = new File("/tmp/book/001.webp");
        ArrayList<File> visible = new ArrayList<>(Arrays.asList(png010, jpg002, txt, webp001));

        ArrayList<String> paths = MainImageOpenController.buildVisibleImageResultPaths(
                jpg002.getAbsolutePath(),
                visible);

        assertEquals(Arrays.asList(
                png010.getAbsolutePath(),
                jpg002.getAbsolutePath(),
                webp001.getAbsolutePath()
        ), paths);
    }

    @Test
    public void visibleImageSequenceReturnsEmptyWhenSelectedImageIsNotVisible() {
        ArrayList<File> visible = new ArrayList<>(Arrays.asList(
                new File("/tmp/book/001.jpg"),
                new File("/tmp/book/002.jpg")
        ));

        ArrayList<String> paths = MainImageOpenController.buildVisibleImageResultPaths(
                "/tmp/book/999.jpg",
                visible);

        assertTrue(paths.isEmpty());
    }
}
