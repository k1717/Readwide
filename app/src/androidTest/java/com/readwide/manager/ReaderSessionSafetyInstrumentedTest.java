package com.readwide.manager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.Layout;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.search.LargeTextSearchResult;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.util.SearchOptions;
import com.readwide.manager.view.CustomReaderView;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Focused source regressions. Adding these does not imply device/test execution. */
@RunWith(AndroidJUnit4.class)
public class ReaderSessionSafetyInstrumentedTest {
    @Test public void geometryPublishesOnlyAfterNewMarginAndInsetsAgree() {
        onMain(() -> {
            CustomReaderView view = reader("one two three");
            int[] notifications = {0};
            view.setReaderListener(new CustomReaderView.ReaderListener() {
                @Override public void onSingleTap(float x, float y) { }
                @Override public void onTextLongPress(String t, int s, int e, float x, float y) { }
                @Override public void onReaderManualScroll() { }
                @Override public void onReaderManualOverscroll(int direction) { }
                @Override public void onReaderScrollChanged() {
                    notifications[0]++;
                    assertTrue(view.hasCurrentTextLayout());
                    assertEquals(720, view.getTextLayoutWidthForIndex());
                }
            });
            try {
                view.updatePresentation(() -> {
                    view.setReaderStyle(18, 1.35f, Color.BLACK, Color.WHITE, 72, 16, Typeface.DEFAULT);
                    assertFalse(view.hasCurrentTextLayout());
                    view.setTextZoneAdjustments(0, 0, -72, -72);
                });
                assertEquals(1, notifications[0]);
            } finally { view.releaseTextResources(); }
        });
    }

    @Test public void noPhysicalViewportIsNotAOnePixelReadyLayout() {
        onMain(() -> {
            FakeReader activity = new FakeReader();
            activity.readerView = reader("first\nsecond");
            try {
                activity.filePath = "/height.txt";
                activity.textContentReadyForPersistence = true;
                activity.readerView.setPadding(0, 500, 0, 500);
                assertFalse(activity.readerView.hasCurrentTextLayout());
                assertNull(new ReaderMemoryController(activity).captureReadingState());
                activity.readerView.setPadding(0, 500, 0, 459); // One real drawable pixel.
                assertTrue(activity.readerView.hasCurrentTextLayout());
            } finally { close(activity); }
        });
    }

    @Test public void selectionEqualityKeepsTheHandleAndStrictCrossingSwapsIt() {
        onMain(() -> {
            CustomReaderView view = reader("abcdef");
            try {
                invoke(view, "setTextSelection", new Class<?>[]{int.class, int.class}, 1, 3);
                int startHandle = (int) field(view, "SELECTION_HANDLE_START");
                int endHandle = (int) field(view, "SELECTION_HANDLE_END");
                set(view, "activeSelectionHandle", startHandle);
                Layout layout = (Layout) field(view, "layout");
                float y = 16 + layout.getLineBaseline(0);
                invoke(view, "updateSelectionFromHandle", new Class<?>[]{int.class, float.class, float.class},
                        startHandle, 24 + layout.getPrimaryHorizontal(3), y);
                assertEquals(1, field(view, "textSelectionStart"));
                assertEquals(3, field(view, "textSelectionEnd"));
                assertEquals(startHandle, field(view, "activeSelectionHandle"));
                invoke(view, "updateSelectionFromHandle", new Class<?>[]{int.class, float.class, float.class},
                        startHandle, 24 + layout.getPrimaryHorizontal(5), y);
                assertEquals(3, field(view, "textSelectionStart"));
                assertEquals(5, field(view, "textSelectionEnd"));
                assertEquals(endHandle, field(view, "activeSelectionHandle"));
            } finally { view.releaseTextResources(); }
        });
    }

