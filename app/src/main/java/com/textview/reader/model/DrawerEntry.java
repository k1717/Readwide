package com.textview.reader.model;

/**
 * A single entry in the navigation drawer's storage shortcut list.
 * Carries an icon, a title, an optional subtitle (path), and the action key
 * the host activity uses to dispatch the click.
 */
public class DrawerEntry {

    /** Special action types used by MainActivity to route the click. */
    public static final int ACTION_RECENT = 0;
    public static final int ACTION_INTERNAL = 1;
    public static final int ACTION_EXTERNAL_SD = 2;
    public static final int ACTION_DOWNLOADS = 3;
    public static final int ACTION_STORAGE_ROOT = 4;
    public static final int ACTION_RECENT_FOLDER = 5;
    public static final int ACTION_FOLDER_SHORTCUT = 6;
    public static final int ACTION_HEADER = -1;
    public static final int ACTION_DIVIDER = -2;

    private final int actionType;
    private final int iconRes;
    private final String title;
    private final String subtitle;
    /** Filesystem path for entries that map to a directory (null for ACTION_RECENT). */
    private final String path;

    public DrawerEntry(int actionType, int iconRes, String title, String subtitle, String path) {
        this.actionType = actionType;
        this.iconRes = iconRes;
        this.title = title;
        this.subtitle = subtitle;
        this.path = path;
    }

    public int getActionType() { return actionType; }
    public int getIconRes() { return iconRes; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public String getPath() { return path; }
    public boolean isHeader() { return actionType == ACTION_HEADER; }
    public boolean isDivider() { return actionType == ACTION_DIVIDER; }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DrawerEntry)) return false;
        DrawerEntry other = (DrawerEntry) obj;
        return actionType == other.actionType
                && iconRes == other.iconRes
                && java.util.Objects.equals(title, other.title)
                && java.util.Objects.equals(subtitle, other.subtitle)
                && java.util.Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(actionType, iconRes, title, subtitle, path);
    }
}

