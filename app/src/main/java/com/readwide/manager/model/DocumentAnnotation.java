package com.readwide.manager.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * A note or highlight attached to a source position without modifying the source
 * document. Positions are absolute TXT offsets or Markdown source offsets.
 */
public final class DocumentAnnotation {
    public static final String TYPE_NOTE = "note";
    public static final String TYPE_HIGHLIGHT = "highlight";

    private String id = UUID.randomUUID().toString();
    private String filePath = "";
    private String fileName = "";
    private String documentType = "";
    private String type = TYPE_NOTE;
    private int startPosition;
    private int endPosition;
    private int lineNumber;
    private String selectedText = "";
    private String note = "";
    private String anchorTextBefore = "";
    private String anchorTextAfter = "";
    private long createdAt = System.currentTimeMillis();
    private long updatedAt = createdAt;

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("filePath", filePath);
        obj.put("fileName", fileName);
        obj.put("documentType", documentType);
        obj.put("type", type);
        obj.put("startPosition", startPosition);
        obj.put("endPosition", endPosition);
        obj.put("lineNumber", lineNumber);
        obj.put("selectedText", selectedText);
        obj.put("note", note);
        obj.put("anchorTextBefore", anchorTextBefore);
        obj.put("anchorTextAfter", anchorTextAfter);
        obj.put("createdAt", createdAt);
        obj.put("updatedAt", updatedAt);
        return obj;
    }

    public static DocumentAnnotation fromJson(JSONObject obj) {
        DocumentAnnotation annotation = new DocumentAnnotation();
        annotation.id = nonBlank(obj.optString("id", ""), annotation.id);
        annotation.filePath = obj.optString("filePath", "");
        annotation.fileName = obj.optString("fileName", "");
        annotation.documentType = obj.optString("documentType", "");
        String storedType = obj.optString("type", TYPE_NOTE);
        annotation.type = TYPE_HIGHLIGHT.equals(storedType) ? TYPE_HIGHLIGHT : TYPE_NOTE;
        annotation.startPosition = Math.max(0, obj.optInt("startPosition", 0));
        annotation.endPosition = Math.max(annotation.startPosition,
                obj.optInt("endPosition", annotation.startPosition));
        annotation.lineNumber = Math.max(1, obj.optInt("lineNumber", 1));
        annotation.selectedText = obj.optString("selectedText", "");
        annotation.note = obj.optString("note", "");
        annotation.anchorTextBefore = obj.optString("anchorTextBefore", "");
        annotation.anchorTextAfter = obj.optString("anchorTextAfter", "");
        annotation.createdAt = Math.max(0L, obj.optLong("createdAt", System.currentTimeMillis()));
        annotation.updatedAt = Math.max(annotation.createdAt,
                obj.optLong("updatedAt", annotation.createdAt));
        return annotation;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    public void touch() { updatedAt = System.currentTimeMillis(); }
    public boolean isHighlight() { return TYPE_HIGHLIGHT.equals(type); }

    public String getId() { return id; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath != null ? filePath : ""; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName != null ? fileName : ""; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType != null ? documentType : ""; }
    public String getType() { return type; }
    public void setType(String type) { this.type = TYPE_HIGHLIGHT.equals(type) ? TYPE_HIGHLIGHT : TYPE_NOTE; }
    public int getStartPosition() { return startPosition; }
    public void setStartPosition(int startPosition) { this.startPosition = Math.max(0, startPosition); }
    public int getEndPosition() { return endPosition; }
    public void setEndPosition(int endPosition) { this.endPosition = Math.max(startPosition, endPosition); }
    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = Math.max(1, lineNumber); }
    public String getSelectedText() { return selectedText; }
    public void setSelectedText(String selectedText) { this.selectedText = selectedText != null ? selectedText : ""; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note != null ? note : ""; }
    public String getAnchorTextBefore() { return anchorTextBefore; }
    public void setAnchorTextBefore(String anchorTextBefore) { this.anchorTextBefore = anchorTextBefore != null ? anchorTextBefore : ""; }
    public String getAnchorTextAfter() { return anchorTextAfter; }
    public void setAnchorTextAfter(String anchorTextAfter) { this.anchorTextAfter = anchorTextAfter != null ? anchorTextAfter : ""; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
}
