package com.readwide.manager.util;

/** Placement and proportions only; each viewer retains its own paging actions. */
public final class TapZoneProfile {
    public enum Viewer { TXT, EPUB, PDF, MARKDOWN, WORD, IMAGE }

    public final int mode;
    public final int leading;
    public final int trailing;

    public TapZoneProfile(int mode, int leading, int trailing) {
        this.mode = mode == PrefsManager.TAP_ZONE_HORIZONTAL
                ? PrefsManager.TAP_ZONE_HORIZONTAL : PrefsManager.TAP_ZONE_VERTICAL;
        this.leading = Math.max(5, Math.min(80, leading));
        this.trailing = Math.max(5, Math.min(Math.min(80, trailing), 90 - this.leading));
    }

    public int middle() { return 100 - leading - trailing; }

    public static Viewer forDocument(String type) {
        if ("EPUB".equals(type)) return Viewer.EPUB;
        if ("Markdown".equals(type)) return Viewer.MARKDOWN;
        if ("Word".equals(type) || "HWP".equals(type)) return Viewer.WORD;
        return Viewer.TXT;
    }

    public static String key(Viewer viewer, String field) {
        if (viewer == Viewer.TXT) throw new IllegalArgumentException("TXT uses legacy keys");
        return "tap_profile_" + viewer.name().toLowerCase(java.util.Locale.ROOT) + "_" + field;
    }

    static Class<?> preferenceType(String key) {
        if (key == null || !key.startsWith("tap_profile_")) return null;
        for (Viewer viewer : Viewer.values()) {
            if (viewer == Viewer.TXT) continue;
            if (key.equals(key(viewer, "use_txt"))) return Boolean.class;
            for (String field : new String[]{"mode", "leading", "trailing"}) {
                if (key.equals(key(viewer, field))) return Integer.class;
            }
        }
        return null;
    }

    static String[] withResetKeys(String[] existing) {
        java.util.List<String> keys = new java.util.ArrayList<>(java.util.Arrays.asList(existing));
        for (Viewer viewer : Viewer.values()) {
            if (viewer == Viewer.TXT) continue;
            for (String field : new String[]{"use_txt", "mode", "leading", "trailing"}) {
                keys.add(key(viewer, field));
            }
        }
        return keys.toArray(new String[0]);
    }
}
