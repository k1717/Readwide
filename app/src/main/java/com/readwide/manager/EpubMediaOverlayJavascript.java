package com.readwide.manager;

import java.util.regex.Pattern;

/** Pure string builder for the small DOM surface used by EPUB media overlays. */
final class EpubMediaOverlayJavascript {
    static final String READWIDE_ACTIVE_CLASS = "-readwide-media-overlay-active";
    private static final String ACTIVE_ATTRIBUTE = "data-readwide-media-overlay-active";
    private static final String STYLE_ID = "__readwide_media_overlay_style";
    private static final Pattern SAFE_CLASS = Pattern.compile("^[-_A-Za-z][-_A-Za-z0-9]{0,127}$");

    private EpubMediaOverlayJavascript() {
    }

    /**
     * Clears the old cue, marks the requested ID, and centers it in both normal
     * and vertical-writing documents. The publisher class is accepted only when
     * it is a single safe class token; the Readwide fallback class is always
     * applied so books without {@code media:active-class} remain visible.
     */
    static String highlight(String fragment, String publisherActiveClass) {
        String id = jsString(fragment != null ? fragment : "");
        String publisher = jsString(safeClassOrEmpty(publisherActiveClass));
        String fallback = jsString(READWIDE_ACTIVE_CLASS);
        String attribute = jsString(ACTIVE_ATTRIBUTE);
        String styleId = jsString(STYLE_ID);
        return "(function(){try{"
                + "var a=" + attribute + ",f=" + fallback + ",p=" + publisher + ";"
                + clearMarkedElementsJavascript("a", "f")
                + "var e=document.getElementById(" + id + ");if(!e)return false;"
                + "if(!document.getElementById(" + styleId + ")){"
                + "var s=document.createElement('style');s.id=" + styleId + ";"
                + "s.textContent='.'+f+'{background-color:rgba(255,210,0,.32)!important;'"
                + "+'outline:2px solid rgba(255,170,0,.72)!important;'"
                + "+'outline-offset:1px;box-decoration-break:clone;-webkit-box-decoration-break:clone;}';"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "e.setAttribute(a,'1');e.classList.add(f);if(p)e.classList.add(p);"
                + "if(e.scrollIntoView)e.scrollIntoView({block:'center',inline:'center',behavior:'auto'});"
                + "return true;}catch(_e){return false;}})()";
    }

    /** Removes only classes previously added by this helper. */
    static String clear(String publisherActiveClass) {
        String publisher = jsString(safeClassOrEmpty(publisherActiveClass));
        String fallback = jsString(READWIDE_ACTIVE_CLASS);
        String attribute = jsString(ACTIVE_ATTRIBUTE);
        return "(function(){try{var a=" + attribute + ",f=" + fallback + ",p=" + publisher + ";"
                + clearMarkedElementsJavascript("a", "f")
                + "return true;}catch(_e){return false;}})()";
    }

    private static String clearMarkedElementsJavascript(String attributeVariable,
                                                         String fallbackVariable) {
        return "var n=document.querySelectorAll('['+" + attributeVariable + "+']');"
                + "for(var i=0;i<n.length;i++){var x=n[i];x.removeAttribute("
                + attributeVariable + ");x.classList.remove(" + fallbackVariable
                + ");if(p)x.classList.remove(p);}";
    }

    static String safeClassOrEmpty(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return SAFE_CLASS.matcher(trimmed).matches() && !"-".equals(trimmed)
                && !"_".equals(trimmed) ? trimmed : "";
    }

    private static String jsString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.append('"').toString();
    }
}
