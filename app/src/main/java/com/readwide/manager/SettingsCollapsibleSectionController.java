package com.readwide.manager;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.readwide.manager.util.PrefsManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the flat list of settings sections into collapsible groups.
 *
 * The settings layout keeps every section as a flat sequence of direct children
 * inside one container: a bold section-header TextView followed by that section's
 * controls, repeated for each section. This controller walks the container once,
 * groups each header with the sibling views that follow it (up to the next header),
 * makes the header a tappable row with a disclosure marker, and toggles the group
 * visibility. Sections always start collapsed; expansion state is per-session only
 * so returning to Settings never reopens a section on its own.
 *
 * The Updates section is intentionally left raw: always visible, no toggle.
 *
 * The custom main-theme color block (main_custom_theme_section) carries its own
 * conditional visibility (shown only when the custom theme is selected). It is
 * excluded from the blanket group toggle so the two rules never fight; within an
 * expanded theme group it is shown only when the custom theme is also selected.
 */
final class SettingsCollapsibleSectionController {

    private static final int[] HEADER_IDS = {
            R.id.section_header_language,
            R.id.section_header_theme,
            R.id.section_header_txt_layout,
            R.id.section_header_epub_layout,
            R.id.section_header_behavior,
            R.id.section_header_button_order,
            R.id.section_header_security,
            R.id.section_header_backup,
            R.id.section_header_updates
    };
    private static final String[] HEADER_KEYS = {
            "language", "theme", "txt_layout", "epub_layout",
            "behavior", "button_order", "security", "backup", "updates"
    };
    // Sections left raw: always visible, no collapse toggle (single-option folders).
    private static final String[] RAW_SECTIONS = { "language", "updates" };
    // Sections that belong to the View-settings (appearance/style) mode. Everything
    // else is general app/data settings. The active mode hides the other group
    // entirely so the two entry points never show each other's sections.
    private static final String[] APPEARANCE_SECTIONS = { "theme", "txt_layout", "epub_layout" };

    private static final String MARKER_EXPANDED = "\u25BE  ";
    private static final String MARKER_COLLAPSED = "\u25B8  ";

    private final SettingsActivity activity;
    private final PrefsManager prefs;

    SettingsCollapsibleSectionController(@NonNull SettingsActivity activity) {
        this.activity = activity;
        this.prefs = activity.prefs;
    }

    void setup() {
        ViewGroup container = activity.findViewById(R.id.settings_content_container);
        if (container == null) return;

        int childCount = container.getChildCount();
        List<Integer> headerPositions = new ArrayList<>();
        List<View> headerViews = new ArrayList<>();
        List<String> headerKeys = new ArrayList<>();
        for (int i = 0; i < childCount; i++) {
            View child = container.getChildAt(i);
            String key = keyForId(child.getId());
            if (key != null) {
                headerPositions.add(i);
                headerViews.add(child);
                headerKeys.add(key);
            }
        }
        if (headerViews.isEmpty()) return;

        int verticalPadPx = Math.round(10 * activity.getResources().getDisplayMetrics().density);
        TypedValue rippleValue = new TypedValue();
        activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, rippleValue, true);
        int rippleRes = rippleValue.resourceId;

        for (int h = 0; h < headerViews.size(); h++) {
            final View header = headerViews.get(h);
            final String key = headerKeys.get(h);
            int start = headerPositions.get(h) + 1;
            int end = (h + 1 < headerPositions.size()) ? headerPositions.get(h + 1) : childCount;
            final List<View> content = new ArrayList<>();
            for (int i = start; i < end; i++) {
                content.add(container.getChildAt(i));
            }

            // Mode filter: a section that does not belong to the current Settings mode
            // (View settings vs general app settings) is hidden entirely, header and
            // content, and skipped from the collapse toggle.
            if (!sectionMatchesMode(key)) {
                header.setVisibility(View.GONE);
                for (View v : content) {
                    v.setVisibility(View.GONE);
                }
                continue;
            }

            // Raw sections (single-option folders) stay always visible, plain header, no toggle.
            if (isRawSection(key)) {
                continue;
            }

            final CharSequence baseTitle = (header instanceof TextView)
                    ? stripMarker(((TextView) header).getText()) : "";

            if (rippleRes != 0) {
                header.setBackgroundResource(rippleRes);
            }
            header.setPadding(header.getPaddingLeft(), verticalPadPx,
                    header.getPaddingRight(), verticalPadPx);
            header.setClickable(true);
            header.setFocusable(true);

            final boolean[] expandedState = { false };
            applyState(header, content, baseTitle, expandedState[0], key);

            header.setOnClickListener(v -> {
                expandedState[0] = !expandedState[0];
                applyState(header, content, baseTitle, expandedState[0], key);
            });
        }
    }

    private void applyState(View header, List<View> content,
                            CharSequence baseTitle, boolean expanded, String key) {
        for (View v : content) {
            if (v.getId() == R.id.main_custom_theme_section) {
                // Conditionally visible on its own; handled together with the
                // custom-theme rule below so the two never override each other.
                continue;
            }
            v.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (header instanceof TextView) {
            ((TextView) header).setText(activity.getString(
                    R.string.section_marker_title_format,
                    expanded ? MARKER_EXPANDED : MARKER_COLLAPSED,
                    baseTitle));
        }
        if ("theme".equals(key)) {
            applyCustomThemeSectionVisibility(content, expanded);
        }
    }

    private void applyCustomThemeSectionVisibility(List<View> content, boolean expanded) {
        View chart = null;
        for (View v : content) {
            if (v.getId() == R.id.main_custom_theme_section) {
                chart = v;
                break;
            }
        }
        if (chart == null) return;
        boolean custom = prefs != null && prefs.getDarkMode() == PrefsManager.DARK_MODE_CUSTOM;
        chart.setVisibility((expanded && custom) ? View.VISIBLE : View.GONE);
    }

    private CharSequence stripMarker(CharSequence text) {
        if (text == null) return "";
        String s = text.toString();
        if (s.startsWith(MARKER_EXPANDED)) return s.substring(MARKER_EXPANDED.length());
        if (s.startsWith(MARKER_COLLAPSED)) return s.substring(MARKER_COLLAPSED.length());
        return text;
    }

    private String keyForId(int id) {
        if (id == View.NO_ID) return null;
        for (int i = 0; i < HEADER_IDS.length; i++) {
            if (HEADER_IDS[i] == id) return HEADER_KEYS[i];
        }
        return null;
    }

    private boolean isRawSection(String key) {
        for (String raw : RAW_SECTIONS) {
            if (raw.equals(key)) return true;
        }
        return false;
    }

    private boolean sectionMatchesMode(String key) {
        boolean appearance = isAppearanceSection(key);
        return activity.appearanceSettingsMode ? appearance : !appearance;
    }

    private boolean isAppearanceSection(String key) {
        for (String s : APPEARANCE_SECTIONS) {
            if (s.equals(key)) return true;
        }
        return false;
    }
}
