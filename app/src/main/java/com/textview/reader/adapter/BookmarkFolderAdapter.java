package com.textview.reader.adapter;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.R;
import com.textview.reader.UiColorUtils;
import com.textview.reader.model.Bookmark;
import com.textview.reader.util.FileUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Date;

/**
 * Bookmark list:
 * file folder header -> expandable bookmarks inside each text file.
 */
public class BookmarkFolderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_FOLDER = 0;
    private static final int TYPE_BOOKMARK = 1;
    private static final int TYPE_SECTION = 2;

    public interface Listener {
        void onFolderClick(String filePath);
        void onFolderDelete(String filePath, String expansionKey, String fileName, int bookmarkCount);
        void onBookmarkClick(Bookmark bookmark);
        void onBookmarkDelete(Bookmark bookmark);
        void onBookmarkEdit(Bookmark bookmark);
    }

    private static class Row {
        int type;
        String filePath;
        String expansionKey;
        String fileName;
        int count;
        boolean expanded;
        boolean currentFile;
        Bookmark bookmark;

        static Row section(String title, int count) {
            Row r = new Row();
            r.type = TYPE_SECTION;
            r.fileName = title;
            r.count = count;
            return r;
        }

        static Row folder(String filePath, String expansionKey, String fileName, int count, boolean expanded, boolean currentFile) {
            Row r = new Row();
            r.type = TYPE_FOLDER;
            r.filePath = filePath;
            r.expansionKey = expansionKey;
            r.fileName = fileName;
            r.count = count;
            r.expanded = expanded;
            r.currentFile = currentFile;
            return r;
        }

        static Row bookmark(Bookmark b) {
            Row r = new Row();
            r.type = TYPE_BOOKMARK;
            r.bookmark = b;
            r.filePath = b.getFilePath();
            r.fileName = b.getFileName();
            return r;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private Listener listener;
    private int folderCount = 0;

    private int dialogBgColor = Color.TRANSPARENT;
    private int folderBgColor = Color.TRANSPARENT;
    private int folderBorderColor = Color.LTGRAY;
    private int textColor = Color.WHITE;
    private int subTextColor = Color.LTGRAY;
    private int pathTextColor = Color.GRAY;
    private String currentPageLayoutSignature = "";

    public BookmarkFolderAdapter() {
        setHasStableIds(true);
    }

    public void setThemeColors(int dialogBgColor, int textColor, int subTextColor, int folderBgColor) {
        this.dialogBgColor = dialogBgColor;
        this.textColor = textColor;

        boolean lightDialog = isLightColor(dialogBgColor);
        // Derive every bookmark-card surface from the dialog background itself.
        // TXT, PDF, EPUB, and Word sometimes pass slightly different panel colors,
        // so using folderBgColor directly made TXT bookmark cards drift in tone.
        // This keeps the file cards, section chips, and expanded bookmark rows
        // visually aligned across all viewers while still following the active theme.
        this.folderBgColor = blendColors(dialogBgColor, textColor, lightDialog ? 0.070f : 0.115f);
        this.subTextColor = blendColors(dialogBgColor, textColor, lightDialog ? 0.74f : 0.78f);
        this.pathTextColor = blendColors(dialogBgColor, textColor, lightDialog ? 0.58f : 0.66f);
        this.folderBorderColor = blendColors(dialogBgColor, textColor, lightDialog ? 0.260f : 0.360f);
        notifyDataSetChanged();
    }

    public void setCurrentPageLayoutSignature(String signature) {
        this.currentPageLayoutSignature = signature != null ? signature : "";
        notifyDataSetChanged();
    }

    private static boolean isLightColor(int color) {
        return UiColorUtils.isHalfLightColor(color);
    }

    private static int blendColors(int bottomColor, int topColor, float topAlpha) {
        return UiColorUtils.blendColors(bottomColor, topColor, topAlpha);
    }

    private GradientDrawable rowBackground(int color, int strokeColor, float radiusDp, float strokeDp, View view) {
        float density = view.getResources().getDisplayMetrics().density;

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radiusDp * density);
        bg.setStroke(Math.max(1, Math.round(strokeDp * density)), strokeColor);
        return bg;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int getFolderCount() {
        return folderCount;
    }

    public void setBookmarks(List<Bookmark> allBookmarks, Set<String> expandedFolders, String currentFilePath) {
        rows.clear();

        if (expandedFolders == null) expandedFolders = new HashSet<>();

        List<Bookmark> sorted = new ArrayList<>(allBookmarks);
        Collections.sort(sorted, (a, b) -> {
            int byType = Integer.compare(typeRank(a), typeRank(b));
            if (byType != 0) return byType;
            String af = safeFileName(a);
            String bf = safeFileName(b);
            int byName = af.compareToIgnoreCase(bf);
            if (byName != 0) return byName;
            String ap = a.getFilePath() != null ? a.getFilePath() : "";
            String bp = b.getFilePath() != null ? b.getFilePath() : "";
            int byPath = ap.compareToIgnoreCase(bp);
            if (byPath != 0) return byPath;
            return Integer.compare(a.getCharPosition(), b.getCharPosition());
        });

        folderCount = 0;
        for (int rank = 0; rank <= 4; rank++) {
            Map<String, List<Bookmark>> grouped = new LinkedHashMap<>();
            int sectionCount = 0;
            for (Bookmark b : sorted) {
                if (typeRank(b) != rank) continue;
                sectionCount++;
                String key = groupKeyForBookmark(b, rank);
                if (!grouped.containsKey(key)) grouped.put(key, new ArrayList<>());
                grouped.get(key).add(b);
            }
            if (sectionCount == 0) continue;

            rows.add(Row.section(typeTitle(rank), sectionCount));

            for (Map.Entry<String, List<Bookmark>> entry : grouped.entrySet()) {
                String groupKey = entry.getKey();
                List<Bookmark> bookmarks = entry.getValue();
                Collections.sort(bookmarks, Comparator.comparingInt(Bookmark::getCharPosition));

                Bookmark first = bookmarks.isEmpty() ? null : bookmarks.get(0);
                String path = first != null && first.getFilePath() != null ? first.getFilePath() : "";
                String fileName = first != null ? safeFileName(first) : new File(groupKey).getName();
                String expansionKey = expansionKeyFor(rank, groupKey);

                // Migrate older raw-path expansion entries into type-aware folder keys.
                // Without this, a TXT group and a PDF group can share the same raw key
                // and expand/collapse together.
                if (expandedFolders.contains(path)) {
                    expandedFolders.remove(path);
                    expandedFolders.add(expansionKey);
                }

                boolean expanded = expandedFolders.contains(expansionKey);
                boolean current = currentFilePath != null && currentFilePath.equals(path);

                rows.add(Row.folder(path, expansionKey, fileName, bookmarks.size(), expanded, current));
                folderCount++;
                if (expanded) {
                    for (Bookmark b : bookmarks) rows.add(Row.bookmark(b));
                }
            }
        }

        notifyDataSetChanged();
    }

    private static int typeRank(Bookmark b) {
        String name = safeFileName(b);
        if (FileUtils.isTextFile(name)) return 0;
        if (FileUtils.isPdfFile(name)) return 1;
        if (FileUtils.isEpubFile(name)) return 2;
        if (FileUtils.isWordFile(name)) return 3;
        return 4;
    }

    private static String typeTitle(int rank) {
        switch (rank) {
            case 0: return "TXT";
            case 1: return "PDF";
            case 2: return "EPUB";
            case 3: return "Word";
            default: return "Other";
        }
    }

    private static String safeFileName(Bookmark b) {
        if (b.getFileName() != null && !b.getFileName().isEmpty()) return b.getFileName();
        if (b.getFilePath() != null && !b.getFilePath().isEmpty()) return new File(b.getFilePath()).getName();
        return "(unknown file)";
    }

    private static String groupKeyForBookmark(Bookmark b, int rank) {
        String path = b.getFilePath();
        if (path != null && !path.trim().isEmpty()) {
            return path.trim();
        }

        // Fall back to a typed file-name key for legacy/corrupt bookmarks with
        // missing paths so TXT/PDF/EPUB/Word sections do not share one empty key.
        return "missing-path:" + rank + ":" + safeFileName(b);
    }

    private static String expansionKeyFor(int rank, String groupKey) {
        return "bookmark-folder:v2:" + rank + ":" + safeString(groupKey);
    }

    private static long stableIdFor(@NonNull Row row) {
        String key;
        switch (row.type) {
            case TYPE_SECTION:
                key = "section:" + safeString(row.fileName);
                break;
            case TYPE_FOLDER:
                key = "folder:" + safeString(row.expansionKey);
                break;
            case TYPE_BOOKMARK:
            default:
                Bookmark b = row.bookmark;
                key = "bookmark:"
                        + (b != null ? safeString(b.getId()) : "")
                        + ":"
                        + (b != null ? safeString(b.getFilePath()) : "")
                        + ":"
                        + (b != null ? b.getCharPosition() : 0);
                break;
        }
        return stableLongHash(key);
    }

    private static long stableLongHash(String key) {
        long h = 1125899906842597L;
        for (int i = 0; i < key.length(); i++) {
            h = 31L * h + key.charAt(i);
        }
        return h;
    }

    private static String safeString(String value) {
        return value != null ? value : "";
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= rows.size()) return RecyclerView.NO_ID;
        return stableIdFor(rows.get(position));
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SECTION) {
            TextView title = new TextView(parent.getContext());
            float d = parent.getResources().getDisplayMetrics().density;
            int padH = Math.round(14 * d);
            int padV = Math.round(4 * d);
            title.setPadding(padH, padV, padH, padV);
            title.setTextSize(12f);
            title.setAllCaps(false);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Math.round(2 * d), Math.round(8 * d), Math.round(2 * d), Math.round(5 * d));
            title.setLayoutParams(lp);
            return new SectionHolder(title);
        }
        if (viewType == TYPE_FOLDER) {
            View view = inflater.inflate(R.layout.item_bookmark_folder, parent, false);
            return new FolderHolder(view);
        }
        View view = inflater.inflate(R.layout.item_bookmark, parent, false);
        return new BookmarkHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof SectionHolder) {
            ((SectionHolder) holder).bind(row);
        } else if (holder instanceof FolderHolder) {
            ((FolderHolder) holder).bind(row);
        } else {
            ((BookmarkHolder) holder).bind(row.bookmark);
        }
    }

    class SectionHolder extends RecyclerView.ViewHolder {
        TextView title;
        SectionHolder(View itemView) {
            super(itemView);
            title = (TextView) itemView;
        }
        void bind(Row row) {
            title.setText(row.fileName + "  •  " + row.count);
            title.setTextColor(blendColors(dialogBgColor, textColor, 0.90f));
            boolean lightDialog = isLightColor(dialogBgColor);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(blendColors(dialogBgColor, textColor, lightDialog ? 0.070f : 0.125f));
            bg.setCornerRadius(13f * title.getResources().getDisplayMetrics().density);
            bg.setStroke(Math.max(1, Math.round(1f * title.getResources().getDisplayMetrics().density)),
                    blendColors(dialogBgColor, textColor, lightDialog ? 0.160f : 0.245f));
            title.setBackground(bg);
            title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        }
    }

    class FolderHolder extends RecyclerView.ViewHolder {
        TextView arrow, title, meta, path;
        private String boundFilePath;
        private String boundExpansionKey;
        private String boundFileName;
        private int boundBookmarkCount;

        FolderHolder(View itemView) {
            super(itemView);
            arrow = itemView.findViewById(R.id.folder_arrow);
            title = itemView.findViewById(R.id.folder_title);
            meta = itemView.findViewById(R.id.folder_meta);
            path = itemView.findViewById(R.id.folder_path);
            itemView.setOnClickListener(v -> {
                if (listener != null && boundExpansionKey != null) {
                    listener.onFolderClick(boundExpansionKey);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onFolderDelete(boundFilePath, boundExpansionKey, boundFileName, boundBookmarkCount);
                }
                return true;
            });
        }

        void bind(Row row) {
            boundFilePath = row.filePath;
            boundExpansionKey = row.expansionKey;
            boundFileName = row.fileName;
            boundBookmarkCount = row.count;
            boolean lightDialog = isLightColor(dialogBgColor);
            int cardFill = row.currentFile
                    ? blendColors(dialogBgColor, textColor, lightDialog ? 0.095f : 0.145f)
                    : folderBgColor;
            int cardStroke = row.currentFile
                    ? blendColors(dialogBgColor, textColor, lightDialog ? 0.360f : 0.460f)
                    : folderBorderColor;
            itemView.setBackground(rowBackground(cardFill, cardStroke, 11f, 1.4f, itemView));

            arrow.setText(row.expanded ? "▾" : "▸");
            arrow.setTextColor(textColor);

            String prefix = row.currentFile ? "현재 파일  •  " : "";
            title.setText(prefix + row.fileName);
            title.setTextColor(textColor);

            meta.setText(row.count + (row.count == 1 ? " bookmark" : " bookmarks"));
            meta.setTextColor(subTextColor);

            path.setText(row.filePath != null ? row.filePath : "");
            path.setTextColor(pathTextColor);
        }
    }

    class BookmarkHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView excerpt;
        TextView meta;
        ImageButton btnDelete;
        private Bookmark boundBookmark;

        BookmarkHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.bookmark_title);
            excerpt = itemView.findViewById(R.id.bookmark_excerpt);
            meta = itemView.findViewById(R.id.bookmark_meta);
            btnDelete = itemView.findViewById(R.id.bookmark_delete);

            itemView.setOnClickListener(v -> {
                if (listener != null && boundBookmark != null) {
                    listener.onBookmarkClick(boundBookmark);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (listener != null && boundBookmark != null) {
                    listener.onBookmarkEdit(boundBookmark);
                }
                return true;
            });

            btnDelete.setOnClickListener(v -> {
                if (listener != null && boundBookmark != null) {
                    listener.onBookmarkDelete(boundBookmark);
                }
            });
        }

        void bind(Bookmark bookmark) {
            boundBookmark = bookmark;
            // Expanded bookmark rows use a subtle rounded subsection card, matching
            // the visual language of the file-folder rows without overpowering them.
            boolean lightDialog = isLightColor(dialogBgColor);
            int rowFill = blendColors(dialogBgColor, textColor, lightDialog ? 0.045f : 0.080f);
            int rowStroke = blendColors(dialogBgColor, textColor, lightDialog ? 0.100f : 0.160f);
            itemView.setBackground(rowBackground(rowFill, rowStroke, 9f, 1.0f, itemView));

            String display = bookmark.getDisplayText();
            title.setText(display != null && !display.isEmpty()
                    ? display
                    : "Position " + bookmark.getCharPosition());
            title.setTextColor(textColor);

            String excerptText = bookmark.getExcerpt();
            if (excerptText != null && !excerptText.isEmpty()
                    && !excerptText.equals(display)) {
                excerpt.setText(excerptText);
                excerpt.setTextColor(subTextColor);
                excerpt.setVisibility(View.VISIBLE);
            } else {
                excerpt.setVisibility(View.GONE);
            }

            String dateStr = dateFormat.format(new Date(bookmark.getUpdatedAt()));
            int end = bookmark.getEndPosition();
            String range = end > bookmark.getCharPosition()
                    ? bookmark.getCharPosition() + " - " + end
                    : String.valueOf(bookmark.getCharPosition());
            String pageText = "";
            if (shouldShowPageMetadata(bookmark)) {
                int total = Math.max(1, bookmark.getTotalPages());
                int page = Math.max(1, Math.min(total, bookmark.getPageNumber()));
                pageText = "Page " + page + " / " + total + "  •  ";
            }
            meta.setText(pageText + "Position " + range + "  •  " + dateStr);
            meta.setTextColor(pathTextColor);

            btnDelete.setColorFilter(subTextColor);
            btnDelete.setBackgroundColor(Color.TRANSPARENT);
        }

        private boolean shouldShowPageMetadata(Bookmark bookmark) {
            if (bookmark == null || bookmark.getPageNumber() <= 0 || bookmark.getTotalPages() <= 0) {
                return false;
            }

            String name = bookmark.getFileName();
            if (name == null || name.trim().isEmpty()) {
                name = bookmark.getFilePath();
            }
            boolean txtBookmark = name != null && FileUtils.isTextFile(name);
            if (!txtBookmark) {
                return true;
            }

            // TXT page numbers depend on the active large-file partition/page model.
            // Do not display a saved TXT page total unless it was produced by the
            // currently active model. This prevents old 4000/400 vs 12000/600 totals
            // from appearing as if they were current.
            String savedSignature = bookmark.getPageLayoutSignature() != null
                    ? bookmark.getPageLayoutSignature()
                    : "";
            return !currentPageLayoutSignature.isEmpty()
                    && currentPageLayoutSignature.equals(savedSignature);
        }
    }
}
