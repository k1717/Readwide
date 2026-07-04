package com.readwide.manager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.readwide.manager.util.BookmarkManager;
import com.readwide.manager.util.ThemeManager;

/**
 * What {@link ReaderDialogStyleController} needs from the activity it styles
 * dialogs for. Originally the styler was hard-wired to {@code ReaderActivity};
 * this interface lets {@code DocumentPageActivity} host the same dialogs
 * (currently the read-aloud dialogs) with its own theme snapshot.
 */
interface ReaderDialogStyleHost {

    /** The activity dialogs are created on; also the Context for views and strings. */
    @NonNull
    AppCompatActivity dialogStyleHostActivity();

    /** dp-to-px for the host's display. */
    int dialogStyleDpToPx(int dp);

    /**
     * The theme manager backing the snapshot. Hosts that keep a lazily
     * initialized field should initialize it here so other host code observes
     * the same instance the styler used.
     */
    @NonNull
    ThemeManager dialogStyleThemeManager();

    /** Current dialog theme snapshot: background color. */
    int dialogSnapshotBackgroundColor();

    /** Current dialog theme snapshot: text color. */
    int dialogSnapshotTextColor();

    /** Store a refreshed theme snapshot (called by {@code syncReaderDialogThemeSnapshot}). */
    void setDialogSnapshotColors(int backgroundColor, int textColor);

    /** Bookmark manager for the styler's bookmark-list dialog; null on hosts without one. */
    @Nullable
    BookmarkManager dialogStyleBookmarkManager();
}
