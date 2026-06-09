package com.textview.reader.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.model.TextChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for long text files.
 * Uses smaller chunks and cheap text layout settings to reduce drag lag.
 */
public class TextChunkAdapter extends RecyclerView.Adapter<TextChunkAdapter.ChunkViewHolder> {
    private final List<TextChunk> chunks = new ArrayList<>();

    private float textSizeSp = 18f;
    private float lineSpacingMultiplier = 1.5f;
    private int textColor = 0xFFE0E0E0;
    private int marginHorizontalPx = 24;
    private int marginVerticalPx = 8;
    private Typeface typeface = Typeface.DEFAULT;

    public TextChunkAdapter() {
        setHasStableIds(true);
    }

    public void setChunks(List<TextChunk> newChunks) {
        List<TextChunk> old = new ArrayList<>(chunks);
        List<TextChunk> next = new ArrayList<>();
        if (newChunks != null) next.addAll(newChunks);

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }

            @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                TextChunk a = old.get(oldItemPosition);
                TextChunk b = next.get(newItemPosition);
                return a.getIndex() == b.getIndex()
                        && a.getStartChar() == b.getStartChar();
            }

            @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                TextChunk a = old.get(oldItemPosition);
                TextChunk b = next.get(newItemPosition);
                String aText = a.getText();
                String bText = b.getText();
                return java.util.Objects.equals(aText, bText);
            }
        });

        chunks.clear();
        chunks.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    public List<TextChunk> getChunks() { return chunks; }

    public TextChunk getChunk(int position) {
        if (position < 0 || position >= chunks.size()) return null;
        return chunks.get(position);
    }

    public void setTextStyle(float textSizeSp,
                             float lineSpacingMultiplier,
                             int textColor,
                             int marginHorizontalPx,
                             int marginVerticalPx,
                             Typeface typeface) {
        this.textSizeSp = textSizeSp;
        this.lineSpacingMultiplier = lineSpacingMultiplier;
        this.textColor = textColor;
        this.marginHorizontalPx = marginHorizontalPx;
        this.marginVerticalPx = Math.max(0, marginVerticalPx / 2);
        this.typeface = typeface != null ? typeface : Typeface.DEFAULT;
        notifyItemRangeChanged(0, chunks.size());
    }

    @SuppressLint("WrongConstant")
    @NonNull
    @Override
    public ChunkViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        TextView tv = new TextView(context);
        tv.setId(android.R.id.text1);
        tv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setIncludeFontPadding(true);
        tv.setTextIsSelectable(false);
        tv.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        tv.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        tv.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        tv.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        return new ChunkViewHolder(tv);
    }

    @Override
    public void onBindViewHolder(@NonNull ChunkViewHolder holder, int position) {
        TextChunk chunk = chunks.get(position);
        TextView tv = holder.textView;
        tv.setText(chunk.getText());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        tv.setLineSpacing(0, lineSpacingMultiplier);
        tv.setTextColor(textColor);
        tv.setTypeface(typeface);
        tv.setPadding(marginHorizontalPx, marginVerticalPx, marginHorizontalPx, marginVerticalPx);
    }

    @Override public long getItemId(int position) {
        TextChunk c = getChunk(position);
        return c != null ? c.getStartChar() : RecyclerView.NO_ID;
    }

    @Override public int getItemCount() { return chunks.size(); }

    public static class ChunkViewHolder extends RecyclerView.ViewHolder {
        public final TextView textView;
        public ChunkViewHolder(@NonNull TextView itemView) {
            super(itemView);
            this.textView = itemView;
        }
    }
}
