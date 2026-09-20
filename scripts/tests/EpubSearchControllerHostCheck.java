package com.readwide.manager;

/** Exact selected controller methods with explicit host WebView/lifecycle seams. */
public final class EpubSearchControllerHostCheck {
    private static int passed, failed;
    private static void check(String name, Runnable body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }
    private static void expect(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        check("font selection reload preserves current position", () -> {
            DocumentPageActivity a = new DocumentPageActivity();
            new DocumentFontDialogController(a).refresh();
            expect(a.reloads == 1 && a.directLoads == 0);
        });
        check("font selection skips invalid page", () -> {
            DocumentPageActivity a = new DocumentPageActivity(); a.valid = false;
            new DocumentFontDialogController(a).refresh();
            expect(a.reloads == 0 && a.directLoads == 0);
        });
        check("search cleanup reload preserves found passage", () -> {
            DocumentPageActivity a = new DocumentPageActivity();
            new DocumentSearchController(a).refresh();
            expect(a.reloads == 1 && a.directLoads == 0);
        });
        check("search cleanup skips closed reader", () -> {
            DocumentPageActivity a = new DocumentPageActivity(); a.activityDestroyed = true;
            new DocumentSearchController(a).refresh();
            expect(a.reloads == 0 && a.directLoads == 0);
        });
        check("current selection completion reveals match", () -> {
            DocumentPageActivity a = new DocumentPageActivity();
            new DocumentSearchController(a).select(); a.webView.callback.accept("true");
            expect(a.reveals == 1 && a.reloads == 0);
        });
        check("missing current markup reload preserves position", () -> {
            DocumentPageActivity a = new DocumentPageActivity();
            new DocumentSearchController(a).select(); a.webView.callback.accept("false");
            expect(a.reveals == 0 && a.reloads == 1 && a.directLoads == 0);
        });
        check("late completion cannot reload newly navigated page", () -> {
            DocumentPageActivity a = new DocumentPageActivity();
            new DocumentSearchController(a).select(); a.currentPage++;
            a.webView.callback.accept("false");
            expect(a.reveals == 0 && a.reloads == 0 && a.directLoads == 0);
        });
        check("late completion cannot reload replacement document", () -> {
            DocumentPageActivity a = new DocumentPageActivity();
            new DocumentSearchController(a).select(); a.documentAnchorPageGeneration++;
            a.webView.callback.accept("false");
            expect(a.reveals == 0 && a.reloads == 0 && a.directLoads == 0);
        });
        check("late completion cannot reveal a changed query", () -> {
            DocumentPageActivity a = new DocumentPageActivity();
            new DocumentSearchController(a).select(); a.activeDocumentSearchQuery = "summer";
            a.webView.callback.accept("true"); expect(a.reveals == 0);
        });
        check("late completion cannot interfere with next match", () -> {
            DocumentPageActivity a = new DocumentPageActivity();
            new DocumentSearchController(a).select(); a.activeDocumentSearchOrdinal++;
            a.webView.callback.accept("true"); expect(a.reveals == 0);
        });
        check("late completion ignores replaced WebView", () -> {
            DocumentPageActivity a = new DocumentPageActivity();
            new DocumentSearchController(a).select(); WebView old = a.webView; a.webView = new WebView();
            old.callback.accept("true"); expect(a.reveals == 0);
        });
        System.out.println("TOTAL: " + passed + " passed; " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
