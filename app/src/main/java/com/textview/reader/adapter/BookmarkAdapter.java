package com.textview.reader.adapter;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.R;
import com.textview.reader.model.Bookmark;
import com.textview.reader.util.FileUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bookmark list with top-level subsections by file type: TXT, PDF, EPUB, Word.
 */
public class BookmarkAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SECTION = 0;
    private static final int TYPE_BOOKMARK = 1;

    public interface OnBookmarkClickListener {
        void onBookmarkClick(Bookmark bookmark);
        void onBookmarkDelete(Bookmark bookmark);
        void onBookmarkEdit(Bookmark bookmark);
    }

    private static class Row {
        int type;
        String sectionTitle;
        int count;
        Bookmark bookmark;

        static Row section(String title, int count) {
            Row row = new Row();
            row.type = TYPE_SECTION;
            row.sectionTitle = title;
            row.count = count;
            return row;
        }

        static Row bookmark(Bookmark bookmark) {
            Row row = new Row();
            row.type = TYPE_BOOKMARK;
            row.bookmark = bookmark;
            return row;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private OnBookmarkClickListener listener;
    private boolean showFileName = false;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public void setListener(OnBookmarkClickListener listener) {
        this.listener = listener;
    }

    public void setShowFileName(boolean show) {
        this.showFileName = show;
    }

    public void setBookmarks(List<Bookmark> list) {
        rows.clear();

        Map<String, List<Bookmark>> grouped = new LinkedHashMap<>();
        grouped.put("TXT", new ArrayList<>());
        grouped.put("PDF", new ArrayList<>());
        grouped.put("EPUB", new ArrayList<>());
        grouped.put("Word", new ArrayList<>());

        List<Bookmark> sorted = new ArrayList<>(list);
        Collections.sort(sorted, (a, b) -> {
            int byType = Integer.compare(typeOrder(a), typeOrder(b));
            if (byType != 0) return byType;
            String af = safeFileName(a);
            String bf = safeFileName(b);
            int byFile = af.compareToIgnoreCase(bf);
            if (byFile != 0) return byFile;
            return Integer.compare(a.getCharPosition(), b.getCharPosition());
        });

        for (Bookmark bookmark : sorted) {
            String type = displayType(bookmark);
            List<Bookmark> bucket = grouped.get(type);
            if (bucket == null) {
                bucket = new ArrayList<>();
                grouped.put(type, bucket);
            }
            bucket.add(bookmark);
        }

        for (Map.Entry<String, List<Bookmark>> entry : grouped.entrySet()) {
            List<Bookmark> bucket = entry.getValue();
            if (bucket.isEmpty()) continue;
            rows.add(Row.section(entry.getKey(), bucket.size()));
            for (Bookmark bookmark : bucket) rows.add(Row.bookmark(bookmark));
        }

        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SECTION) {
            TextView view = new TextView(parent.getContext());
            int hPad = dp(parent, 16);
            int vPad = dp(parent, 12);
            view.setPadding(hPad, vPad, hPad, dp(parent, 6));
            view.setTextSize(13f);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setAllCaps(false);
            return new SectionHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bookmark, parent, false);
        return new BookmarkHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof SectionHolder) {
            ((SectionHolder) holder).bind(row);
        } else if (holder instanceof BookmarkHolder) {
            ((BookmarkHolder) holder).bind(row.bookmark);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private static int dp(ViewGroup parent, int dp) {
        return Math.round(dp * parent.getResources().getDisplayMetrics().density);
    }

    private static boolean isDark(View view) {
        return (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int typeOrder(Bookmark b) {
        String type = displayType(b);
        if ("TXT".equals(type)) return 0;
        if ("PDF".equals(type)) return 1;
        if ("EPUB".equals(type)) return 2;
        if ("Word".equals(type)) return 3;
        return 4;
    }

    private static String displayType(Bookmark b) {
        String name = safeFileName(b);
        if (FileUtils.isPdfFile(name)) return "PDF";
        if (FileUtils.isEpubFile(name)) return "EPUB";
        if (FileUtils.isWordFile(name)) return "Word";
        return "TXT";
    }

    private static String safeFileName(Bookmark b) {
        if (b.getFileName() != null && !b.getFileName().isEmpty()) return b.getFileName();
        if (b.getFilePath() != null && !b.getFilePath().isEmpty()) {
            return new java.io.File(b.getFilePath()).getName();
        }
        return "(unknown file)";
    }

    class SectionHolder extends RecyclerView.ViewHolder {
        TextView title;

        SectionHolder(@NonNull View itemView) {
            super(itemView);
            title = (TextView) itemView;
        }

        void bind(Row row) {
            boolean dark = isDark(itemView);
            title.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
            title.setTextColor(dark ? Color.rgb(138, 180, 248) : Color.rgb(26, 115, 232));
            title.setText(row.sectionTitle + "  •  " + row.count);
        }
    }

    class BookmarkHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView excerpt;
        TextView meta;
        ImageButton btnDelete;

        BookmarkHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.bookmark_title);
            excerpt = itemView.findViewById(R.id.bookmark_excerpt);
            meta = itemView.findViewById(R.id.bookmark_meta);
            btnDelete = itemView.findViewById(R.id.bookmark_delete);

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    Row row = rows.get(pos);
                    if (row.type == TYPE_BOOKMARK) listener.onBookmarkClick(row.bookmark);
                }
            });

            itemView.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    Row row = rows.get(pos);
                    if (row.type == TYPE_BOOKMARK) listener.onBookmarkEdit(row.bookmark);
                }
                return true;
            });

            btnDelete.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    Row row = rows.get(pos);
                    if (row.type == TYPE_BOOKMARK) listener.onBookmarkDelete(row.bookmark);
                }
            });
        }

        void bind(Bookmark bookmark) {
            boolean dark = isDark(itemView);
            int bg = dark ? Color.BLACK : Color.WHITE;
            int fg = dark ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36);
            int sub = dark ? Color.rgb(189, 193, 198) : Color.rgb(95, 99, 104);
            int metaColor = dark ? Color.rgb(154, 160, 166) : Color.rgb(117, 117, 117);

            itemView.setBackgroundColor(bg);

            String main = bookmark.getExcerpt();
            if (main == null || main.trim().isEmpty()) main = bookmark.getDisplayText();
            if (main == null || main.trim().isEmpty()) main = positionLabel(bookmark);
            title.setText(main.trim());

            String memo = bookmark.getLabel();
            if (showFileName) {
                String file = safeFileName(bookmark);
                if (memo != null && !memo.isEmpty()) {
                    excerpt.setText(file + "  •  Memo: " + memo);
                } else {
                    excerpt.setText(file);
                }
                excerpt.setVisibility(file.isEmpty() && (memo == null || memo.isEmpty()) ? View.GONE : View.VISIBLE);
            } else if (memo != null && !memo.isEmpty()) {
                excerpt.setText("Memo: " + memo);
                excerpt.setVisibility(View.VISIBLE);
            } else {
                excerpt.setVisibility(View.GONE);
            }

            String dateStr = dateFormat.format(new Date(bookmark.getUpdatedAt()));
            meta.setText(positionLabel(bookmark) + "  •  " + dateStr);

            title.setTextColor(fg);
            excerpt.setTextColor(sub);
            meta.setTextColor(metaColor);
            btnDelete.setColorFilter(metaColor);
        }

        private String positionLabel(Bookmark bookmark) {
            String type = displayType(bookmark);
            int oneBased = Math.max(1, bookmark.getCharPosition() + 1);
            if ("PDF".equals(type)) return "PDF page " + oneBased;
            if ("EPUB".equals(type)) return "EPUB section " + oneBased;
            if ("Word".equals(type)) return "Word page " + oneBased;
            int start = Math.max(0, bookmark.getCharPosition());
            int end = Math.max(start, bookmark.getEndPosition());
            return "Line " + bookmark.getLineNumber() + "  •  Pos " + start + "-" + end;
        }
    }
}
