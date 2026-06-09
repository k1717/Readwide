package com.textview.reader;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;

import com.textview.reader.util.PrefsManager;

import java.io.File;

final class ReaderActionController {
    private final ReaderActivity activity;

    ReaderActionController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void openFileBrowserFromViewer() {
        Intent intent = new Intent(activity, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_RETURN_TO_VIEWER, true);
        File current = activity.filePath != null ? new File(activity.filePath) : null;
        File parent = current != null ? current.getParentFile() : null;
        if (parent != null && parent.exists() && parent.isDirectory()) {
            intent.putExtra(MainActivity.EXTRA_START_DIRECTORY, parent.getAbsolutePath());
        }
        activity.startActivity(intent);
    }

    void openHomeFromViewer() {
        Intent intent = new Intent(activity, MainActivity.class);
        activity.startActivity(intent);
        activity.finish();
    }

    void startAutoPageTurn() {
        enterBodyReadingModeForAutoPageTurn();
        if (activity.autoPageTurnController != null) activity.autoPageTurnController.start();
    }

    void stopAutoPageTurn(boolean showToast) {
        if (activity.autoPageTurnController != null) activity.autoPageTurnController.stop(showToast);
    }

    void stopAutoPageTurnForManualNavigation() {
        if (activity.autoPageTurnController != null) activity.autoPageTurnController.stopForManualNavigation();
    }

    void applyReaderBrightnessOverride(float brightness) {
        float clamped = Math.max(0f, Math.min(1f, brightness));
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = clamped;
        activity.getWindow().setAttributes(lp);
    }

    void clearReaderBrightnessOverride() {
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        activity.getWindow().setAttributes(lp);
    }

    boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) { activity.finish(); return true; }
        else if (id == R.id.action_add_bookmark) { activity.addBookmark(); return true; }
        else if (id == R.id.action_bookmarks) { activity.showBookmarksForFile(); return true; }
        else if (id == R.id.action_go_to) { activity.showGoToDialog(); return true; }
        else if (id == R.id.action_search) { activity.showTextSearch(); return true; }
        else if (id == R.id.action_brightness) { activity.showBrightnessDialog(); return true; }
        else if (id == R.id.action_font) { activity.showFontDialog(); return true; }
        else if (id == R.id.action_font_increase) { changeFontSize(2f); return true; }
        else if (id == R.id.action_font_decrease) { changeFontSize(-2f); return true; }
        else if (id == R.id.action_font_reset) { resetFontSize(); return true; }
        else if (id == R.id.action_file_info) { activity.showFileInfoDialog(); return true; }
        return false;
    }

    void changeFontSize(float delta) {
        float newSize = Math.max(8f, Math.min(48f, activity.prefs.getFontSize() + delta));
        activity.prefs.setFontSize(newSize);
        activity.applyPreferences();
        activity.updatePositionLabel();
    }

    void resetFontSize() {
        activity.prefs.setFontSize(PrefsManager.DEFAULT_FONT_SIZE);
        activity.applyPreferences();
        activity.updatePositionLabel();
    }

    private void enterBodyReadingModeForAutoPageTurn() {
        activity.toolbarVisible = false;
        if (activity.toolbar != null) activity.toolbar.setVisibility(View.GONE);
        if (activity.bottomBar != null) activity.bottomBar.setVisibility(View.GONE);
        if (activity.readerPageStatus != null) activity.readerPageStatus.setVisibility(View.VISIBLE);
        activity.updateReaderFileTitleVisibility();
        activity.updateBottomMenuBackground();
        activity.handler.post(() -> {
            if (activity.activityDestroyed) return;
            if (activity.readerRoot != null) ViewCompat.requestApplyInsets(activity.readerRoot);
            activity.updateNavigationBarForBottomMenu();
        });
    }
}
