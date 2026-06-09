package com.textview.reader.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A group of bookmarks, typically representing one file.
 * Supports user-created custom groups as well.
 */
public class BookmarkGroup {
    private String id;
    private String name;        // group display name (filename or custom)
    private String filePath;    // null for custom groups spanning multiple files
    private boolean expanded;
    private List<Bookmark> bookmarks;

    public BookmarkGroup() {
        this.id = UUID.randomUUID().toString();
        this.bookmarks = new ArrayList<>();
        this.expanded = true;
    }

    public BookmarkGroup(String name, String filePath) {
        this();
        this.name = name;
        this.filePath = filePath;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("filePath", filePath != null ? filePath : "");
        obj.put("expanded", expanded);
        JSONArray arr = new JSONArray();
        for (Bookmark b : bookmarks) {
            arr.put(b.toJson());
        }
        obj.put("bookmarks", arr);
        return obj;
    }

    public static BookmarkGroup fromJson(JSONObject obj) throws JSONException {
        BookmarkGroup g = new BookmarkGroup();
        g.id = obj.optString("id", UUID.randomUUID().toString());
        g.name = obj.optString("name", "Unnamed");
        String fp = obj.optString("filePath", "");
        g.filePath = fp.isEmpty() ? null : fp;
        g.expanded = obj.optBoolean("expanded", true);
        JSONArray arr = obj.optJSONArray("bookmarks");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                g.bookmarks.add(Bookmark.fromJson(arr.getJSONObject(i)));
            }
        }
        return g;
    }

    public void addBookmark(Bookmark b) { bookmarks.add(b); }
    public void removeBookmark(String bookmarkId) {
        bookmarks.removeIf(b -> b.getId().equals(bookmarkId));
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String fp) { this.filePath = fp; }
    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }
    public List<Bookmark> getBookmarks() { return bookmarks; }
    public int getBookmarkCount() { return bookmarks.size(); }
}
