package com.textview.reader.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tracks the last reading position for a file.
 * Stored separately from bookmarks so auto-resume works independently.
 */
public class ReaderState {
    private String filePath;
    private int charPosition;
    private int scrollY;        // pixel scroll position for exact restore
    private int pageNumber;      // cached displayed page number for large-file fast restore
    private int totalPages;      // cached displayed total page count when saved
    private long fileLength;     // file size when state was saved, used to validate large-file cache
    private long lastReadAt;
    private String encoding;    // detected encoding

    public ReaderState() {}

    public ReaderState(String filePath) {
        this.filePath = filePath;
        this.lastReadAt = System.currentTimeMillis();
        this.encoding = "UTF-8";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("filePath", filePath);
        obj.put("charPosition", charPosition);
        obj.put("scrollY", scrollY);
        obj.put("pageNumber", pageNumber);
        obj.put("totalPages", totalPages);
        obj.put("fileLength", fileLength);
        obj.put("lastReadAt", lastReadAt);
        obj.put("encoding", encoding != null ? encoding : "UTF-8");
        return obj;
    }

    public static ReaderState fromJson(JSONObject obj) throws JSONException {
        ReaderState s = new ReaderState();
        s.filePath = obj.getString("filePath");
        s.charPosition = obj.optInt("charPosition", 0);
        s.scrollY = obj.optInt("scrollY", 0);
        s.pageNumber = obj.optInt("pageNumber", 0);
        s.totalPages = obj.optInt("totalPages", 0);
        s.fileLength = obj.optLong("fileLength", 0L);
        s.lastReadAt = obj.optLong("lastReadAt", 0);
        s.encoding = obj.optString("encoding", "UTF-8");
        return s;
    }

    // --- Getters / Setters ---
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getCharPosition() { return charPosition; }
    public void setCharPosition(int charPosition) { this.charPosition = charPosition; }

    public int getScrollY() { return scrollY; }
    public void setScrollY(int scrollY) { this.scrollY = scrollY; }

    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public long getFileLength() { return fileLength; }
    public void setFileLength(long fileLength) { this.fileLength = fileLength; }

    public long getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(long lastReadAt) { this.lastReadAt = lastReadAt; }

    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }
}
