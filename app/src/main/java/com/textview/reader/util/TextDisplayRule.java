package com.textview.reader.util;

import org.json.JSONException;
import org.json.JSONObject;

public class TextDisplayRule {
    public static final String SCOPE_ALL_TXT = "all_txt";
    public static final String SCOPE_FILE = "file";

    public String id;
    public boolean enabled;
    public String findText;
    public String replacementText;
    public boolean caseSensitive;
    public boolean useRegex;
    public String scope;
    public String filePath;
    public String sourceFilePath;

    public TextDisplayRule() {
        this.id = String.valueOf(System.currentTimeMillis());
        this.enabled = true;
        this.findText = "";
        this.replacementText = "";
        this.caseSensitive = false;
        this.useRegex = false;
        this.scope = SCOPE_ALL_TXT;
        this.filePath = "";
        this.sourceFilePath = "";
    }

    public boolean isValid() {
        if (findText == null || findText.isEmpty()) return false;
        // Keep the first implementation single-line only. Multi-line rules can
        // span large-TXT partition seams and would need a separate streaming
        // transform model.
        return findText.indexOf('\n') < 0
                && findText.indexOf('\r') < 0
                && (replacementText == null
                || (replacementText.indexOf('\n') < 0 && replacementText.indexOf('\r') < 0));
    }

    public boolean appliesTo(String targetFilePath) {
        if (!enabled || !isValid()) return false;
        if (SCOPE_FILE.equals(scope)) {
            return filePath != null && targetFilePath != null && filePath.equals(targetFilePath);
        }
        return true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id != null ? id : String.valueOf(System.currentTimeMillis()));
        obj.put("enabled", enabled);
        obj.put("findText", findText != null ? findText : "");
        obj.put("replacementText", replacementText != null ? replacementText : "");
        obj.put("caseSensitive", caseSensitive);
        obj.put("useRegex", useRegex);
        obj.put("scope", scope != null ? scope : SCOPE_ALL_TXT);
        obj.put("filePath", filePath != null ? filePath : "");
        obj.put("sourceFilePath", sourceFilePath != null ? sourceFilePath : "");
        return obj;
    }

    public static TextDisplayRule fromJson(JSONObject obj) {
        TextDisplayRule rule = new TextDisplayRule();
        if (obj == null) return rule;
        rule.id = obj.optString("id", String.valueOf(System.currentTimeMillis()));
        rule.enabled = obj.optBoolean("enabled", true);
        rule.findText = obj.optString("findText", "");
        rule.replacementText = obj.optString("replacementText", "");
        rule.caseSensitive = obj.optBoolean("caseSensitive", false);
        rule.useRegex = obj.optBoolean("useRegex", false);
        rule.scope = obj.optString("scope", SCOPE_ALL_TXT);
        rule.filePath = obj.optString("filePath", "");
        rule.sourceFilePath = obj.optString("sourceFilePath", "");
        if ((rule.sourceFilePath == null || rule.sourceFilePath.isEmpty()) && rule.filePath != null && !rule.filePath.isEmpty()) {
            rule.sourceFilePath = rule.filePath;
        }
        if (!SCOPE_FILE.equals(rule.scope)) rule.scope = SCOPE_ALL_TXT;
        return rule;
    }
}
