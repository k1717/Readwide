package com.textview.reader;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.adapter.BookmarkFolderAdapter;
import com.textview.reader.model.Bookmark;
import com.textview.reader.util.FileUtils;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PdfBookmarkDialogController {
    private final PdfReaderActivity activity;

    PdfBookmarkDialogController(@NonNull PdfReaderActivity activity) {
        this.activity = activity;
    }

private void addBookmarkForCurrentPage() {
    if (activity.filePath == null || activity.pageCount <= 0) {
        ShortToast.show(activity, activity.getString(R.string.file_not_loaded));
        return;
    }

    List<Bookmark> existing = activity.bookmarkManager.getBookmarksForFile(activity.filePath);
    for (Bookmark b : existing) {
        if (b.getCharPosition() == activity.currentPage) {
            b.setLineNumber(activity.currentPage + 1);
            b.setPageNumber(activity.currentPage + 1);
            b.setTotalPages(activity.pageCount);
            b.setExcerpt(pageLabel(activity.currentPage));
            b.setEndPosition(activity.currentPage);
            activity.bookmarkManager.updateBookmark(b);
            ShortToast.show(activity, activity.getString(R.string.bookmark_updated));
            return;
        }
    }

    Bookmark bookmark = new Bookmark(activity.filePath, activity.fileName, activity.currentPage, activity.currentPage + 1, pageLabel(activity.currentPage));
    bookmark.setPageNumber(activity.currentPage + 1);
    bookmark.setTotalPages(activity.pageCount);
    bookmark.setEndPosition(activity.currentPage);
    activity.bookmarkManager.addBookmark(bookmark);
    ShortToast.show(activity, activity.getString(R.string.bookmark_saved));
}

private String pageLabel(int zeroBasedPage) {
    return String.format(Locale.getDefault(), "Page %d", zeroBasedPage + 1);
}

void showBookmarksDialog() {
    if (activity.filePath == null) {
        ShortToast.show(activity, activity.getString(R.string.file_not_loaded));
        return;
    }

    final int bg = activity.readerBg;
    final int panel = activity.readerPanel;
    final int fg = activity.readerFg;
    final int sub = activity.readerSub;
    final int line = activity.readerLine;

    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(activity.dpToPx(18), activity.dpToPx(16), activity.dpToPx(18), activity.dpToPx(10));
    GradientDrawable boxBg = new GradientDrawable();
    boxBg.setColor(bg);
    boxBg.setCornerRadius(activity.dpToPx(16));
    boxBg.setStroke(Math.max(1, activity.dpToPx(1)), line);
    box.setBackground(boxBg);

    TextView title = new TextView(activity);
    title.setText(activity.getString(R.string.bookmark));
    title.setTextColor(fg);
    title.setTextSize(22f);
    title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    title.setGravity(android.view.Gravity.CENTER);
    title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    title.setPadding(0, 0, 0, activity.dpToPx(4));
    box.addView(title, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

    TextView currentInfo = new TextView(activity);
    currentInfo.setTextColor(sub);
    currentInfo.setTextSize(12f);
    currentInfo.setGravity(android.view.Gravity.CENTER);
    currentInfo.setSingleLine(false);
    currentInfo.setLineSpacing(0f, 1.08f);
    currentInfo.setPadding(0, 0, 0, activity.dpToPx(10));
    box.addView(currentInfo, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
    TextView hintButton = new TextView(activity);
    hintButton.setText(activity.getString(R.string.bookmark_hints_show));
    hintButton.setContentDescription(activity.getString(R.string.bookmark_hints_show));
    hintButton.setTextColor(sub);
    hintButton.setTextSize(12f);
    hintButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    hintButton.setGravity(android.view.Gravity.CENTER);
    hintButton.setPadding(0, activity.dpToPx(6), 0, activity.dpToPx(4));
    hintButton.setOnClickListener(v -> showBookmarkHintsPopup());
    box.addView(hintButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

    TextView saveButton = new TextView(activity);
    saveButton.setText(activity.getString(R.string.add_current_bookmark));
    saveButton.setGravity(android.view.Gravity.CENTER);
    saveButton.setTextColor(fg);
    saveButton.setTextSize(16f);
    saveButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    saveButton.setPadding(0, activity.dpToPx(12), 0, activity.dpToPx(12));
    android.graphics.drawable.GradientDrawable saveBg = new android.graphics.drawable.GradientDrawable();
    boolean darkBookmarkDialog = activity.isDarkColor(bg);
    int saveFill = activity.blendColors(bg, fg, darkBookmarkDialog ? 0.135f : 0.085f);
    int saveStroke = activity.blendColors(bg, fg, darkBookmarkDialog ? 0.460f : 0.360f);
    saveBg.setColor(saveFill);
    saveBg.setCornerRadius(activity.dpToPx(14));
    saveBg.setStroke(Math.max(1, activity.dpToPx(1)), saveStroke);
    saveButton.setBackground(saveBg);
    LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    saveLp.setMargins(0, activity.dpToPx(8), 0, 0);

    RecyclerView rv = new RecyclerView(activity);
    rv.setLayoutManager(new LinearLayoutManager(activity));
    rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
    rv.setItemAnimator(null);
    rv.setBackgroundColor(Color.TRANSPARENT);
    rv.setPadding(0, activity.dpToPx(8), 0, 0);
    rv.setClipToPadding(false);
    BookmarkFolderAdapter adapter = new BookmarkFolderAdapter();
    adapter.setThemeColors(bg, fg, sub, panel);
    Set<String> expandedFolders = new HashSet<>();
    expandedFolders.add(activity.filePath);
    rv.setAdapter(adapter);

    TextView emptyText = new TextView(activity);
    emptyText.setText(activity.getString(R.string.no_bookmarks_hint));
    emptyText.setTextColor(sub);
    emptyText.setGravity(android.view.Gravity.CENTER);
    emptyText.setPadding(0, activity.dpToPx(18), 0, activity.dpToPx(18));
    box.addView(emptyText, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
    LinearLayout.LayoutParams bookmarkListLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            activity.dpToPx(430));
    box.addView(rv, bookmarkListLp);
    box.addView(saveButton, saveLp);

    TextView closeButton = new TextView(activity);
    closeButton.setText(activity.getString(R.string.close));
    closeButton.setGravity(android.view.Gravity.CENTER);
    closeButton.setTextColor(fg);
    closeButton.setTextSize(16f);
    closeButton.setPadding(0, activity.dpToPx(14), 0, activity.dpToPx(10));
    box.addView(closeButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

    android.app.Dialog dialog = activity.createStablePositionedDialog(box, 34, false, true);

    final Runnable[] refreshRef = new Runnable[1];
    refreshRef[0] = () -> {
        List<Bookmark> all = activity.bookmarkManager.getAllBookmarks();
        adapter.setBookmarks(all, expandedFolders, activity.filePath);
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
                adapter.getFolderCount(), all.size(), activity.currentPage + 1, activity.pageCount));
    };

    saveButton.setOnClickListener(v -> {
        addBookmarkForCurrentPage();
        expandedFolders.add(activity.filePath);
        refreshRef[0].run();
    });
    closeButton.setOnClickListener(v -> dialog.dismiss());

    adapter.setListener(new BookmarkFolderAdapter.Listener() {
        @Override public void onFolderClick(String folderFilePath) {
            if (expandedFolders.contains(folderFilePath)) expandedFolders.remove(folderFilePath);
            else expandedFolders.add(folderFilePath);
            refreshRef[0].run();
        }

        @Override public void onFolderDelete(String folderFilePath, String expansionKey, String folderName, int bookmarkCount) {
            showBookmarkFolderDeleteConfirm(folderFilePath, folderName, bookmarkCount, () -> {
                expandedFolders.remove(folderFilePath);
                expandedFolders.remove(expansionKey);
                refreshRef[0].run();
            });
        }

        @Override public void onBookmarkClick(Bookmark b) {
            navigateToBookmark(b);
            dialog.dismiss();
        }

        @Override public void onBookmarkDelete(Bookmark b) {
            showBookmarkDeleteConfirm(b, refreshRef[0]);
        }

        @Override public void onBookmarkEdit(Bookmark b) {
            showBookmarkMemoEditDialog(b, refreshRef[0]);
        }
    });

    dialog.setOnDismissListener(d -> {});
    refreshRef[0].run();
    dialog.show();
}

private void showBookmarkHintsPopup() {
    LinearLayout box = activity.makeDialogBox();
    box.addView(activity.makeDialogTitle(activity.getString(R.string.bookmark_hints_show)));

    TextView message = new TextView(activity);
    message.setText(activity.getString(R.string.bookmark_folder_hint));
    message.setTextColor(activity.dialogSub());
    message.setTextSize(13f);
    message.setLineSpacing(0f, 1.12f);
    message.setPadding(0, 0, 0, activity.dpToPx(12));
    box.addView(message, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

    final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
    activity.addDialogBottomActions(box, null, activity.getString(R.string.ok), () -> {
        if (dialogRef[0] != null) dialogRef[0].dismiss();
    });
    dialogRef[0] = createSmallBookmarkHintDialog(box);
    dialogRef[0].show();
}

private android.app.Dialog createSmallBookmarkHintDialog(@NonNull View content) {
    android.app.Dialog dialog = new android.app.Dialog(activity);
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
    dialog.setCanceledOnTouchOutside(true);

    android.widget.FrameLayout outerFrame = new android.widget.FrameLayout(activity);
    outerFrame.setBackgroundColor(Color.TRANSPARENT);
    outerFrame.setClipChildren(true);
    outerFrame.setClipToPadding(true);
    if (content instanceof ViewGroup) {
        ViewGroup group = (ViewGroup) content;
        group.setClipChildren(true);
        group.setClipToPadding(true);
    }
    ScrollView adaptiveScroll = activity.wrapAdaptiveDialogContent(content, outerFrame);
    dialog.setContentView(outerFrame);

    android.view.Window window = dialog.getWindow();
    if (window != null) {
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(window.getAttributes());
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        lp.width = Math.max(activity.dpToPx(240), Math.min(Math.round(screenWidth * 0.74f), activity.dpToPx(360)));
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.y = activity.dpToPx(112);
        lp.dimAmount = 0.16f;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }
    activity.applyAdaptiveDialogMaxHeight(dialog, adaptiveScroll, Math.max(activity.dpToPx(240), Math.min(Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.74f), activity.dpToPx(360))));
    return dialog;
}

private void navigateToBookmark(@NonNull Bookmark b) {
    String path = b.getFilePath();
    if (path == null || path.trim().isEmpty()) {
        ShortToast.show(activity, activity.getString(R.string.file_not_found_prefix) + "(missing path)");
        return;
    }

    File target = new File(path.trim());
    if (!target.exists()) {
        ShortToast.show(activity, activity.getString(R.string.file_not_found_prefix) + path);
        return;
    }
    if (path.equals(activity.filePath) || target.getAbsolutePath().equals(activity.filePath)) {
        activity.goToPage(b.getCharPosition(), Integer.compare(b.getCharPosition(), activity.currentPage));
        return;
    }
    android.content.Intent intent;
    String targetPath = target.getAbsolutePath();
    if (FileUtils.isPdfFile(target.getName())) {
        intent = new android.content.Intent(activity, PdfReaderActivity.class);
        intent.putExtra(PdfReaderActivity.EXTRA_FILE_PATH, targetPath);
        intent.putExtra(PdfReaderActivity.EXTRA_JUMP_TO_PAGE, b.getCharPosition());
    } else if (FileUtils.isEpubFile(target.getName()) || FileUtils.isWordFile(target.getName())) {
        intent = new android.content.Intent(activity, DocumentPageActivity.class);
        intent.putExtra(DocumentPageActivity.EXTRA_FILE_PATH, targetPath);
        intent.putExtra(DocumentPageActivity.EXTRA_JUMP_TO_PAGE, b.getCharPosition());
    } else {
        intent = new android.content.Intent(activity, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, targetPath);
        intent.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, b.getCharPosition());
        intent.putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, b.getPageNumber());
        intent.putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, b.getTotalPages());
    }
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
    activity.startActivity(intent);
}

