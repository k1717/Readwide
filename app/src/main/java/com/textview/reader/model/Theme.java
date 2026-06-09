package com.textview.reader.model;

import com.textview.reader.UiColorUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Custom reading theme with text/background colors and optional background image.
 */
public class Theme {
    private String id;
    private String name;
    private int textColor;
    private int backgroundColor;
    private int linkColor;
    private int toolbarColor;
    private String backgroundImagePath; // null if no image
    private float backgroundImageAlpha; // 0.0 - 1.0
    private boolean isBuiltIn;

    // Built-in themes
    public static final Theme LIGHT = new Theme("light", "Light",
            0xFF212121, 0xFFFAFAFA, 0xFF1976D2, true);
    public static final Theme DARK = new Theme("dark", "Dark",
            0xFFE0E0E0, 0xFF000000, 0xFF9E9E9E, true);
    public static final Theme SEPIA = new Theme("sepia", "Sepia",
            0xFF5B4636, 0xFFF4ECD8, 0xFF8B5E3C, true);
    public static final Theme DARK_BLUE = new Theme("dark_blue", "Deep Navy",
            0xFFD7E4FA, 0xFF050D23, 0xFF82B1FF, 0xFF041630, true);
    public static final Theme GREEN_EYE = new Theme("green_eye", "Eye Care",
            0xFF2E4A2E, 0xFFC8E6C9, 0xFF1B5E20, true);
    public static final Theme CREAM = new Theme("cream", "Cream",
            0xFF3E2723, 0xFFFFF8E1, 0xFF6D4C41, true);

    public static final Theme[] BUILT_IN_THEMES = {LIGHT, DARK, SEPIA, DARK_BLUE, GREEN_EYE, CREAM};

    private Theme(String id, String name, int textColor, int bgColor, int linkColor, boolean builtIn) {
        this(id, name, textColor, bgColor, linkColor,
                defaultToolbarColorForBackground(bgColor, usesEvenGrayToolbarFallback(id, builtIn)),
                builtIn);
    }

    private Theme(String id, String name, int textColor, int bgColor, int linkColor, int toolbarColor, boolean builtIn) {
        this.id = id;
        this.name = name;
        this.textColor = textColor;
        this.backgroundColor = bgColor;
        this.linkColor = linkColor;
        this.toolbarColor = toolbarColor;
        this.backgroundImageAlpha = 0.3f;
        this.isBuiltIn = builtIn;
    }

    private static boolean usesEvenGrayToolbarFallback(String id, boolean builtIn) {
        return builtIn && ("light".equals(id) || "dark".equals(id));
    }

    private static int defaultToolbarColorForBackground(int bgColor) {
        return defaultToolbarColorForBackground(bgColor, false);
    }

    private static int defaultToolbarColorForBackground(int bgColor, boolean evenGrayFallback) {
        int r = (bgColor >> 16) & 0xFF;
        int g = (bgColor >> 8) & 0xFF;
        int b = bgColor & 0xFF;
        boolean light = UiColorUtils.isLightColor(bgColor);

        int max = Math.max(r, Math.max(g, b));

        // Derive the fallback toolbar color from the reading background itself.
        // Light reading backgrounds get lower RGB intensity at 0.25 amount.
        // Dark reading backgrounds get higher RGB intensity at 0.20 amount.
        // Light/Dark reading themes keep the separate 0.17 even-gray fallback.
        // Colored bright backgrounds use inverse 4:2:4 weighting; dark backgrounds use
        // a simple hue-preserving intensity increase. Deep Navy uses a fixed toolbar color.
        int outR = weightedToolbarChannel(r, max, light, evenGrayFallback,
                toolbarAdjustmentWeight(r, g, b, r, light, evenGrayFallback));
        int outG = weightedToolbarChannel(g, max, light, evenGrayFallback,
                toolbarAdjustmentWeight(r, g, b, g, light, evenGrayFallback));
        int outB = weightedToolbarChannel(b, max, light, evenGrayFallback,
                toolbarAdjustmentWeight(r, g, b, b, light, evenGrayFallback));
        return 0xFF000000 | (outR << 16) | (outG << 8) | outB;
    }

