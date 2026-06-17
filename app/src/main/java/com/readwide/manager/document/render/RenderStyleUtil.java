package com.readwide.manager.document.render;

/** Small shared helpers for the rendered-document style builders. */
final class RenderStyleUtil {
    private RenderStyleUtil() {}

    /** Trim a string; return null if it's null or blank after trimming. */
    static String emptyToNull(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }
}
