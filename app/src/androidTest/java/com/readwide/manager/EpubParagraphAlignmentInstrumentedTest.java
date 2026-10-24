package com.readwide.manager;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.util.EpubBindingRewriter;
import com.readwide.manager.util.EpubTextAlignment;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Computed-style regressions for trusted test HTML; not executed by source checks. */
@RunWith(AndroidJUnit4.class)
public class EpubParagraphAlignmentInstrumentedTest {
    @Test public void bookDefaultRetainsPublisherAlignment() throws Exception {
        checkStyles(EpubTextAlignment.BOOK_DEFAULT, "justify", "justify");
    }

    @Test public void startOverridesInlinePriorityButRetainsSpecialAndInheritedCentering() throws Exception {
        checkStyles(EpubTextAlignment.START, "start", "auto");
    }

    @Test public void justifiedKeepsLastLineNaturalAndSpecialFormattingIntact() throws Exception {
        checkStyles(EpubTextAlignment.JUSTIFY, "justify", "auto");
    }

    private static void checkStyles(int mode, String expected, String expectedLast) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<WebView> view = new AtomicReference<>();
        String stylesheet = EpubCssCompatibility.prepareParagraphAlignment(
                "body{text-align:justify}h1{text-align:center}"
                + "table p,blockquote p{text-align:left}.center{text-align:center}.right{text-align:right}");
        String body = "<body><p id='prose' style='text-align:justify!important;text-align-last:justify'>Prose</p>"
                + "<h1 id='title'>Title</h1><div class='center'><p id='center'>Centered</p></div>"
                + "<p class='right' id='right'>Right</p><div dir='rtl'><p id='rtl'>RTL</p></div>"
                + "<table><tr><td><p id='table'>Cell</p></td></tr></table>"
                + "<blockquote><p id='quote'>Quote</p></blockquote></body>";
        if (mode != EpubTextAlignment.BOOK_DEFAULT) {
            body = EpubBindingRewriter.transformEmbeddedCss(body, EpubCssCompatibility::prepareParagraphAlignment);
        }
        String html = "<!doctype html><html><head>" + EpubCssCompatibility.paragraphAlignmentStyle(mode)
                + "<style>" + stylesheet + "</style></head>" + body + "</html>";
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                WebView web = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                view.set(web);
                // Script is only for querying this synthetic fixture's computed styles.
                // Production EPUB scripting policy is not changed by the setting.
                web.getSettings().setJavaScriptEnabled(true);
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView current, String url) {
                        current.evaluateJavascript("(function(){var result={};"
                                + "['prose','title','center','right','rtl','table','quote'].forEach(function(id){"
                                + "result[id]=getComputedStyle(document.getElementById(id)).textAlign;});"
                                + "result.last=getComputedStyle(document.getElementById('prose')).textAlignLast;"
                                + "result.direction=getComputedStyle(document.getElementById('rtl')).direction;"
                                + "return result;})()", value -> {
                            try {
                                JSONObject styles = new JSONObject(value);
                                assertEquals(expected, styles.getString("prose"));
                                assertEquals(expectedLast, styles.getString("last"));
                                assertEquals(expected, styles.getString("rtl"));
                                assertEquals("rtl", styles.getString("direction"));
                                assertEquals("center", styles.getString("title"));
                                assertEquals("center", styles.getString("center"));
                                assertEquals("right", styles.getString("right"));
                                assertEquals("left", styles.getString("table"));
                                assertEquals("left", styles.getString("quote"));
                            } catch (Throwable error) { failure.set(error); }
                            finally { finished.countDown(); }
                        });
                    }
                });
                web.loadDataWithBaseURL("https://readwide.invalid/", html, "text/html", "UTF-8", null);
            });
            assertTrue("WebView style callback timed out", finished.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (view.get() != null) view.get().destroy();
            });
        }
    }
}
