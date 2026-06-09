package com.textview.reader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.adapter.BookmarkFolderAdapter;
import com.textview.reader.model.Bookmark;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the TXT bookmark manager UI.  ReaderActivity keeps the legacy bookmark
 * restore/save/navigation logic, while this controller owns the rounded dialog
 * layout and list interactions.
 */
final class ReaderBookmarkDialogController {
    private static final int TXT_BOOKMARK_POPUP_Y_DP = 34;

    private final ReaderActivity activity;

    ReaderBookmarkDialogController(ReaderActivity activity) {
        this.activity = activity;
    }

void showBookmarksForFile() {
    if (activity.filePath == null || activity.fileContent == null) {
        ShortToast.show(activity, activity.getString(R.string.file_not_loaded));
        return;
    }

    final int bg = activity.dialogStyler().readerDialogBgColor();
    final int panel = activity.dialogStyler().readerDialogPanelColor();
    final int fg = activity.dialogStyler().readerDialogTextColor(bg);
    final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setBackgroundColor(Color.TRANSPARENT);
    int pad = activity.dpToPx(12);
    // Keep content slightly inset inside the rounded outer frame.
    box.setPadding(pad, pad, pad, activity.dpToPx(6));

    TextView currentInfo = new TextView(activity);
    currentInfo.setTextColor(sub);
    currentInfo.setTextSize(12f);
    currentInfo.setGravity(Gravity.CENTER);
    currentInfo.setSingleLine(false);
    currentInfo.setIncludeFontPadding(false);
    currentInfo.setLineSpacing(0f, 1.08f);
    currentInfo.setPadding(0, 0, 0, activity.dpToPx(8));
    box.addView(currentInfo, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
    TextView hintButton = new TextView(activity);
    hintButton.setText(activity.getString(R.string.bookmark_hints_show));
    hintButton.setContentDescription(activity.getString(R.string.bookmark_hints_show));
    hintButton.setTextColor(sub);
    hintButton.setTextSize(12f);
    hintButton.setTypeface(Typeface.DEFAULT_BOLD);
    hintButton.setGravity(Gravity.CENTER);
    hintButton.setPadding(0, activity.dpToPx(6), 0, activity.dpToPx(4));
    hintButton.setOnClickListener(v -> new ReaderBookmarkHintsDialogController(activity).show());
    box.addView(hintButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

    TextView saveButton = new TextView(activity);
    saveButton.setText(activity.getString(R.string.add_current_bookmark));
    saveButton.setGravity(Gravity.CENTER);
    saveButton.setTextColor(fg);
    saveButton.setTextSize(16f);
    saveButton.setTypeface(Typeface.DEFAULT_BOLD);
    saveButton.setPadding(0, activity.dpToPx(12), 0, activity.dpToPx(12));
    GradientDrawable saveBg = new GradientDrawable();
    boolean darkBookmarkDialog = activity.isDarkColor(bg);
    int saveFill = activity.dialogStyler().blendColors(bg, fg, darkBookmarkDialog ? 0.135f : 0.085f);
    int saveStroke = activity.dialogStyler().blendColors(bg, fg, darkBookmarkDialog ? 0.460f : 0.360f);
    saveBg.setColor(saveFill);
    saveBg.setCornerRadius(activity.dpToPx(14));
    saveBg.setStroke(Math.max(1, activity.dpToPx(1)), saveStroke);
    saveButton.setBackground(saveBg);
    LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    saveLp.setMargins(0, activity.dpToPx(8), 0, activity.dpToPx(2));

    TextView emptyText = new TextView(activity);
    emptyText.setText(activity.getString(R.string.no_bookmarks_hint));
    emptyText.setTextColor(sub);
    emptyText.setGravity(Gravity.CENTER);
    emptyText.setTextSize(14f);
    emptyText.setPadding(0, activity.dpToPx(18), 0, activity.dpToPx(18));

    RecyclerView rv = new RecyclerView(activity);
    rv.setBackgroundColor(Color.TRANSPARENT);
    rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
    rv.setLayoutManager(new LinearLayoutManager(activity));
    // Keep bookmark folder expand/collapse immediate instead of the slow default item animation.
    rv.setItemAnimator(null);

    BookmarkFolderAdapter adapter = new BookmarkFolderAdapter();
    adapter.setThemeColors(bg, fg, sub, panel);
    Set<String> expandedFolders = new HashSet<>();
    expandedFolders.add(activity.filePath); // current file starts expanded

    rv.setAdapter(adapter);

    box.addView(emptyText, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

    LinearLayout.LayoutParams bookmarkListLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            activity.dpToPx(430));
    box.addView(rv, bookmarkListLp);
    box.addView(saveButton, saveLp);

    final android.app.Dialog[] dialogRef = new android.app.Dialog[1];

    Runnable refresh = () -> {
        activity.syncCurrentFileBookmarksToLargeTextExactPageModel();
        adapter.setCurrentPageLayoutSignature(activity.buildCurrentLargeTextBookmarkPageSignature());
        List<Bookmark> allBookmarks = activity.bookmarkManager.getAllBookmarks();
        adapter.setBookmarks(allBookmarks, expandedFolders, activity.filePath);
        // Keep the bookmark dialog height stable even when the list is empty.
        // This prevents the window from bouncing when the first bookmark is added.
        emptyText.setVisibility(View.GONE);
        rv.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams rvLp = (LinearLayout.LayoutParams) rv.getLayoutParams();
        if (rvLp != null && rvLp.height != activity.dpToPx(430)) {
            rvLp.height = activity.dpToPx(430);
            rv.setLayoutParams(rvLp);
        }
        currentInfo.setText(activity.getString(R.string.all_bookmarks_status,
                adapter.getFolderCount(),
                allBookmarks.size(),
                activity.getDisplayedCurrentPageNumber(),
                activity.getDisplayedTotalPageCount()));

        // When the first bookmark is added, RecyclerView/content height can change.
        // Redraw the outer rounded overlay after the layout settles so the bottom
        // rounded border does not disappear until reopening the dialog.
        box.requestLayout();
        rv.requestLayout();
        if (dialogRef[0] != null) {
            activity.dialogStyler().redrawDialogOuterBorder(dialogRef[0], activity.dialogStyler().strongDialogBorderColor(bg));
        }
    };

    TextView title = activity.dialogStyler().makeReaderDialogTitle(activity.getString(R.string.bookmark), bg, fg);
    title.setGravity(Gravity.CENTER);
    title.setIncludeFontPadding(false);
    title.setPadding(activity.dpToPx(22), activity.dpToPx(12), activity.dpToPx(22), 0);
    LinearLayout dialogPanel = new LinearLayout(activity);
    dialogPanel.setOrientation(LinearLayout.VERTICAL);
    dialogPanel.setBackgroundColor(Color.TRANSPARENT);
    dialogPanel.addView(title, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
    dialogPanel.addView(box, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

    TextView closeButton = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.close), sub, Gravity.CENTER);
    dialogPanel.addView(closeButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            activity.dpToPx(50)));

    // Populate bookmark contents before show() so the bottom-positioned window
    // is measured once at its final height. Showing a temporary loading state
    // first and then replacing it with the real two-line status makes the
    // bookmark window visibly hard-drop on some devices.
    refresh.run();

    android.app.Dialog dialog = activity.dialogStyler().createPositionedReaderDialog(dialogPanel, bg,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, TXT_BOOKMARK_POPUP_Y_DP, 14, 460, false);
    dialogRef[0] = dialog;
    closeButton.setOnClickListener(v -> dialog.dismiss());

    saveButton.setOnClickListener(v -> activity.saveCurrentBookmarkTekStyle(() -> {
        expandedFolders.add(activity.filePath);
        refresh.run();
    }));

    adapter.setListener(new BookmarkFolderAdapter.Listener() {
        @Override
        public void onFolderClick(String folderFilePath) {
            if (expandedFolders.contains(folderFilePath)) {
                expandedFolders.remove(folderFilePath);
            } else {
                expandedFolders.add(folderFilePath);
            }
            refresh.run();
        }

        @Override
        public void onFolderDelete(String folderFilePath, String expansionKey, String folderName, int bookmarkCount) {
            new ReaderBookmarkFolderDeleteDialogController(activity).show(folderFilePath, folderName, bookmarkCount, () -> {
                expandedFolders.remove(folderFilePath);
                expandedFolders.remove(expansionKey);
                refresh.run();
            });
        }

        @Override
        public void onBookmarkClick(Bookmark b) {
            if (new ReaderBookmarkOpenController(activity).open(b)) {
                dialog.dismiss();
            }
        }

        @Override
        public void onBookmarkDelete(Bookmark b) {
            activity.dialogStyler().showBookmarkDeleteConfirm(b, refresh);
        }

        @Override
        public void onBookmarkEdit(Bookmark b) {
            new ReaderBookmarkMemoDialogController(activity).show(b, refresh);
        }
    });

    dialog.show();
}

}