private void showBookmarkDeleteConfirm(@NonNull Bookmark bookmark, @NonNull Runnable afterDelete) {
    LinearLayout box = activity.makeDialogBox();
    box.addView(activity.makeDialogTitle(activity.getString(R.string.delete_bookmark)));

    TextView message = new TextView(activity);
    message.setText(bookmark.getFileName() + "\n\n" + bookmark.getDisplayText());
    message.setTextColor(activity.dialogSub());
    message.setTextSize(14f);
    message.setLineSpacing(0f, 1.15f);
    message.setPadding(0, 0, 0, activity.dpToPx(12));
    box.addView(message, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

    final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
    activity.addDialogBottomActions(box, null, activity.getString(R.string.delete), () -> {
        activity.bookmarkManager.deleteBookmark(bookmark.getId());
        afterDelete.run();
        if (dialogRef[0] != null) dialogRef[0].dismiss();
    });
    dialogRef[0] = activity.createStablePositionedDialog(box, PdfReaderActivity.PDF_TOOLBAR_POPUP_Y_DP, false, false);
    dialogRef[0].show();
}

private void showBookmarkFolderDeleteConfirm(String folderFilePath, String folderName, int bookmarkCount, @NonNull Runnable afterDelete) {
    LinearLayout box = activity.makeDialogBox();
    box.addView(activity.makeDialogTitle(activity.getString(R.string.delete_bookmark_folder)));

    TextView message = new TextView(activity);
    String displayName = folderName != null && !folderName.trim().isEmpty() ? folderName.trim() : activity.getString(R.string.bookmark);
    message.setText(displayName + "\n\n"
            + activity.getString(R.string.delete_bookmark_folder_message, bookmarkCount)
            + "\n" + activity.getString(R.string.delete_bookmark_folder_note));
    message.setTextColor(activity.dialogSub());
    message.setTextSize(14f);
    message.setLineSpacing(0f, 1.15f);
    message.setPadding(0, 0, 0, activity.dpToPx(12));
    box.addView(message, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

    final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
    activity.addDialogBottomActions(box, null, activity.getString(R.string.delete), () -> {
        activity.bookmarkManager.deleteBookmarksForFile(folderFilePath);
        afterDelete.run();
        if (dialogRef[0] != null) dialogRef[0].dismiss();
    });
    dialogRef[0] = activity.createStablePositionedDialog(box, PdfReaderActivity.PDF_TOOLBAR_POPUP_Y_DP, false, false);
    dialogRef[0].show();
}

private void showBookmarkMemoEditDialog(@NonNull Bookmark bookmark, @NonNull Runnable afterSave) {
    LinearLayout box = activity.makeDialogBox();
    box.addView(activity.makeDialogTitle(activity.getString(R.string.edit_bookmark_memo)));

    EditText input = activity.makeDialogInput(activity.getString(R.string.optional_memo));
    input.setText(bookmark.getLabel());
    input.setSelectAllOnFocus(true);
    box.addView(input, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            activity.dpToPx(52)));

    final android.app.Dialog[] dialogRef = new android.app.Dialog[1];

    LinearLayout actions = new LinearLayout(activity);
    actions.setTag("dialog_actions");
    actions.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
    actions.setPadding(0, activity.dpToPx(8), 0, 0);

    TextView cancel = new TextView(activity);
    cancel.setText(activity.getString(R.string.cancel));
    cancel.setTextColor(activity.dialogSub());
    cancel.setTextSize(16f);
    cancel.setGravity(android.view.Gravity.CENTER);
    cancel.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);

    TextView clear = new TextView(activity);
    clear.setText(activity.getString(R.string.clear_memo));
    clear.setTextColor(activity.dialogSub());
    clear.setTextSize(16f);
    clear.setGravity(android.view.Gravity.CENTER);
    clear.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);

    TextView save = new TextView(activity);
    save.setText(activity.getString(R.string.save));
    save.setTextColor(activity.dialogFg());
    save.setTextSize(16f);
    save.setGravity(android.view.Gravity.CENTER);
    save.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    save.setPadding(activity.dpToPx(18), 0, activity.dpToPx(18), 0);

    actions.addView(cancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
    actions.addView(clear, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
    actions.addView(save, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dpToPx(46)));
    box.addView(actions, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    cancel.setOnClickListener(v -> {
        if (dialogRef[0] != null) dialogRef[0].dismiss();
    });
    clear.setOnClickListener(v -> {
        bookmark.setLabel("");
        activity.bookmarkManager.updateBookmark(bookmark);
        afterSave.run();
        if (dialogRef[0] != null) dialogRef[0].dismiss();
    });
    save.setOnClickListener(v -> {
        bookmark.setLabel(input.getText().toString().trim());
        activity.bookmarkManager.updateBookmark(bookmark);
        afterSave.run();
        if (dialogRef[0] != null) dialogRef[0].dismiss();
    });

    dialogRef[0] = activity.createStablePositionedDialog(box, PdfReaderActivity.PDF_TOOLBAR_POPUP_Y_DP, true, false);
    dialogRef[0].show();
}

}
