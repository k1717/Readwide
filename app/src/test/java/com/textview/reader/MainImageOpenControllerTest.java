package com.textview.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

public class MainImageOpenControllerTest {
    @Test
    public void visibleImageSequencePreservesCurrentMainListOrder() {
        ArrayList<File> visible = new ArrayList<>(Arrays.asList(
                new File("/tmp/book/010.png"),
                new File("/tmp/book/002.jpg"),
                new File("/tmp/book/readme.txt"),
                new File("/tmp/book/001.webp")
        ));

        ArrayList<String> paths = MainImageOpenController.buildVisibleImageResultPaths(
                "/tmp/book/002.jpg",
                visible);

        assertEquals(Arrays.asList(
                "/tmp/book/010.png",
                "/tmp/book/002.jpg",
                "/tmp/book/001.webp"
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
