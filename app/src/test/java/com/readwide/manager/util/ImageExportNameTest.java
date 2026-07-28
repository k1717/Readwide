package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ImageExportNameTest {
    @Test
    public void keepsArchiveEntryBaseNameAndUnicode() {
        assertEquals("表紙 01.jpg",
                ImageExportName.safeDisplayName("images/表紙 01.jpg", "image/jpeg"));
    }

    @Test
    public void removesPathAndUnsafeCharacters() {
        assertEquals("page_01_.png",
                ImageExportName.safeDisplayName("../folder/page:01?.png", "image/png"));
    }

    @Test
    public void addsExtensionWhenEntryHasNone() {
        assertEquals("cover.jpg",
                ImageExportName.safeDisplayName("cover", "image/jpeg"));
    }

    @Test
    public void suppliesFallbackName() {
        assertEquals("image.webp",
                ImageExportName.safeDisplayName("  ", "image/webp"));
    }

    @Test
    public void splitsStemAndExtensionForCollisionSuffix() {
        assertEquals("page.tar", ImageExportName.stem("page.tar.png"));
        assertEquals(".png", ImageExportName.extension("page.tar.png"));
    }
}
