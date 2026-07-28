package com.readwide.manager.adapter;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.R;
import com.readwide.manager.model.SafDocumentEntry;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PrefsManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lightweight URI-row adapter used only by the SAF compatibility browser. */
public final class SafDocumentAdapter
        extends RecyclerView.Adapter<SafDocumentAdapter.ViewHolder> {

    public interface Listener {
        void onEntryClick(@NonNull SafDocumentEntry entry);
    }

    private final List<SafDocumentEntry> entries = new ArrayList<>();
    private final Listener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public SafDocumentAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void setEntries(@NonNull List<SafDocumentEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(entries.get(position));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    final class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView name;
        private final TextView info;
        private final TextView path;
        private final TextView progress;
        private SafDocumentEntry bound;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.file_icon);
            name = itemView.findViewById(R.id.file_name);
            info = itemView.findViewById(R.id.file_info);
            path = itemView.findViewById(R.id.file_path);
            progress = itemView.findViewById(R.id.file_progress);
            View marker = itemView.findViewById(R.id.file_selection_marker);
            if (marker != null) marker.setVisibility(View.GONE);
            if (path != null) path.setVisibility(View.GONE);
            if (progress != null) progress.setVisibility(View.GONE);
            itemView.setOnClickListener(v -> {
                SafDocumentEntry entry = bound;
                if (entry != null) listener.onEntryClick(entry);
            });
        }

        void bind(@NonNull SafDocumentEntry entry) {
            bound = entry;
            PrefsManager prefs = PrefsManager.getInstance(itemView.getContext());
            int primary = prefs.getMainTextColor(itemView.getContext());
            int secondary = prefs.getMainSubTextColor(itemView.getContext());
            boolean dark = prefs.shouldUseDarkColors(itemView.getContext());
            int iconTint = dark ? primary : Color.rgb(72, 76, 82);

            name.setTextColor(primary);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(entry.getName());
            info.setTextColor(secondary);
            info.setSingleLine(true);
            info.setEllipsize(TextUtils.TruncateAt.END);
            icon.setImageTintList(ColorStateList.valueOf(iconTint));

            if (entry.isDirectory()) {
                icon.setImageResource(R.drawable.ic_folder);
                info.setText(R.string.folder);
            } else {
                icon.setImageResource(iconResForFile(entry.getName()));
                String size = FileUtils.formatFileSize(entry.getSize());
                String date = entry.getLastModified() > 0L
                        ? dateFormat.format(new Date(entry.getLastModified())) : "";
                String type = FileUtils.getReadableFileType(entry.getName());
                info.setText(date.isEmpty()
                        ? String.format(Locale.getDefault(), "%s  •  %s", type, size)
                        : String.format(Locale.getDefault(), "%s  •  %s  •  %s", type, size, date));
            }
        }
    }

    private static int iconResForFile(@NonNull String fileName) {
        if (FileUtils.isPdfFile(fileName)) return R.drawable.ic_file_pdf;
        if (FileUtils.isEpubFile(fileName)) return R.drawable.ic_file_epub;
        if (FileUtils.isWordOrHwpFile(fileName)) return R.drawable.ic_file_document;
        if (FileUtils.isArchiveFile(fileName)) return R.drawable.ic_file_archive;
        if (FileUtils.isImageFile(fileName)) return R.drawable.ic_file_image;
        if (FileUtils.isVideoFile(fileName)) return R.drawable.ic_file_video;
        if (FileUtils.isAudioFile(fileName)) return R.drawable.ic_file_audio;
        if (FileUtils.isApkFile(fileName)) return R.drawable.ic_file_apk;
        return R.drawable.ic_text_file;
    }
}