    @Test public void selectionUsesInsertionEndpointsAtWrappedAndFinalLineEdges() {
        onMain(() -> {
            CustomReaderView view = reader("word ".repeat(100));
            try {
                Layout layout = (Layout) field(view, "layout");
                int end = layout.getLineStart(1);
                invoke(view, "setTextSelection", new Class<?>[]{int.class, int.class}, 0, end);
                float[] point = (float[]) invoke(view, "getSelectionHandlePoint", new Class<?>[]{boolean.class}, false);
                assertEquals(24 + layout.getLineRight(0), point[0], 0.01f);
                int handle = (int) field(view, "SELECTION_HANDLE_END");
                float[] visual = (float[]) invoke(view, "getSelectionHandleVisualPoint", new Class<?>[]{boolean.class}, false);
                float[] grip = (float[]) invoke(view, "getSelectionHandleLowerGripCenter",
                        new Class<?>[]{int.class, float[].class}, handle, visual);
                long now = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                        android.view.MotionEvent.ACTION_DOWN, grip[0], grip[1], 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 10,
                        android.view.MotionEvent.ACTION_UP, grip[0], grip[1], 0);
                try { view.onTouchEvent(down); view.onTouchEvent(up); }
                finally { down.recycle(); up.recycle(); }
                assertEquals(end, field(view, "textSelectionEnd"));
                assertEquals(end, invoke(view, "getSelectionOffsetAt", new Class<?>[]{float.class, float.class},
                        1000f, (float) (16 + layout.getLineBottom(0) - 1)));
                view.setTextContent("abcdef");
                layout = (Layout) field(view, "layout");
                assertEquals(6, invoke(view, "getSelectionOffsetAt", new Class<?>[]{float.class, float.class},
                        1000f, (float) (16 + layout.getLineBottom(0) - 1)));
            } finally { view.releaseTextResources(); }
        });
    }

    @Test public void instantSearchRejectsRemoteStartAndCancelsAnOlderLanding() {
        onMain(() -> {
            FakeReader activity = new FakeReader();
            activity.prefs = PrefsManager.getInstance(context());
            activity.readerView = reader("hit hit hit");
            activity.fileContent = activity.readerView.getTextContent();
            activity.filePath = "/search.txt";
            activity.largeTextEstimateActive = true;
            activity.largeTextPreviewBaseCharOffset = 1000;
            activity.largeTextPartitionBodyCharCount = activity.fileContent.length();
            activity.activeSearchOrdinal = 5;
            ReaderSearchController controller = new ReaderSearchController(activity);
            try {
                assertNull(controller.searchCurrentLargeTextPartitionInstant("hit", 3000, false));
                assertNull(controller.searchCurrentLargeTextPartitionInstant("hit", 999, true));
                assertEquals(1004, controller.searchCurrentLargeTextPartitionInstant("hit", 1001, true).charPosition);
                controller.rememberLargeTextSearchTotal("hit", SearchOptions.literal().signature(), 20);
                activity.beginLargeTextPartitionSwitchPending(10, 20);
                assertTrue(controller.applyInstantLargeTextSearchResult("hit",
                        new LargeTextSearchResult(1004, 1, 6, 20), null));
                assertEquals(1, activity.cancellations);
                assertEquals(1004, activity.landedAt);
                assertFalse(activity.largeTextPartitionSwitchState.isInProgress());
            } finally { close(activity); }
        });
    }

    @Test public void externalEditTriggersContextReloadBeforeSessionAdoption() {
        onMain(() -> {
            FakeReader activity = new FakeReader();
            File file = null;
            try {
                file = File.createTempFile("txt-session-", ".txt", context().getCacheDir());
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1, 2, 3}); }
                activity.filePath = file.getAbsolutePath();
                activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, activity.filePath));
                activity.prefs = PrefsManager.getInstance(context());
                activity.readerView = reader("alpha");
                activity.fileContent = activity.readerView.getTextContent();
                activity.contentsSourceLength = file.length();
                activity.contentsSourceModified = file.lastModified();
                activity.appliedTextDisplayRuleSignature = activity.currentTextDisplayRuleSignature();
                assertTrue(activity.ensureLoadedTextCurrent());
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{4}); }
                assertFalse(activity.ensureLoadedTextCurrent());
                assertNotNull(activity.reload);
                assertEquals(5, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
                assertTrue(activity.reload.getBooleanExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION, false));
                assertFalse(activity.reload.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE));
            } catch (Exception failure) { throw new AssertionError(failure); }
            finally { close(activity); if (file != null && file.exists()) assertTrue(file.delete()); }
        });
    }

    @Test public void ttsSavesQueueOriginNotTheCurrentScrolledWindow() {
        withTts((controller, host) -> {
            queue(controller, host, 1000, 100);
            host.base = 5000;
            invoke(controller, "saveQueueCheckpoint", new Class<?>[]{int.class},
                    invoke(controller, "queueAbsolute", new Class<?>[]{int.class}, 25));
            assertEquals(1025, host.prefs.getTtsLastCharPosition());
            assertEquals(host.signature, host.prefs.getTtsLastContentSignature());
            assertEquals(true, invoke(controller, "hasResumeStateForCurrentFile", new Class<?>[0]));
            host.signature = "changed-transform";
            assertEquals(false, invoke(controller, "hasResumeStateForCurrentFile", new Class<?>[0]));
            assertEquals("", host.prefs.getTtsLastFilePath());
        });
    }

    @Test public void importedManualEncodingInvalidatesAppliedContentIdentity() {
        onMain(() -> {
            FakeReader activity = new FakeReader();
            File file = null;
            try {
                file = File.createTempFile("txt-policy-", ".txt", context().getCacheDir());
                activity.prefs = PrefsManager.getInstance(context());
                activity.filePath = file.getAbsolutePath();
                ReaderReloadController controller = new ReaderReloadController(activity);
                String auto = controller.currentTextDisplayRuleSignature();
                activity.prefs.setManualTextEncodingForFile(file, "UTF-16LE");
                assertNotEquals(auto, controller.currentTextDisplayRuleSignature());
                activity.prefs.setManualTextEncodingForFile(file, null);
                assertEquals(auto, controller.currentTextDisplayRuleSignature());
            } catch (Exception failure) { throw new AssertionError(failure); }
            finally {
                if (file != null) {
                    activity.prefs.setManualTextEncodingForFile(file, null);
                    assertTrue(file.delete());
                }
                close(activity);
            }
        });
    }

    @Test public void pendingLoadIsNotRestartedFromOldViewportOnResume() {
        onMain(() -> {
            FakeReader activity = new FakeReader();
            try {
                activity.filePath = "/loading.txt";
                activity.readerView = reader("old viewport");
                activity.appliedTextDisplayRuleSignature = "";
                new ReaderReloadController(activity).maybeReloadForTextDisplayRuleChange();
                assertNull(activity.reload);
            } finally { close(activity); }
        });
    }

    @Test public void finalSaveAcceptsCurrentLoadedIdentityWithoutStartingAWorker() {
        withLoadedSession((activity, source) -> {
            activity.activityDestroyed = true; // onDestroy saves after cancelling work.
            ReaderState saved = new ReaderMemoryController(activity).captureReadingStateForPersistence();
            assertNotNull(saved);
            assertEquals(5, saved.getCharPosition());
            assertEquals(source.length(), saved.getFileLength());
            assertNull(activity.reload);
        });
    }

    @Test public void editedSourceCannotRetagOldCoordinatesAsANewDurableState() {
        withLoadedSession((activity, source) -> {
            long loadedLength = activity.contentsSourceLength;
            replaceFile(source, new byte[]{7});
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            assertNull(memory.captureReadingStateForPersistence());
            ReaderState transientTarget = memory.captureReadingStateForRecreation();
            assertEquals(5, transientTarget.getCharPosition());
            assertEquals(loadedLength, transientTarget.getFileLength());
            assertNull("final save is not a reload admission path", activity.reload);
        });
    }

    @Test public void decodingPolicyChangeBlocksDurableSaveButKeepsRecreationTarget() {
        withLoadedSession((activity, source) -> {
            activity.prefs.setManualTextEncodingForFile(source, "UTF-16LE");
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            assertNull(memory.captureReadingStateForPersistence());
            assertEquals(5, memory.captureReadingStateForRecreation().getCharPosition());
            assertNull(activity.reload);
        });
    }

    @Test public void missingOrUnknownSourceIdentityCannotPassDurableSaveGate() {
        withLoadedSession((activity, source) -> {
            long modified = activity.contentsSourceModified;
            activity.contentsSourceModified = -1;
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            assertNull(memory.captureReadingStateForPersistence());
            activity.contentsSourceModified = modified;
            assertTrue(source.delete());
            assertNull(memory.captureReadingStateForPersistence());
        });
    }

    @Test public void editedSourceReloadKeepsNewestQueuedTargetAndClearsOldLaunchHints() {
        withLoadedSession((activity, source) -> {
            queueRemoteTarget(activity);
            replaceFile(source, new byte[]{7});
            assertTrue(new ReaderReloadController(activity).maybeReloadForPhysicallyEditedOriginalTxtFile());
            assertReloadTarget(activity, 3000, false);
        });
    }

    @Test public void policyReloadKeepsQueuedTargetEvenBeforeAdoptedLayoutIsReady() {
        withLoadedSession((activity, source) -> {
            queueRemoteTarget(activity);
            activity.textContentReadyForPersistence = false;
            activity.readerView.layout(0, 0, 0, 0);
            activity.prefs.setManualTextEncodingForFile(source, "UTF-16LE");
            new ReaderReloadController(activity).maybeReloadForTextDisplayRuleChange();
            assertReloadTarget(activity, 3000, false);
        });
    }

    @Test public void policyReloadKeepsVisibleRowForAutomaticHandoff() {
        withLoadedSession((activity, source) -> {
            activity.largeTextEstimateActive = true;
            activity.beginLargeTextPartitionSwitchPending(2, 20);
            activity.largeTextPartitionSwitchState.setPendingTargetChar(2000);
            activity.largeTextPartitionSwitchState.markViewportHandoff();
            activity.prefs.setManualTextEncodingForFile(source, "UTF-16LE");
            new ReaderReloadController(activity).maybeReloadForTextDisplayRuleChange();
            assertReloadTarget(activity, 5, true);
        });
    }

    @Test public void partitionPolicyChangeCapturesQueueBeforeItIsCleared() {
        withLoadedSession((activity, source) -> {
            queueRemoteTarget(activity);
            activity.appliedLargeTextPartitionMode = activity.getLargeTextPartitionMode() == 0 ? 1 : 0;
            int previousMode = activity.appliedLargeTextPartitionMode;
            assertTrue(new ReaderReloadController(activity).maybeReloadForLargeTextPartitionModeChange());
            assertNotNull(activity.reload);
            assertEquals(3000, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
            assertFalse(activity.reload.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE));
            assertFalse(activity.reload.hasExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE));
            assertFalse(activity.reload.hasExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES));
            assertFalse(activity.reload.getBooleanExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION, false));
            assertEquals("requesting a reload must not certify an old window", previousMode,
                    activity.appliedLargeTextPartitionMode);
        });
    }

    @Test public void trimWithStaleTextRetainsContextButDropsPagesWithoutSavingIt() {
        withLoadedSession((activity, source) -> {
            replaceFile(source, new byte[]{7});
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            // No BookmarkManager: attempting a durable write here would fail.
            memory.trimReaderMemoryForBackground(true);
            assertTrue(activity.backgroundTextMemoryReleased);
            assertEquals(5, activity.backgroundTextRestoreIntent.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
            assertEquals("alpha", activity.backgroundTextRestoreIntent.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE));
            assertNoPageHints(activity.backgroundTextRestoreIntent);
            assertTrue(memory.restoreReaderAfterBackgroundMemoryTrimIfNeeded());
            assertReloadTarget(activity, 5, true);
        });
    }

    @Test public void sourceOrPolicyChangeAfterTrimInvalidatesRestorePageHints() {
        withLoadedSession((activity, source) -> {
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            ReaderState target = memory.captureReadingState();
            activity.backgroundTextRestoreIntent = ReaderMemoryController.backgroundRestoreIntent(activity.getIntent(), target);
            activity.backgroundTextMemoryReleased = true;
            activity.prefs.setManualTextEncodingForFile(source, "UTF-16LE");
            assertTrue(memory.restoreReaderAfterBackgroundMemoryTrimIfNeeded());
            assertReloadTarget(activity, 5, true);
        });
    }

    @Test public void uriLaunchTrimRestoresResolvedFileAndOwnedPosition() {
        withLoadedSession((activity, source) -> {
            activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_URI, "content://test/book"));
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            // Exercise real trim while autosave is disabled; no shared bookmarks touched.
            boolean autoSave = activity.prefs.getAutoSavePosition();
            try {
                activity.prefs.setAutoSavePosition(false);
                memory.trimReaderMemoryForBackground(true);
                assertTrue(activity.backgroundTextMemoryReleased);
                assertEquals("", activity.readerView.getTextContent());
                assertTrue(memory.restoreReaderAfterBackgroundMemoryTrimIfNeeded());
                assertEquals(source.getAbsolutePath(), activity.reload.getStringExtra(ReaderActivity.EXTRA_FILE_PATH));
                assertEquals("content://test/book", activity.reload.getStringExtra(ReaderActivity.EXTRA_FILE_URI));
                assertEquals(5, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
                assertFalse(activity.backgroundTextMemoryReleased);
            } finally { activity.prefs.setAutoSavePosition(autoSave); }
        });
    }

    @Test public void uriRestoreCannotReopenOldFileAfterLaunchTargetChanges() {
        withLoadedSession((activity, source) -> {
            activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_URI, "content://test/old"));
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            activity.backgroundTextRestoreIntent = ReaderMemoryController.backgroundRestoreIntent(
                    activity.getIntent(), memory.captureReadingState());
            activity.backgroundTextMemoryReleased = true;
            activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_URI, "content://test/new"));
            assertFalse(memory.restoreReaderAfterBackgroundMemoryTrimIfNeeded());
            assertNull(activity.reload);
            assertNull(activity.backgroundTextRestoreIntent);
        });
    }

    @Test public void unchangedTrimIdentityRetainsItsKnownPageHints() {
        withLoadedSession((activity, source) -> {
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            activity.backgroundTextRestoreIntent = ReaderMemoryController.backgroundRestoreIntent(
                    activity.getIntent(), memory.captureReadingState());
            activity.backgroundTextMemoryReleased = true;
            assertTrue(memory.restoreReaderAfterBackgroundMemoryTrimIfNeeded());
            assertEquals(1, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, -1));
            assertEquals(20, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, -1));
        });
    }

    @Test public void unappliedRenderingSettingsKeepCharactersButInvalidateSavedPageHints() {
        withLoadedSession((activity, source) -> {
            activity.layoutPreferencesCurrent = false;
            ReaderState saved = new ReaderMemoryController(activity).captureReadingStateForPersistence();
            assertNotNull(saved);
            assertEquals(5, saved.getCharPosition());
            assertEquals("alpha", saved.getAnchorTextBefore());
            assertEquals(source.length(), saved.getFileLength());
            assertEquals(0, saved.getPageNumber());
            assertEquals(0, saved.getTotalPages());
            assertEquals(0, saved.getScrollY());
            assertNotEquals(activity.readerPageLayoutSignatureForPath(activity.filePath), saved.getPresentationSignature());
            activity.layoutPreferencesCurrent = true;
            ReaderState applied = new ReaderMemoryController(activity).captureReadingStateForPersistence();
            assertEquals(1, applied.getPageNumber());
            assertEquals(20, applied.getTotalPages());
            assertEquals(activity.readerPageLayoutSignatureForPath(activity.filePath), applied.getPresentationSignature());
        });
    }

    @Test public void pageHintSignatureIncludesOverlapWithoutChangingTextIdentity() {
        withLoadedSession((activity, source) -> {
            int original = activity.prefs.getPagingOverlapLines();
            String pageSignature = activity.readerPageLayoutSignatureForPath(activity.filePath);
            String textSignature = activity.currentTextDisplayRuleSignature();
            try {
                activity.prefs.setPagingOverlapLines(original == 0 ? 1 : 0);
                assertNotEquals(pageSignature, activity.readerPageLayoutSignatureForPath(activity.filePath));
                assertEquals(textSignature, activity.currentTextDisplayRuleSignature());
            } finally { activity.prefs.setPagingOverlapLines(original); }
        });
    }

    @Test public void appliedPreferenceOwnershipRejectsChangedFontMarginAndReplacementView() {
        withLoadedSession((activity, source) -> {
            ReaderPreferencesController controller = new ReaderPreferencesController(activity);
            assertFalse(controller.hasCurrentLayoutPreferences());
            set(controller, "appliedLayoutView", activity.readerView);
            set(controller, "appliedLayoutPreferencesSignature", activity.readerLayoutPreferencesSignature());
            assertTrue(controller.hasCurrentLayoutPreferences());
            activity.largeTextEstimateActive = true;
            activity.appliedLargeTextPartitionMode = Integer.MIN_VALUE;
            assertFalse(controller.hasCurrentLayoutPreferences());
            activity.appliedLargeTextPartitionMode = activity.getLargeTextPartitionMode();
            assertTrue(controller.hasCurrentLayoutPreferences());
            activity.largeTextEstimateActive = false;
            int originalMargin = activity.prefs.getMarginHorizontal();
            String originalFont = activity.prefs.getFontFamily();
            CustomReaderView originalView = activity.readerView;
            try {
                activity.prefs.setMarginHorizontal(originalMargin == 24 ? 25 : 24);
                assertFalse(controller.hasCurrentLayoutPreferences());
                activity.prefs.setMarginHorizontal(originalMargin);
                assertTrue(controller.hasCurrentLayoutPreferences());
                activity.prefs.setFontFamily("different-font-policy");
                assertFalse(controller.hasCurrentLayoutPreferences());
                activity.prefs.setFontFamily(originalFont);
                assertTrue(controller.hasCurrentLayoutPreferences());
                activity.readerView = reader("same settings, another view");
                assertFalse(controller.hasCurrentLayoutPreferences());
            } finally {
                activity.prefs.setMarginHorizontal(originalMargin);
                activity.prefs.setFontFamily(originalFont);
                if (activity.readerView != originalView) activity.readerView.releaseTextResources();
                activity.readerView = originalView;
            }
        });
    }

    @Test public void renderingChangeAfterTrimDiscardsOldHintsEvenAfterNewSettingsAreApplied() {
        withLoadedSession((activity, source) -> {
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            activity.backgroundTextRestoreIntent = ReaderMemoryController.backgroundRestoreIntent(
                    activity.getIntent(), memory.captureReadingState());
            activity.backgroundTextMemoryReleased = true;
            int original = activity.prefs.getPagingOverlapLines();
            try {
                activity.prefs.setPagingOverlapLines(original == 0 ? 1 : 0);
                activity.layoutPreferencesCurrent = true; // onResume applied the new geometry.
                assertTrue(activity.loadedTextMatchesCurrentSourceAndTransform());
                assertTrue(memory.restoreReaderAfterBackgroundMemoryTrimIfNeeded());
                assertReloadTarget(activity, 5, true);
            } finally { activity.prefs.setPagingOverlapLines(original); }
        });
    }

    @Test public void unchangedSignatureDoesNotCertifyUnappliedGeometryOnTrimRestore() {
        withLoadedSession((activity, source) -> {
            ReaderMemoryController memory = new ReaderMemoryController(activity);
            activity.backgroundTextRestoreIntent = ReaderMemoryController.backgroundRestoreIntent(
                    activity.getIntent(), memory.captureReadingState());
            activity.backgroundTextMemoryReleased = true;
            activity.layoutPreferencesCurrent = false;
            assertTrue(memory.restoreReaderAfterBackgroundMemoryTrimIfNeeded());
            assertReloadTarget(activity, 5, true);
        });
    }

    @Test public void trimBeforeApplyingRestoredSettingsCannotCarryKnownPageHints() {
        withLoadedSession((activity, source) -> {
            boolean original = activity.prefs.getAutoSavePosition();
            try {
                activity.prefs.setAutoSavePosition(false);
                activity.layoutPreferencesCurrent = false;
                ReaderMemoryController memory = new ReaderMemoryController(activity);
                memory.trimReaderMemoryForBackground(true);
                assertEquals(5, activity.backgroundTextRestoreIntent.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
                assertEquals(0, activity.backgroundTextRestoreIntent.getIntExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, -1));
                assertEquals(0, activity.backgroundTextRestoreIntent.getIntExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, -1));
                activity.layoutPreferencesCurrent = true;
                assertTrue(memory.restoreReaderAfterBackgroundMemoryTrimIfNeeded());
                assertReloadTarget(activity, 5, true);
            } finally { activity.prefs.setAutoSavePosition(original); }
        });
    }

    private interface LoadedSessionCheck { void run(FakeReader activity, File source); }
    private static void withLoadedSession(LoadedSessionCheck check) {
        onMain(() -> {
            FakeReader activity = new FakeReader();
            File source = null;
            try {
                source = File.createTempFile("txt-save-owner-", ".txt", context().getCacheDir());
                replaceFile(source, new byte[]{1, 2, 3});
                activity.prefs = PrefsManager.getInstance(context());
                activity.filePath = source.getAbsolutePath();
                activity.readerView = reader("alpha beta gamma");
                activity.fileContent = activity.readerView.getTextContent();
                activity.textContentReadyForPersistence = true;
                activity.contentsSourceLength = source.length();
                activity.contentsSourceModified = source.lastModified();
                activity.appliedTextDisplayRuleSignature = activity.currentTextDisplayRuleSignature();
                activity.setIntent(new Intent().putExtra(ReaderActivity.EXTRA_FILE_PATH, activity.filePath)
                        .putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, "old launch anchor")
                        .putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, "old launch suffix")
                        .putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, 99)
                        .putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE, 999L)
                        .putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, 7)
                        .putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, 99));
                check.run(activity, source);
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            finally {
                if (source != null) {
                    if (activity.prefs != null) activity.prefs.setManualTextEncodingForFile(source, null);
                    if (source.exists()) assertTrue(source.delete());
                }
                activity.clearLoadedTextSnapshot();
                close(activity);
            }
        });
    }
    private static void replaceFile(File source, byte[] bytes) {
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(bytes); }
        catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    private static void queueRemoteTarget(FakeReader activity) {
        activity.largeTextEstimateActive = true;
        activity.beginLargeTextPartitionSwitchPending(2, 20);
        activity.largeTextPartitionSwitchState.setPendingTargetChar(2000);
        activity.largeTextPartitionSwitchState.queueTarget(3000, 3, 20);
    }
    private static void assertNoPageHints(Intent target) {
        assertFalse(target.hasExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE));
        assertFalse(target.hasExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES));
        assertTrue(target.getBooleanExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION, false));
    }
    private static void assertReloadTarget(FakeReader activity, int position, boolean withContext) {
        assertNotNull(activity.reload);
        assertEquals(position, activity.reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
        assertEquals(withContext ? "alpha" : null,
                activity.reload.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE));
        String after = activity.reload.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER);
        assertTrue(after == null || after.isEmpty());
        assertFalse(activity.reload.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE));
        assertFalse(activity.reload.hasExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE));
        assertNoPageHints(activity.reload);
    }

    @Test public void legacyRelativeTtsCheckpointIsNotReinterpretedAsAbsolute() {
        withTts((controller, host) -> {
            host.prefs.setTtsLastPlaybackState(host.path, 25, 8, true, 0);
            assertEquals(false, invoke(controller, "hasResumeStateForCurrentFile", new Class<?>[0]));
            host.signature = ""; // Other document hosts retain their legacy migration path.
            host.prefs.setTtsLastPlaybackState(host.path, 25, 8, true, 0);
            assertEquals(true, invoke(controller, "hasResumeStateForCurrentFile", new Class<?>[0]));
        });
    }

    @Test public void ttsHighlightMapsToTheCurrentWindowOrClearsIt() {
        withTts((controller, host) -> {
            queue(controller, host, 1000, 100);
            host.base = 1010;
            host.source.text = "0123456789";
            invoke(controller, "highlightQueueSegment", new Class<?>[]{TtsTextSource.class, TtsSpeechSegment.class},
                    host.source, new TtsSpeechSegment(12, 15, "234"));
            assertEquals(2, host.source.start);
            assertEquals(5, host.source.end);
            host.base = 4000;
            invoke(controller, "highlightQueueSegment", new Class<?>[]{TtsTextSource.class, TtsSpeechSegment.class},
                    host.source, new TtsSpeechSegment(12, 15, "234"));
            assertEquals(-1, host.source.start);
        });
    }

    @Test public void ttsContinuationUsesConsumedEndNotOverlappingDisplayPage() {
        withTts((controller, host) -> {
            queue(controller, host, 1000, 100);
            host.base = 5000; // User scrolled away while the immutable queue was speaking.
            invoke(controller, "advanceAndSpeakNextPage", new Class<?>[]{int.class}, 0);
            assertEquals(1100, host.jump);
            assertEquals(0, host.pageTurns);
            assertEquals(1100, field(controller, "nextSpeechChar"));
        });
    }

    private static void queue(ReaderTtsController controller, FakeHost host, int base, int limit) {
        set(controller, "queueBaseChar", base);
        set(controller, "queueTextLimit", limit);
        set(controller, "queuePath", host.path);
        set(controller, "queueSignature", host.signature);
    }
    private interface TtsCheck { void run(ReaderTtsController controller, FakeHost host); }
    private static void withTts(TtsCheck check) {
        onMain(() -> {
            FakeHost host = new FakeHost();
            Map<String, ?> before = new HashMap<>(host.prefs.getPrefs().getAll());
            try { check.run(new ReaderTtsController(host), host); }
            finally {
                host.handler.removeCallbacksAndMessages(null);
                SharedPreferences.Editor edit = host.prefs.getPrefs().edit();
                for (String key : host.prefs.getPrefs().getAll().keySet()) if (key.startsWith("tts_last_")) edit.remove(key);
                for (Map.Entry<String, ?> entry : before.entrySet()) if (entry.getKey().startsWith("tts_last_")) {
                    Object value = entry.getValue(); String key = entry.getKey();
                    if (value instanceof String) edit.putString(key, (String) value);
                    else if (value instanceof Integer) edit.putInt(key, (Integer) value);
                    else if (value instanceof Long) edit.putLong(key, (Long) value);
                    else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                }
                edit.commit(); close(host.activity);
            }
        });
    }
    private static final class FakeSource implements TtsTextSource {
        String text = "0123456789"; int start = -1, end = -1;
        public String getTextContent() { return text; }
        public int getCurrentCharPosition() { return 0; }
        public int getCharPositionAfterCurrentVisibleContent() { return text.length(); }
        public void setTtsHighlightRange(int s, int e) { start = s; end = e; }
        public void clearTtsHighlight() { start = end = -1; }
    }
    private static final class FakeHost implements TtsHost {
        final FakeReader activity = new FakeReader();
        final PrefsManager prefs = PrefsManager.getInstance(context());
        final Handler handler = new Handler(android.os.Looper.getMainLooper());
        final FakeSource source = new FakeSource();
        String path = "/tts-session.txt", signature = "txt-absolute-v1|test";
        int base, jump = -1, pageTurns;
        public AppCompatActivity ttsHostActivity() { return activity; }
        public PrefsManager ttsHostPrefs() { return prefs; }
        public ReaderDialogStyleController ttsHostDialogStyler() { return null; }
        public int ttsHostDpToPx(int dp) { return dp; }
        public TtsTextSource ttsTextSource() { return source; }
        public boolean isTtsHostDestroyed() { return false; }
        public String ttsHostFilePath() { return path; }
        public Handler ttsHostHandler() { return handler; }
        public boolean isTtsTextFullyResident() { return false; }
        public boolean isTtsTextTemporarilyUnavailable() { return false; }
        public int ttsDisplayedCurrentPageNumber() { return 1; }
        public int ttsDisplayedTotalPageCount() { return 1; } // Estimate is NOT an EOF limit.
        public int ttsCurrentCharPosition() { return base; }
        public int ttsTextBaseCharPosition() { return base; }
        public boolean ttsUsesAbsoluteTextContinuation() { return true; }
        public boolean ttsHasTextAfter(int position) { return position < 9000; }
        public String ttsCheckpointSignature() { return signature; }
        public void ttsHostPageBy(int direction) { pageTurns++; }
        public void ttsJumpToAbsoluteCharPosition(int position) { jump = position; }
        public void ttsJumpToAbsoluteCharPosition(int position, int page, int total) { jump = position; }
        public void ttsUpdateFloatingCard() { }
        public void ttsStopAutoPageTurn() { }
        public void ttsHandlePlaybackCommand(String action) { }
        public String ttsHostKind() { return TtsPlaybackService.HOST_READER; }
    }
    private static final class FakeReader extends ReaderActivity {
        Intent reload; int cancellations, landedAt, partitionReloadTarget = -1;
        boolean layoutPreferencesCurrent = true;
        @Override public Context getApplicationContext() { return context(); }
        @Override public Resources getResources() { return context().getResources(); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return context().getSharedPreferences(name, mode); }
        @Override void loadFileFromIntent(Intent intent) { reload = intent; loadGeneration.incrementAndGet(); }
        @Override int getBookmarkSaveCharPosition() { return 5; }
        @Override String getAnchorTextBefore(int position) { return "alpha"; }
        @Override String getAnchorTextAfter(int position) { return ""; }
        @Override void updatePositionLabel() { }
        @Override void applySearchHighlight() { }
        @Override int getDisplayedTotalPageCount() { return 20; }
        @Override int getStatusCurrentPageNumber() { return 1; }
        @Override boolean hasCurrentReaderLayoutPreferences() { return layoutPreferencesCurrent; }
        @Override boolean isLargeTextExactPageIndexReady() { return false; }
        @Override void scheduleLargeTextExactPageIndexingRestart() { }
        @Override void reloadLargeTextPreviewAround(int position, int page, int total,
                String before, String after, int startLine, boolean showLoading) { partitionReloadTarget = position; }
        @Override int estimateDisplayedPageForLargeTextLine(int line, int total) { return 1; }
        @Override SearchOptions currentSearchOptions() { return SearchOptions.literal(); }
        @Override int countLinesUntilChar(int position) { return 1; }
        @Override void cancelLargeTextPartitionSwitchForLocalJump() {
            cancellations++; largeTextPartitionSwitchGeneration.incrementAndGet(); clearLargeTextPartitionSwitchPending();
        }
        @Override void scrollToSearchResultPosition(int position) { landedAt = position; }
    }
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static void onMain(Runnable check) { InstrumentationRegistry.getInstrumentation().runOnMainSync(check); }
    private static CustomReaderView reader(String text) {
        CustomReaderView view = new CustomReaderView(context());
        view.setReaderStyle(18, 1.35f, Color.BLACK, Color.WHITE, 24, 16, Typeface.DEFAULT);
        view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 720, 960); view.setTextContent(text); return view;
    }
    private static void close(FakeReader activity) {
        if (activity.readerView != null) activity.readerView.releaseTextResources();
        activity.handler.removeCallbacksAndMessages(null); activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow(); activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
    }
    private static Field declared(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object field(Object object, String name) {
        try { return declared(object, name).get(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void set(Object object, String name, Object value) {
        try { declared(object, name).set(object, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) {
        try { Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, args); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
