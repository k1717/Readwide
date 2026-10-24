package com.readwide.manager;

import android.content.Intent;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/** Captures launches without opening screens, reading archives or creating preferences. */
@RunWith(AndroidJUnit4.class)
public class ImageViewerLaunchOptionsInstrumentedTest {
    @Test public void extractedImageReceivesOptionsAndRetainsItsSequence() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ArchiveScreen screen = new ArchiveScreen();
            try {
                File image = new File("/virtual/book/page.jpg");
                openExtracted(screen, image);
                assertEquals(1, screen.launches);
                assertNotNull(screen.options);
                assertEquals(ImageReaderActivity.class.getName(),
                        screen.intent.getComponent().getClassName());
                assertEquals(image.getAbsolutePath(),
                        screen.intent.getStringExtra(ImageReaderActivity.EXTRA_FILE_PATH));
                assertEquals(Collections.singletonList(image.getAbsolutePath()),
                        screen.intent.getStringArrayListExtra(ImageReaderActivity.EXTRA_FILE_PATHS));
                assertFalse(screen.intent.getBooleanExtra(ImageReaderActivity.EXTRA_ALLOW_FILE_OPS, true));
            } finally { screen.closeWorkers(); }
        });
    }

    @Test public void nonImageExtractedFilesKeepTheirNormalLaunch() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ArchiveScreen screen = new ArchiveScreen();
            try {
                String[] names = {"book.pdf", "book.epub", "book.txt", "book.zip"};
                Class<?>[] targets = {PdfReaderActivity.class, DocumentPageActivity.class,
                        ReaderActivity.class, ArchiveBrowserActivity.class};
                for (int i = 0; i < names.length; i++) {
                    openExtracted(screen, new File("/virtual/book", names[i]));
                    assertEquals(i + 1, screen.launches);
                    assertNull(screen.options);
                    assertEquals(targets[i].getName(), screen.intent.getComponent().getClassName());
                }
            } finally { screen.closeWorkers(); }
        });
    }

    @Test public void directComicLaunchRetainsFlagsHandoffAndFinishOrder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            MainScreen screen = new MainScreen();
            try {
                ArrayList<String> paths = new ArrayList<>(Collections.singletonList("/virtual/page.jpg"));
                ArchiveImageSequenceLoader.Result result = new ArchiveImageSequenceLoader.Result(
                        paths, new ArrayList<>(Collections.singletonList("page.jpg")),
                        new ArrayList<>(Collections.singletonList("chapter/page.jpg")), 0, true, null);
                invoke(MainArchiveImageOpenController.class, new MainArchiveImageOpenController(screen),
                        "openImageReader", new Class<?>[]{File.class, ArchiveImageSequenceLoader.Result.class},
                        new File("/virtual/book.cbz"), result);
                assertEquals(1, screen.launches);
                assertNotNull(screen.options);
                assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        screen.intent.getFlags());
                assertNotNull(screen.intent.getStringExtra(ImageReaderActivity.EXTRA_SEQUENCE_HANDOFF_TOKEN));
                assertEquals("chapter/page.jpg",
                        screen.intent.getStringExtra(ImageReaderActivity.EXTRA_SOURCE_ENTRY_PATH));
                assertEquals(1, screen.launchesAtFinish);
            } finally {
                if (screen.intent != null) ImageSequenceHandoffStore.discard(
                        screen.intent.getStringExtra(ImageReaderActivity.EXTRA_SEQUENCE_HANDOFF_TOKEN));
                screen.closeWorkers();
            }
        });
    }

    private static void openExtracted(ArchiveScreen screen, File file) {
        invoke(ArchiveBrowserActivity.class, screen, "openExtractedFile", new Class<?>[]{File.class}, file);
    }

    private static void invoke(Class<?> owner, Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static String appPackageName() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
    }

    private static final class ArchiveScreen extends ArchiveBrowserActivity {
        Intent intent;
        Bundle options;
        int launches;
        @Override public String getPackageName() { return appPackageName(); }
        @Override public void startActivity(Intent intent) { capture(intent, null); }
        @Override public void startActivity(Intent intent, Bundle options) { capture(intent, options); }
        private void capture(Intent intent, Bundle options) {
            this.intent = intent; this.options = options; launches++;
        }
        void closeWorkers() {
            try {
                java.lang.reflect.Field field = ArchiveBrowserActivity.class.getDeclaredField("executor");
                field.setAccessible(true);
                ((ExecutorService) field.get(this)).shutdownNow();
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }

    private static final class MainScreen extends MainActivity {
        Intent intent;
        Bundle options;
        int launches, launchesAtFinish;
        @Override public String getPackageName() { return appPackageName(); }
        @Override public void startActivity(Intent intent) { capture(intent, null); }
        @Override public void startActivity(Intent intent, Bundle options) { capture(intent, options); }
        private void capture(Intent intent, Bundle options) {
            this.intent = intent; this.options = options; launches++;
        }
        @Override void finishIfReturnToViewerMode() { launchesAtFinish = launches; }
        void closeWorkers() {
            fileSearchHandler.removeCallbacksAndMessages(null);
            fileSearchExecutor.shutdownNow(); fileOperationExecutor.shutdownNow();
            folderValidationExecutor.shutdownNow();
        }
    }
}
