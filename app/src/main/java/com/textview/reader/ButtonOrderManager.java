package com.textview.reader;

import android.app.Activity;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.PrefsManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Centralizes user-customizable button/chip ordering.  The UI surfaces only
 * stable string keys; concrete view ids stay here so Activities can simply ask
 * this class to reattach their existing views in the saved order.
 */
final class ButtonOrderManager {
    static final String GROUP_MAIN_FILTERS = "main_filters";
    static final String GROUP_TXT_READER = "txt_reader";
    static final String GROUP_DOCUMENT_VIEWER = "document_viewer";
    static final String GROUP_PDF_VIEWER = "pdf_viewer";

    private ButtonOrderManager() {}

    static final class Item {
        final String key;
        final int labelRes;
        final int viewId;

        Item(@NonNull String key, int labelRes, int viewId) {
            this.key = key;
            this.labelRes = labelRes;
            this.viewId = viewId;
        }
    }

    @NonNull
    static List<Item> defaultItems(@NonNull String group) {
        ArrayList<Item> items = new ArrayList<>();
        switch (group) {
            case GROUP_TXT_READER:
                items.add(new Item("home", R.string.toolbar_home, R.id.btn_home));
                items.add(new Item("open", R.string.toolbar_open_file, R.id.btn_open_file));
                items.add(new Item("find", R.string.find, R.id.btn_find));
                items.add(new Item("page", R.string.bottom_page, R.id.btn_page_move));
                items.add(new Item("bookmark", R.string.bookmark, R.id.btn_bookmark));
                items.add(new Item("auto_page", R.string.auto_page_turn_toolbar, R.id.btn_auto_page));
                items.add(new Item("settings", R.string.settings, R.id.btn_settings));
                items.add(new Item("tts", R.string.tts_toolbar, R.id.btn_tts));
                items.add(new Item("font", R.string.font, R.id.btn_font));
                items.add(new Item("rule_add", R.string.toolbar_rule_add, R.id.btn_rule_add));
                items.add(new Item("rule_manage", R.string.toolbar_rule_manage, R.id.btn_rule_manage));
                items.add(new Item("encoding", R.string.toolbar_text_encoding, R.id.btn_text_encoding));
                break;
            case GROUP_DOCUMENT_VIEWER:
                items.add(new Item("prev", R.string.previous_page, R.id.btn_prev_page));
                items.add(new Item("next", R.string.next_page, R.id.btn_next_page));
                items.add(new Item("find", R.string.find, R.id.btn_document_search));
                items.add(new Item("page", R.string.bottom_page, R.id.btn_page_move));
                items.add(new Item("bookmark", R.string.bookmark, R.id.btn_bookmarks));
                items.add(new Item("more", R.string.more, R.id.btn_more));
                break;
            case GROUP_PDF_VIEWER:
                items.add(new Item("prev", R.string.previous_page, R.id.pdf_prev));
                items.add(new Item("next", R.string.next_page, R.id.pdf_next));
                items.add(new Item("mode", R.string.viewer_mode, R.id.pdf_slide_toggle));
                items.add(new Item("page", R.string.bottom_page, R.id.pdf_page));
                items.add(new Item("bookmark", R.string.bookmark, R.id.pdf_bookmark));
                items.add(new Item("more", R.string.more, R.id.pdf_zoom_more));
                break;
            case GROUP_MAIN_FILTERS:
            default:
                items.add(new Item("all", R.string.file_filter_all, R.id.filter_all));
                items.add(new Item("general", R.string.file_filter_general, R.id.filter_general));
                items.add(new Item("archive", R.string.file_filter_archive, R.id.filter_archive));
                items.add(new Item("txt", R.string.file_filter_txt, R.id.filter_txt));
                items.add(new Item("pdf", R.string.file_filter_pdf, R.id.filter_pdf));
                items.add(new Item("epub", R.string.file_filter_epub, R.id.filter_epub));
                items.add(new Item("word", R.string.file_filter_word, R.id.filter_word));
                items.add(new Item("image", R.string.file_filter_img, R.id.filter_img));
                break;
        }
        return items;
    }

    @NonNull
    static List<Item> orderedItems(@Nullable PrefsManager prefs, @NonNull String group) {
        List<Item> defaults = defaultItems(group);
        String saved = prefs != null ? prefs.getPrefs().getString(prefKey(group), "") : "";
        if (saved == null || saved.trim().isEmpty()) return defaults;

        ArrayList<Item> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String key : saved.split(",")) {
            String clean = key == null ? "" : key.trim();
            if (clean.isEmpty() || used.contains(clean)) continue;
            Item item = itemForKey(defaults, clean);
            if (item != null) {
                result.add(item);
                used.add(clean);
            }
        }
        for (Item item : defaults) {
            if (!used.contains(item.key)) result.add(item);
        }
        return result;
    }

    static void saveOrder(@Nullable PrefsManager prefs, @NonNull String group, @NonNull List<Item> orderedItems) {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        Set<String> used = new HashSet<>();
        for (Item item : orderedItems) {
            if (item == null || item.key == null || used.contains(item.key)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(item.key);
            used.add(item.key);
        }
        prefs.getPrefs().edit().putString(prefKey(group), sb.toString()).apply();
    }

    static void resetOrder(@Nullable PrefsManager prefs, @NonNull String group) {
        if (prefs == null) return;
        prefs.getPrefs().edit().remove(prefKey(group)).apply();
    }

    static void applyOrder(@NonNull Activity activity, @Nullable PrefsManager prefs, @NonNull String group) {
        List<Item> items = orderedItems(prefs, group);
        ArrayList<View> views = new ArrayList<>();
        LinearLayout parent = null;
        for (Item item : items) {
            View view = activity.findViewById(item.viewId);
            if (view == null) continue;
            ViewParent rawParent = view.getParent();
            if (!(rawParent instanceof LinearLayout)) continue;
            if (parent == null) parent = (LinearLayout) rawParent;
            if (rawParent == parent) views.add(view);
        }
        if (parent == null || views.size() < 2) return;
        for (View view : views) parent.removeView(view);
        for (View view : views) parent.addView(view);
    }

    private static String prefKey(@NonNull String group) {
        return "button_order_" + group;
    }

    @Nullable
    private static Item itemForKey(@NonNull List<Item> items, @NonNull String key) {
        for (Item item : items) {
            if (key.equals(item.key)) return item;
        }
        return null;
    }
}