    private static float toolbarAdjustmentWeight(int r, int g, int b, int channel,
                                                 boolean lightBackground, boolean evenGrayFallback) {
        if (evenGrayFallback) {
            // Built-in Light/Dark use an even 3.3:3.3:3.3 gray distribution.
            return 2.0f / 3.0f;
        }
        if (lightBackground) {
            // Bright reading backgrounds keep the inverse 4:2:4 weighting:
            // the strongest RGB channel receives half adjustment, weaker channels receive more.
            int maxChannel = Math.max(r, Math.max(g, b));
            return 1.0f - (0.5f * (channel / (float) Math.max(1, maxChannel)));
        }

        // Dark reading backgrounds use a simple hue-preserving intensity increase.
        return 1.0f;
    }

    private static int weightedToolbarChannel(int channel, int maxChannel, boolean lightBackground,
                                              boolean evenGrayFallback, float adjustmentWeight) {
        float baseAmount;
        if (evenGrayFallback) {
            baseAmount = 0.17f;
        } else if (lightBackground) {
            baseAmount = 0.25f;
        } else {
            baseAmount = 0.20f;
        }
        float amount = baseAmount * adjustmentWeight;
        if (lightBackground) {
            return clamp(Math.round(channel * (1.0f - amount)));
        } else {
            if (maxChannel <= 0) {
                return clamp(Math.round(255.0f * amount));
            }
            return clamp(Math.round(channel * (1.0f + amount)));
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    public Theme() {
        this.id = UUID.randomUUID().toString();
        this.backgroundImageAlpha = 0.3f;
        this.toolbarColor = 0xFFFAFAFA;
        this.isBuiltIn = false;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("textColor", textColor);
        obj.put("backgroundColor", backgroundColor);
        obj.put("linkColor", linkColor);
        obj.put("toolbarColor", toolbarColor);
        obj.put("backgroundImagePath", backgroundImagePath != null ? backgroundImagePath : "");
        obj.put("backgroundImageAlpha", backgroundImageAlpha);
        obj.put("isBuiltIn", isBuiltIn);
        return obj;
    }

    public static Theme fromJson(JSONObject obj) throws JSONException {
        Theme t = new Theme();
        t.id = obj.getString("id");
        t.name = obj.optString("name", "Custom");
        t.textColor = obj.getInt("textColor");
        t.backgroundColor = obj.getInt("backgroundColor");
        t.linkColor = obj.optInt("linkColor", 0xFF1976D2);
        t.toolbarColor = obj.optInt("toolbarColor", defaultToolbarColorForBackground(t.backgroundColor));
        String imgPath = obj.optString("backgroundImagePath", "");
        t.backgroundImagePath = imgPath.isEmpty() ? null : imgPath;
        t.backgroundImageAlpha = (float) obj.optDouble("backgroundImageAlpha", 0.3);
        t.isBuiltIn = obj.optBoolean("isBuiltIn", false);
        return t;
    }

    // Getters/Setters
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getTextColor() { return textColor; }
    public void setTextColor(int c) { this.textColor = c; }
    public int getBackgroundColor() { return backgroundColor; }
    public void setBackgroundColor(int c) { this.backgroundColor = c; }
    public int getLinkColor() { return linkColor; }
    public void setLinkColor(int c) { this.linkColor = c; }
    public int getToolbarColor() { return toolbarColor; }
    public void setToolbarColor(int c) { this.toolbarColor = c; }
    public String getBackgroundImagePath() { return backgroundImagePath; }
    public void setBackgroundImagePath(String p) { this.backgroundImagePath = p; }
    public float getBackgroundImageAlpha() { return backgroundImageAlpha; }
    public void setBackgroundImageAlpha(float a) { this.backgroundImageAlpha = a; }
    public boolean isBuiltIn() { return isBuiltIn; }

    public boolean isDark() {
        // Simple luminance check
        int r = (backgroundColor >> 16) & 0xFF;
        int g = (backgroundColor >> 8) & 0xFF;
        int b = backgroundColor & 0xFF;
        return UiColorUtils.isDarkColor(backgroundColor);
    }
}
