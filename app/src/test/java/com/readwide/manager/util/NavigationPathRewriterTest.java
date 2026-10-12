package com.readwide.manager.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationPathRewriterTest {
    @Test public void rewritesRootAndNestedDirectory() {
        assertEquals("/New", NavigationPathRewriter.rebase("/Old", "/Old", "/New"));
        assertEquals("/New/Part/One", NavigationPathRewriter.rebase("/Old/Part/One", "/Old", "/New"));
    }

    @Test public void preservesSiblingNamesAndUnrelatedPaths() {
        assertEquals("/Oldest/One", NavigationPathRewriter.rebase("/Oldest/One", "/Old", "/New"));
        assertEquals("/Other/Old", NavigationPathRewriter.rebase("/Other/Old", "/Old", "/New"));
    }

    @Test public void supportsCaseOnlyRenameWithoutCaseFoldingSiblings() {
        assertEquals("/books/One", NavigationPathRewriter.rebase("/Books/One", "/Books", "/books"));
        assertEquals("/BOOKS/One", NavigationPathRewriter.rebase("/BOOKS/One", "/Books", "/books"));
    }

    @Test public void normalizesTrailingRootSlashesOnly() {
        assertEquals("/New/One", NavigationPathRewriter.rebase("/Old/One", "/Old/", "/New/"));
        assertEquals("/New/Old/One", NavigationPathRewriter.rebase("/Old/Old/One", "/Old", "/New"));
    }

    @Test public void invalidRootsAndNullPathsAreNoOps() {
        assertNull(NavigationPathRewriter.rebase(null, "/Old", "/New"));
        assertEquals("/Old/One", NavigationPathRewriter.rebase("/Old/One", "", "/New"));
        assertEquals("/Old/One", NavigationPathRewriter.rebase("/Old/One", "/", "/New"));
        assertEquals("/Old/One", NavigationPathRewriter.rebase("/Old/One", "/Old", "/"));
    }
}
