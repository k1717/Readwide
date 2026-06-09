package com.textview.reader.adapter;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.R;
import com.textview.reader.UiColorUtils;
import com.textview.reader.model.DrawerEntry;
import com.textview.reader.util.PrefsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for the drawer's storage shortcut list (Recent, storage roots, recent folders, ...).
 */
public class DrawerEntryAdapter extends RecyclerView.Adapter<DrawerEntryAdapter.ViewHolder> {

    public interface OnEntryClickListener {
        void onEntryClick(@NonNull DrawerEntry entry);
    }

    public interface OnEntryLongClickListener {
        boolean onEntryLongClick(@NonNull DrawerEntry entry);
    }

    private final List<DrawerEntry> entries = new ArrayList<>();
    private OnEntryClickListener listener;
    private OnEntryLongClickListener longClickListener;
    private boolean useShortcutBoxColor = false;

    public void setListener(OnEntryClickListener listener) { this.listener = listener; }
    public void setLongClickListener(OnEntryLongClickListener listener) { this.longClickListener = listener; }

    /**
     * True only for the bottom-adjacent shortcut list. Recent-folder rows must not
     * inherit shortcut-box colors, because only their header strip is theme-offset.
     */
    public void setUseShortcutBoxColor(boolean useShortcutBoxColor) {
        if (this.useShortcutBoxColor == useShortcutBoxColor) return;
        this.useShortcutBoxColor = useShortcutBoxColor;
        refreshTheme();
    }

    public void setEntries(@NonNull List<DrawerEntry> newEntries) {
        if (entries.equals(newEntries)) {
            notifyItemRangeChanged(0, entries.size());
            return;
        }

        List<DrawerEntry> old = new ArrayList<>(entries);
        List<DrawerEntry> next = new ArrayList<>(newEntries);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }

            @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                DrawerEntry a = old.get(oldItemPosition);
                DrawerEntry b = next.get(newItemPosition);
                if (a.getActionType() != b.getActionType()) return false;
                String aPath = a.getPath();
                String bPath = b.getPath();
                if (aPath != null || bPath != null) return java.util.Objects.equals(aPath, bPath);
                return java.util.Objects.equals(a.getTitle(), b.getTitle());
            }

            @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).equals(next.get(newItemPosition));
            }
        });

        entries.clear();
        entries.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_drawer_entry, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(entries.get(position));
    }

    @Override
    public int getItemCount() { return entries.size(); }

    public void refreshTheme() {
        notifyItemRangeChanged(0, entries.size());
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView title;
        TextView subtitle;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.drawer_entry_icon);
            title = itemView.findViewById(R.id.drawer_entry_title);
            subtitle = itemView.findViewById(R.id.drawer_entry_subtitle);
            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || listener == null) return;
                DrawerEntry entry = entries.get(pos);
                if (!entry.isHeader() && !entry.isDivider()) listener.onEntryClick(entry);
            });
            itemView.setOnLongClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || longClickListener == null) return false;
                DrawerEntry entry = entries.get(pos);
                if (entry.isHeader() || entry.isDivider()) return false;
                return longClickListener.onEntryLongClick(entry);
            });
        }

        void bind(@NonNull DrawerEntry entry) {
            PrefsManager prefs = PrefsManager.getInstance(itemView.getContext());
            boolean dark = prefs.shouldUseDarkColors(itemView.getContext());
            int titleColor = prefs.getMainTextColor(itemView.getContext());
            int subColor = prefs.getMainSubTextColor(itemView.getContext());
            int headerColor = prefs.getMainMutedTextColor(itemView.getContext());
            int iconBoxColor = prefs.getMainShortcutBoxColor(itemView.getContext());
            int iconColor = readableTextColorForBackground(iconBoxColor);
            int plainIconColor = prefs.shouldUseDarkColors(itemView.getContext())
                    ? prefs.getMainTextColor(itemView.getContext())
                    : Color.rgb(72, 76, 82);

            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            if (entry.isDivider()) {
                lp.height = dpToPx(8);
                itemView.setLayoutParams(lp);
                itemView.setClickable(false);
                itemView.setFocusable(false);
                itemView.setBackgroundColor(Color.TRANSPARENT);
                icon.setVisibility(View.GONE);
                subtitle.setVisibility(View.GONE);
                title.setText("");
                title.setTextSize(0);
                title.setLetterSpacing(0f);
                title.setBackgroundColor(prefs.getMainOutlineColor(itemView.getContext()));
                ViewGroup.LayoutParams titleLp = title.getLayoutParams();
                titleLp.height = Math.max(1, dpToPx(1.5f));
                title.setLayoutParams(titleLp);
                return;
            }

            ViewGroup.LayoutParams titleLp = title.getLayoutParams();
            titleLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            title.setLayoutParams(titleLp);
            title.setBackgroundColor(Color.TRANSPARENT);

            if (entry.isHeader()) {
                lp.height = dpToPx(28);
                itemView.setLayoutParams(lp);
                itemView.setClickable(false);
                itemView.setFocusable(false);
                itemView.setBackgroundColor(Color.TRANSPARENT);
                icon.setVisibility(View.GONE);
                subtitle.setVisibility(View.GONE);
                title.setText(entry.getTitle().toUpperCase(Locale.getDefault()));
                title.setTextColor(headerColor);
                title.setTextSize(11);
                title.setLetterSpacing(0.08f);
                return;
            }

            lp.height = dpToPx(48);
            itemView.setLayoutParams(lp);
            itemView.setClickable(true);
            itemView.setFocusable(true);
            itemView.setBackgroundColor(Color.TRANSPARENT);
            icon.setVisibility(View.VISIBLE);
            title.setTextSize(14);
            title.setLetterSpacing(0f);
            icon.setImageResource(entry.getIconRes());
            if (useShortcutBoxColor) {
                if (icon.getBackground() == null) {
                    icon.setBackgroundResource(R.drawable.drawer_entry_icon_bg);
                }
                icon.setBackgroundTintList(ColorStateList.valueOf(iconBoxColor));
                icon.setImageTintList(ColorStateList.valueOf(iconColor));
            } else {
                icon.setBackground(null);
                icon.setBackgroundTintList(null);
                icon.setImageTintList(ColorStateList.valueOf(plainIconColor));
            }
            title.setText(entry.getTitle());
            title.setTextColor(titleColor);
            subtitle.setTextColor(subColor);
            if (entry.getSubtitle() != null && !entry.getSubtitle().isEmpty()) {
                subtitle.setVisibility(View.VISIBLE);
                subtitle.setText(entry.getSubtitle());
            } else {
                subtitle.setVisibility(View.GONE);
            }
        }

        private int readableTextColorForBackground(int color) {
            return UiColorUtils.readableChipTextColorForBackground(color);
        }

        private int dpToPx(float dp) {
            return Math.round(dp * itemView.getResources().getDisplayMetrics().density);
        }
    }
}
