package com.readwide.manager;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Notification return routing only; no document load, WebView or speech engine. */
@RunWith(AndroidJUnit4.class)
public class TtsNotificationReturnInstrumentedTest {
    @Test public void documentNotificationKeepsIntentAndPlaybackSession() {
        check(f -> {
            TtsPlaybackBridge.register(f.document);
            Intent original = f.document.getIntent();
            Bundle viewport = new Bundle();
            setField(DocumentPageActivity.class, f.document, "pendingRecreationViewport", viewport);
            f.document.onNewIntent(notification());
            assertSame(viewport, getField(DocumentPageActivity.class, f.document, "pendingRecreationViewport"));
            assertSame(original, f.document.getIntent());
            assertEquals(0, f.document.saves);
            assertEquals(0, f.document.loads);
        });
    }

    @Test public void pdfNotificationKeepsIntentAndPlaybackSession() {
        check(f -> {
            TtsPlaybackBridge.register(f.pdf);
            Intent original = f.pdf.getIntent();
            new PdfReaderStartupController(f.pdf).onNewIntent(notification());
            assertSame(original, f.pdf.getIntent());
            assertEquals(0, f.pdf.saves);
            assertEquals(0, f.pdf.loads);
        });
    }

    @Test public void txtNotificationKeepsIntentAndPlaybackSession() {
        check(f -> {
            TtsPlaybackBridge.register(f.txt);
            Intent original = f.txt.getIntent();
            setField(ReaderActivity.class, f.txt, "autoStartTtsConsumed", true);
            f.txt.onNewIntent(notification());
            assertEquals(Boolean.TRUE, getField(ReaderActivity.class, f.txt, "autoStartTtsConsumed"));
            assertSame(original, f.txt.getIntent());
            assertEquals(0, f.txt.saves);
            assertEquals(0, f.txt.loads);
        });
    }

    @Test public void ordinarySameFileReopenStillLoads() {
        check(f -> {
            TtsPlaybackBridge.register(f.document);
            Intent reopen = new Intent().putExtra(DocumentPageActivity.EXTRA_FILE_PATH, "book");
            new DocumentPageStartupController(f.document).onNewIntent(reopen);
            assertSame(reopen, f.document.getIntent());
            assertEquals(1, f.document.saves);
            assertEquals(1, f.document.loads);
        });
    }

    @Test public void copiedMarkerCannotSuppressPositionAutostartOrUriRequests() {
        check(f -> {
            TtsPlaybackBridge.register(f.document);
            String[] explicitExtras = {
                    DocumentPageActivity.EXTRA_JUMP_TO_PAGE,
                    DocumentPageActivity.EXTRA_CONTENT_ANCHOR_JSON,
                    DocumentPageActivity.EXTRA_AUTOSTART_TTS,
                    DocumentPageActivity.EXTRA_FILE_URI,
                    DocumentPageActivity.EXTRA_MARKDOWN_SOURCE_OFFSET,
                    PdfReaderActivity.EXTRA_JUMP_TO_PAGE
            };
            for (String key : explicitExtras) {
                Intent explicit = notification().putExtra(key, "explicit");
                assertFalse(TtsPlaybackBridge.isNotificationReturnTo(
                        f.document, explicit, DocumentPageActivity.EXTRA_FILE_PATH));
            }
            assertFalse(TtsPlaybackBridge.isNotificationReturnTo(f.document,
                    notification().setAction(Intent.ACTION_VIEW), DocumentPageActivity.EXTRA_FILE_PATH));
            assertFalse(TtsPlaybackBridge.isNotificationReturnTo(f.document,
                    notification().setData(Uri.parse("content://example/book")), DocumentPageActivity.EXTRA_FILE_PATH));
            Intent bookmark = notification().putExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, 4);
            new DocumentPageStartupController(f.document).onNewIntent(bookmark);
            assertEquals(1, f.document.loads);
            assertSame(bookmark, f.document.getIntent());
        });
    }

    @Test public void differentPathHostOrDestroyedSessionDoesNotShortcut() {
        check(f -> {
            TtsPlaybackBridge.register(f.document);
            assertFalse(TtsPlaybackBridge.isNotificationReturnTo(f.document,
                    notification().putExtra(DocumentPageActivity.EXTRA_FILE_PATH, "other"),
                    DocumentPageActivity.EXTRA_FILE_PATH));
            assertFalse(TtsPlaybackBridge.isNotificationReturnTo(f.pdf, notification(), PdfReaderActivity.EXTRA_FILE_PATH));
            f.document.activityDestroyed = true;
            assertFalse(TtsPlaybackBridge.isNotificationReturnTo(f.document, notification(), DocumentPageActivity.EXTRA_FILE_PATH));
        });
    }

    private static Intent notification() {
        return new Intent().putExtra(DocumentPageActivity.EXTRA_FILE_PATH, "book")
                .putExtra(TtsPlaybackService.EXTRA_RETURN_TO_PLAYBACK, true);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Object getField(Class<?> owner, Object target, String name) {
        try {
            java.lang.reflect.Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void check(Consumer<Fixture> check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try { check.accept(f); } finally { f.close(); }
        });
    }

    private static final class Fixture {
        final DocumentScreen document = new DocumentScreen();
        final PdfScreen pdf = new PdfScreen();
        final TxtScreen txt = new TxtScreen();
        Fixture() {
            document.filePath = pdf.filePath = txt.filePath = "book";
            document.setIntent(new Intent().putExtra("original", true));
            pdf.setIntent(new Intent().putExtra("original", true));
            txt.setIntent(new Intent().putExtra("original", true));
        }
        void close() {
            TtsPlaybackBridge.unregister(document);
            TtsPlaybackBridge.unregister(pdf);
            TtsPlaybackBridge.unregister(txt);
            document.executor.shutdownNow();
            pdf.executor.shutdownNow();
            txt.executor.shutdownNow();
            txt.largeTextSearchExecutor.shutdownNow();
            txt.largeTextSearchCountExecutor.shutdownNow();
        }
    }

    private static final class DocumentScreen extends DocumentPageActivity {
        int saves, loads;
        @Override void saveOutgoingDocumentReadingState() { saves++; }
        @Override void loadFromIntent(Intent intent) { loads++; }
    }
    private static final class PdfScreen extends PdfReaderActivity {
        int saves, loads;
        @Override void saveReadingState() { saves++; }
        @Override void loadPdfFromIntent() { loads++; }
    }
    private static final class TxtScreen extends ReaderActivity {
        int saves, loads;
        @Override void saveReadingState() { saves++; }
        @Override void loadFileFromIntent(Intent intent) { loads++; }
    }
}
