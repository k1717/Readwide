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

/** Computed CSS fixtures only; execution requires an Android WebView. */
@RunWith(AndroidJUnit4.class)
public class EpubTypographyInstrumentedTest {
    @Test public void bookDefaultUsesOriginalInlineAndStylesheetSpacing() throws Exception {
        checkStyles(0, 26.0, 28.0, 42.0);
    }

    @Test public void selectedSpacingCoexistsWithAlignmentAndPreservesSpecialBlocks() throws Exception {
        checkStyles(4, 36.0, 36.0, 54.0);
    }

    private static void checkStyles(int choice, double prose, double inherited, double larger) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<WebView> view = new AtomicReference<>();
        String css = "body{font-size:20px;line-height:1.4;text-align:justify}"
                + "h1{font-size:20px;line-height:1.1;text-align:center}"
                + "table,pre,blockquote{font-size:20px;line-height:1.2}"
                + ".large{font-size:30px}.formula{line-height:calc(1em + 3px)}";
        // Shared stylesheets are always prepared, including pages with Book default.
        css = EpubCssCompatibility.prepareLineHeight(EpubCssCompatibility.prepareParagraphAlignment(css));
        String body = "<body><p id='prose' style='line-height:1.3!important'>Text</p>"
                + "<p id='inherited'>Inherited</p><p class='large' id='larger'>Larger</p>"
                + "<h1 id='title'>Title</h1><table><tr><td id='cell'>Cell</td></tr></table>"
                + "<pre id='code'>Code</pre><blockquote id='quote'>Quote</blockquote>"
                + "<p class='formula' id='formula'>Expression</p></body>";
        if (choice != 0) body = EpubBindingRewriter.transformEmbeddedCss(body, EpubCssCompatibility::prepareLineHeight);
        String html = "<!doctype html><html><head>"
                + EpubCssCompatibility.lineHeightStyle(choice)
                + EpubCssCompatibility.paragraphAlignmentStyle(EpubTextAlignment.START)
                + "<style>" + css + "</style></head>" + body + "</html>";
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                WebView web = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                view.set(web);
                // Only this trusted synthetic fixture enables script to read computed CSS.
                web.getSettings().setJavaScriptEnabled(true);
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView current, String url) {
                        current.evaluateJavascript("(function(){var result={};"
                                + "['prose','inherited','larger','title','cell','code','quote','formula'].forEach(function(id){"
                                + "result[id]=parseFloat(getComputedStyle(document.getElementById(id)).lineHeight);});"
                                + "result.align=getComputedStyle(document.getElementById('prose')).textAlign;"
                                + "return result;})()", value -> {
                            try {
                                JSONObject styles = new JSONObject(value);
                                assertEquals(prose, styles.getDouble("prose"), 0.05);
                                assertEquals(inherited, styles.getDouble("inherited"), 0.05);
                                assertEquals(larger, styles.getDouble("larger"), 0.05);
                                assertEquals(22.0, styles.getDouble("title"), 0.05);
                                for (String id : new String[]{"cell", "code", "quote"})
                                    assertEquals(24.0, styles.getDouble(id), 0.05);
                                assertEquals(23.0, styles.getDouble("formula"), 0.05);
                                assertEquals("start", styles.getString("align"));
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
