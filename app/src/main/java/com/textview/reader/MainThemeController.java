package com.textview.reader;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

final class MainThemeController {
    private final MainActivity activity;

    MainThemeController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    boolean isDarkUi() {
        return activity.prefs != null && activity.prefs.shouldUseDarkColors(activity);
    }

    Drawable createDrawerInsetBackground(int normalColor, int topInsetColor) {
        ColorDrawable normalLayer = new ColorDrawable(normalColor);
        ColorDrawable topLayer = new ColorDrawable(topInsetColor);
        LayerDrawable background = new LayerDrawable(new Drawable[]{normalLayer, topLayer});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            background.setLayerHeight(1, Math.max(0, activity.drawerTopInsetPx));
            background.setLayerGravity(1, Gravity.TOP);
        }
        return background;
    }

    void applyMainReadableTheme(Toolbar toolbar) {
        boolean dark = isDarkUi();

        int bg = activity.prefs != null ? activity.prefs.getMainBgColor(activity) : (dark ? Color.rgb(0, 0, 0) : Color.rgb(255, 255, 255));
        int panel = activity.prefs != null ? activity.prefs.getMainPanelColor(activity) : (dark ? Color.rgb(17, 17, 17) : Color.rgb(248, 249, 250));
        int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36));
        int sub = activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : (dark ? Color.rgb(176, 176, 176) : Color.rgb(95, 99, 104));
        int bar = activity.prefs != null ? activity.prefs.getMainBarColor(activity) : (dark ? Color.rgb(0, 0, 0) : Color.rgb(32, 33, 36));
        int control = activity.prefs != null ? activity.prefs.getMainControlColor(activity) : (dark ? Color.rgb(210, 210, 210) : Color.rgb(80, 80, 80));
        int drawerActionIcon = activity.prefs != null ? activity.prefs.getMainDrawerActionIconColor(activity) : control;

        View root = activity.findViewById(R.id.main_root);
        View appbar = activity.findViewById(R.id.main_appbar);
        View navDrawer = activity.findViewById(R.id.nav_drawer);
        View recentHeaderRow = activity.findViewById(R.id.recent_header_row);
        TextView recentTitle = activity.findViewById(R.id.recent_section_title);
        View searchBar = activity.findViewById(R.id.file_search_bar);
        View localPathBar = activity.findViewById(R.id.path_bar);
        View drawerRecentHeader = activity.findViewById(R.id.drawer_recent_folders_header);
        View drawerBottomActions = activity.findViewById(R.id.drawer_bottom_actions);
        View drawerOpenFile = activity.findViewById(R.id.drawer_btn_open_file);
        View drawerBookmarks = activity.findViewById(R.id.drawer_btn_bookmarks);
        View drawerSettings = activity.findViewById(R.id.drawer_btn_settings);

        if (root != null) root.setBackgroundColor(bg);
        if (activity.browserSection != null) activity.browserSection.setBackgroundColor(bg);
        if (activity.recentSection != null) activity.recentSection.setBackgroundColor(bg);
        if (navDrawer != null) {
            // Keep the drawer content and bottom inset on the normal drawer background,
            // while only the top status/inset padding above the Recent folders header
            // uses the main app-bar color. This prevents header overlap without
            // pushing the bottom drawer actions into the navigation bar.
            navDrawer.setBackground(createDrawerInsetBackground(bg, bar));
            applyExplicitTextColors(navDrawer, fg, sub);
        }
        if (activity.drawerLayout != null) {
            activity.drawerLayout.setStatusBarBackgroundColor(bar);
        }
        // Recent-folder rows stay on the normal drawer background, matching the
        // recent-file row surface. The Recent folders header itself matches the
        // main Recent files header surface, while the status inset above it uses
        // the main app bar color through DrawerLayout's status-bar background.
        if (activity.drawerStorageList != null) activity.drawerStorageList.setBackgroundColor(bg);
        if (activity.drawerShortcutList != null) activity.drawerShortcutList.setBackgroundColor(bg);
        int drawerRecentHeaderBg = panel;
        int drawerRecentHeaderFg = UiColorUtils.readableChipTextColorForBackground(drawerRecentHeaderBg);
        if (drawerRecentHeader != null) drawerRecentHeader.setBackgroundColor(drawerRecentHeaderBg);
        if (activity.drawerRecentFoldersTitle != null) {
            activity.drawerRecentFoldersTitle.setBackgroundColor(Color.TRANSPARENT);
            activity.drawerRecentFoldersTitle.setTextColor(drawerRecentHeaderFg);
        }
        if (activity.drawerRecentFoldersClearButton != null) {
            activity.drawerRecentFoldersClearButton.setBackgroundColor(Color.TRANSPARENT);
        }
        if (drawerBottomActions != null) {
            drawerBottomActions.setBackgroundColor(bg);
            applyExplicitTextColors(drawerBottomActions, fg, sub);
        }
        applyDrawerBottomActionTheme(drawerOpenFile, bg, fg, drawerActionIcon);
        applyDrawerBottomActionTheme(drawerBookmarks, bg, fg, drawerActionIcon);
        applyDrawerBottomActionTheme(drawerSettings, bg, fg, drawerActionIcon);
        if (recentHeaderRow != null) recentHeaderRow.setBackgroundColor(panel);
        if (recentTitle != null) {
            recentTitle.setBackgroundColor(Color.TRANSPARENT);
            recentTitle.setTextColor(fg);
        }
        if (activity.recentClearAllButton != null) {
            activity.recentClearAllButton.setTextColor(sub);
        }
        if (activity.drawerRecentFoldersClearButton != null) {
            activity.drawerRecentFoldersClearButton.setTextColor(drawerRecentHeaderFg);
        }
        if (searchBar != null) {
            searchBar.setBackgroundColor(bg);
            applyExplicitTextColors(searchBar, fg, sub);
        }
        if (activity.fileSearchInput != null) {
            activity.fileSearchInput.setTextColor(fg);
            activity.fileSearchInput.setHintTextColor(sub);
            activity.fileSearchInput.setBackgroundColor(panel);
            Drawable[] drawables = activity.fileSearchInput.getCompoundDrawablesRelative();
            for (Drawable drawable : drawables) {
                if (drawable != null) DrawableCompat.setTint(drawable.mutate(), sub);
            }
        }
        if (activity.fileSearchClearButton != null) activity.fileSearchClearButton.setTextColor(fg);
        if (activity.fileSearchScopeButton != null) activity.updateSearchScopeButton();
        if (activity.fileSortButton != null) {
            Drawable sortIcon = ContextCompat.getDrawable(activity, R.drawable.ic_sort);
            if (sortIcon != null) {
                Drawable wrapped = DrawableCompat.wrap(sortIcon.mutate());
                DrawableCompat.setTint(wrapped, sub);
                activity.fileSortButton.setImageDrawable(wrapped);
            }
        }
        if (activity.fileRecyclerView != null) activity.fileRecyclerView.setBackgroundColor(bg);
        if (activity.recentRecyclerView != null) activity.recentRecyclerView.setBackgroundColor(bg);
        if (activity.recentSection != null) applyExplicitTextColors(activity.recentSection, fg, sub);
        if (activity.browserSection != null) applyExplicitTextColors(activity.browserSection, fg, sub);
        if (localPathBar != null) localPathBar.setBackgroundColor(panel);
        if (activity.pathText != null) {
            activity.pathText.setTextColor(sub);
        }
        if (activity.parentFolderButton != null) {
            activity.parentFolderButton.setTextColor(fg);
        }
        if (activity.emptyText != null) activity.emptyText.setTextColor(sub);
        if (activity.recentEmptyText != null) activity.recentEmptyText.setTextColor(sub);

        if (appbar != null) appbar.setBackgroundColor(bar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(bar);
            toolbar.setTitleTextColor(Color.WHITE);
            tintMainToolbarIcons(toolbar, Color.WHITE);
            activity.installToolbarMenuButton(toolbar);
            activity.updateMainOverflowButtonVisibility();
        }

        activity.getWindow().setStatusBarColor(bar);
        activity.getWindow().setNavigationBarColor(bg);
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(!dark);
        if (activity.drawerEntryAdapter != null) activity.drawerEntryAdapter.refreshTheme();
        if (activity.drawerFixedEntryAdapter != null) activity.drawerFixedEntryAdapter.refreshTheme();
        if (activity.drawerShortcutEntryAdapter != null) activity.drawerShortcutEntryAdapter.refreshTheme();
        if (activity.fileAdapter != null) activity.fileAdapter.refreshTheme();
        if (activity.recentAdapter != null) activity.recentAdapter.refreshTheme();
        activity.updateFileTypeChips();
    }

    private void applyDrawerBottomActionTheme(View actionRow, int bgColor, int textColor, int iconColor) {
        if (actionRow == null) return;
        actionRow.setBackgroundColor(bgColor);
        actionRow.setPadding(activity.dpToPx(20), 0, activity.dpToPx(16), 0);
        applyDrawerBottomActionColors(actionRow, textColor, iconColor);
    }

    private void applyDrawerBottomActionColors(@NonNull View view, int textColor, int iconColor) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(textColor);
        } else if (view instanceof ImageView) {
            ((ImageView) view).setImageTintList(android.content.res.ColorStateList.valueOf(iconColor));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyDrawerBottomActionColors(group.getChildAt(i), textColor, iconColor);
            }
        }
    }

    private void applyExplicitTextColors(@NonNull View view, int fg, int sub) {
        if (view instanceof TextView) {
            int id = view.getId();
            TextView textView = (TextView) view;
            if (id == R.id.empty_text || id == R.id.recent_empty_text || id == R.id.current_path) {
                textView.setTextColor(sub);
            } else {
                textView.setTextColor(fg);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyExplicitTextColors(group.getChildAt(i), fg, sub);
            }
        }
    }

    private void tintMainToolbarIcons(@NonNull Toolbar toolbar, int color) {
        if (activity.mainOperationProgressButton != null) {
            Drawable progress = ContextCompat.getDrawable(activity, R.drawable.ic_operation_progress);
            if (progress != null) {
                Drawable wrappedProgress = DrawableCompat.wrap(progress.mutate());
                DrawableCompat.setTint(wrappedProgress, color);
                activity.mainOperationProgressButton.setImageDrawable(wrappedProgress);
            }
        }
        if (activity.mainPendingActionButton != null) {
            Drawable pending = ContextCompat.getDrawable(activity, R.drawable.ic_pending_actions);
            if (pending != null) {
                Drawable wrappedPending = DrawableCompat.wrap(pending.mutate());
                DrawableCompat.setTint(wrappedPending, color);
                activity.mainPendingActionButton.setImageDrawable(wrappedPending);
            }
        }
        Drawable overflow = ContextCompat.getDrawable(activity, R.drawable.ic_more_vert);
        if (overflow != null) {
            Drawable wrapped = DrawableCompat.wrap(overflow.mutate());
            DrawableCompat.setTint(wrapped, color);
            toolbar.setOverflowIcon(wrapped);
        }
        Drawable nav = toolbar.getNavigationIcon();
        if (nav != null) {
            Drawable wrapped = DrawableCompat.wrap(nav.mutate());
            DrawableCompat.setTint(wrapped, color);
            toolbar.setNavigationIcon(wrapped);
        }
    }

    @SuppressWarnings("unused")
    void tintMenuIcons(@NonNull Menu menu, int color) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Drawable icon = item.getIcon();
            if (icon != null) {
                Drawable wrapped = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(wrapped, color);
                item.setIcon(wrapped);
            }
        }
    }
}
