package com.textview.reader;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileSortUtils;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class MainImageOpenController {
    private final MainActivity activity;
    private final LinkedHashSet<String> pendingLaunchHandoffTokens = new LinkedHashSet<>();
    private Dialog imageOpenLoadingDialog;

    MainImageOpenController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void attachDeferredImageViewerSequence(@NonNull Intent intent, @NonNull File selected) {
        final String selectedPath = selected.getAbsolutePath();
        final ArrayList<File> visibleSnapshot = activity.fileAdapter != null
                ? activity.fileAdapter.getFilesSnapshot()
                : null;
        final ArrayList<String> visiblePaths = buildVisibleImageResultPaths(selectedPath, visibleSnapshot);
        if (!visiblePaths.isEmpty()) {
            String token = ImageSequenceHandoffStore.put(() ->
                    new ImageSequenceHandoffStore.Sequence(visiblePaths, displayNamesFor(visiblePaths), null));
            intent.putExtra(ImageReaderActivity.EXTRA_SEQUENCE_HANDOFF_TOKEN, token);
            return;
        }

        final int sortMode = activity.prefs != null ? activity.prefs.getSortMode() : PrefsManager.SORT_NAME_ASC;
        final Context appContext = activity.getApplicationContext();

        String token = ImageSequenceHandoffStore.put(() -> {
            ArrayList<String> paths = buildImageSiblingPaths(appContext, selectedPath, sortMode);
            if (paths.isEmpty()) paths.add(selectedPath);
            return new ImageSequenceHandoffStore.Sequence(paths, displayNamesFor(paths), null);
        });
        intent.putExtra(ImageReaderActivity.EXTRA_SEQUENCE_HANDOFF_TOKEN, token);
    }

    @NonNull
    ArrayList<String> buildImageViewerPaths(@NonNull File selected) {
        ArrayList<String> visible = buildVisibleImageResultPaths(selected);
        return visible.isEmpty() ? buildImageSiblingPaths(selected) : visible;
    }

    @NonNull
    private ArrayList<String> buildVisibleImageResultPaths(@NonNull File selected) {
        ArrayList<File> snapshot = activity.fileAdapter != null ? activity.fileAdapter.getFilesSnapshot() : null;
        return buildVisibleImageResultPaths(selected.getAbsolutePath(), snapshot);
    }

    /**
     * Builds the image-viewer sequence from the currently displayed main-list order.
     *
     * This is intentionally based on the adapter snapshot rather than re-sorting the
     * parent directory. When a normal image is opened from the main file list, left/right
     * navigation should follow the folder/search/filter order the user is looking at.
     */
    @NonNull
    static ArrayList<String> buildVisibleImageResultPaths(@NonNull String selectedPath,
                                                          @Nullable ArrayList<File> snapshot) {
        if (snapshot == null) return new ArrayList<>();
        LinkedHashSet<String> ordered = new LinkedHashSet<>();

        boolean containsSelected = false;
        for (File file : snapshot) {
            if (file == null || !file.isFile() || !FileUtils.isImageFile(file.getName())) continue;
            String path = file.getAbsolutePath();
            if (selectedPath.equals(path)) containsSelected = true;
            ordered.add(path);
        }

        if (!containsSelected) return new ArrayList<>();
        return new ArrayList<>(ordered);
    }

    @NonNull
    ArrayList<String> buildImageSiblingPaths(@NonNull File selected) {
        int sortMode = activity.prefs != null ? activity.prefs.getSortMode() : PrefsManager.SORT_NAME_ASC;
        return buildImageSiblingPaths(activity, selected.getAbsolutePath(), sortMode);
    }

    @NonNull
    private static ArrayList<String> buildImageSiblingPaths(@NonNull Context context,
                                                            @NonNull String selectedPath,
                                                            int sortMode) {
        ArrayList<String> paths = new ArrayList<>();
        File selected = new File(selectedPath);
        File parent = selected.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory() || !parent.canRead()) {
            paths.add(selected.getAbsolutePath());
            return paths;
        }
        File[] children = parent.listFiles();
        if (children == null) {
            paths.add(selected.getAbsolutePath());
            return paths;
        }
        List<File> images = new ArrayList<>();
        for (File child : children) {
            if (child != null && child.isFile() && FileUtils.isImageFile(child.getName())) {
                images.add(child);
            }
        }
        FileSortUtils.sortMainFiles(context, images, sortMode);
        for (File image : images) paths.add(image.getAbsolutePath());
        if (!paths.contains(selected.getAbsolutePath())) paths.add(selected.getAbsolutePath());
        return paths;
    }

    @NonNull
    private static ArrayList<String> displayNamesFor(@NonNull ArrayList<String> paths) {
        ArrayList<String> names = new ArrayList<>(paths.size());
        for (String path : paths) names.add(FileUtils.normalizeDisplayFileName(new File(path).getName()));
        return names;
    }

    void startWithLoading(@NonNull Intent intent) {
        showImageOpenLoadingWindow();
        final String handoffToken = intent.getStringExtra(ImageReaderActivity.EXTRA_SEQUENCE_HANDOFF_TOKEN);
        trackPendingHandoffToken(handoffToken);
        activity.fileSearchHandler.postDelayed(() -> {
            try {
                if (activity.activityDestroyed || activity.isFinishing()) {
                    ImageSequenceHandoffStore.discard(handoffToken);
                    return;
                }
                activity.startActivity(intent);
                activity.overridePendingTransition(R.anim.image_viewer_enter, R.anim.image_viewer_hold);
                activity.finishIfReturnToViewerMode();
            } catch (RuntimeException e) {
                ImageSequenceHandoffStore.discard(handoffToken);
                ShortToast.show(activity, R.string.image_open_failed);
            } finally {
                untrackPendingHandoffToken(handoffToken);
                hideImageOpenLoadingWindow();
            }
        }, 90L);
    }

    void onDestroy() {
        LinkedHashSet<String> tokens;
        synchronized (pendingLaunchHandoffTokens) {
            tokens = new LinkedHashSet<>(pendingLaunchHandoffTokens);
            pendingLaunchHandoffTokens.clear();
        }
        for (String token : tokens) {
            ImageSequenceHandoffStore.discard(token);
        }
        hideImageOpenLoadingWindow();
    }

    private void trackPendingHandoffToken(@Nullable String token) {
        if (token == null || token.trim().isEmpty()) return;
        synchronized (pendingLaunchHandoffTokens) {
            pendingLaunchHandoffTokens.add(token);
        }
    }

    private void untrackPendingHandoffToken(@Nullable String token) {
        if (token == null || token.trim().isEmpty()) return;
        synchronized (pendingLaunchHandoffTokens) {
            pendingLaunchHandoffTokens.remove(token);
        }
    }

    void showImageOpenLoadingWindow() {
        hideImageOpenLoadingWindow();
        final boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        final int bg = activity.prefs != null ? activity.prefs.getMainBgColor(activity) : (dark ? Color.rgb(33, 33, 33) : Color.WHITE);
        final int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int line = activity.prefs != null ? activity.prefs.getMainOutlineColor(activity) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));
        final int panel = UiColorUtils.blendColors(bg, fg, UiColorUtils.isLightColor(bg) ? 0.05f : 0.08f);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setMinimumWidth(activity.dpToPx(116));
        box.setMinimumHeight(activity.dpToPx(112));
        box.setPadding(activity.dpToPx(20), activity.dpToPx(22), activity.dpToPx(20), activity.dpToPx(20));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(panel);
        shape.setCornerRadius(activity.dpToPx(24));
        shape.setStroke(Math.max(1, activity.dpToPx(1)), line);
        box.setBackground(shape);

        ProgressBar spinner = new ProgressBar(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            spinner.setIndeterminateTintList(ColorStateList.valueOf(fg));
        }
        box.addView(spinner, new LinearLayout.LayoutParams(activity.dpToPx(54), activity.dpToPx(54)));

        TextView label = new TextView(activity);
        label.setText(R.string.loading);
        label.setTextColor(fg);
        label.setTextSize(15f);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.setMargins(0, activity.dpToPx(10), 0, 0);
        box.addView(label, labelLp);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        dialog.setCancelable(false);
        imageOpenLoadingDialog = dialog;
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    void hideImageOpenLoadingWindow() {
        if (imageOpenLoadingDialog != null) {
            try {
                if (imageOpenLoadingDialog.isShowing()) imageOpenLoadingDialog.dismiss();
            } catch (Exception ignored) {}
            imageOpenLoadingDialog = null;
        }
    }
}
