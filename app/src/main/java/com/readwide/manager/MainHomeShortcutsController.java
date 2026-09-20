package com.readwide.manager;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.model.HomeShortcut;

import java.io.File;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Compact Home-only pin strip. Does not insert fake rows into reading history. */
final class MainHomeShortcutsController {
    private final MainActivity activity;
    private final ExecutorService worker;
    private final HomeShortcut.Probe probe;
    private final ShortcutAdapter adapter = new ShortcutAdapter();
    private RecyclerView list;
    private TextView title;
    private TextView empty;
    private Future<?> refreshTask;
    private Future<?> openTask;
    // Accessed only on the UI thread; workers capture their value and post results back.
    private long refreshGeneration;
    private long openGeneration;
    private boolean closed;
    private boolean expanded;

    MainHomeShortcutsController(MainActivity activity) {
        this(activity, Executors.newSingleThreadExecutor(), HomeShortcut::inspectFileSystem);
    }

    // Dependency injection keeps asynchronous lifecycle and path probing independently testable.
    MainHomeShortcutsController(MainActivity activity, ExecutorService worker, HomeShortcut.Probe probe) {
        this.activity = activity;
        this.worker = worker;
        this.probe = probe;
    }

    void bind() {
        list = activity.findViewById(R.id.home_shortcuts_list);
        title = activity.findViewById(R.id.home_shortcuts_title);
        empty = activity.findViewById(R.id.home_shortcuts_empty);
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        title.setOnClickListener(v -> {
            if (closed) return;
            expanded = !expanded;
            if (!expanded) {
                cancelPendingOpen();
                list.stopScroll();
            }
            updateExpansionState();
        });
        updateExpansionState();
        // No filesystem/preference load here: startup still has to pass the app-lock gate.
    }

    void refresh() {
        if (closed || activity.activityDestroyed || list == null || activity.prefs == null
                || (activity.prefs.isLockEnabled() && !activity.lockChecked)) return;
        final List<String> saved = new ArrayList<>(activity.prefs.getFolderShortcuts(0));
        final long token = ++refreshGeneration;
        cancelPendingOpen();
        if (refreshTask != null) refreshTask.cancel(true);
        try {
            refreshTask = worker.submit(() -> {
                try {
                    List<HomeShortcut> rows = HomeShortcut.load(saved, probe);
                    activity.runOnUiThread(() -> {
                        if (closed || activity.activityDestroyed || token != refreshGeneration) return;
                        adapter.setRows(rows);
                        updateExpansionState();
                        refreshTheme();
                    });
                } catch (InterruptedIOException cancelled) {
                    // A newer snapshot owns the screen now; keep no partial results.
                }
            });
        } catch (RejectedExecutionException stopped) { /* Lifecycle has retired this worker. */ }
    }

    private void updateExpansionState() {
        boolean hasPins = adapter.getItemCount() > 0;
        // Hide the list itself so its old bounds no longer exclude drawer gestures.
        // Keep its adapter/layout manager attached to retain horizontal scroll position.
        list.setVisibility(expanded && hasPins ? View.VISIBLE : View.GONE);
        empty.setVisibility(expanded && !hasPins ? View.VISIBLE : View.GONE);
        String titleText = activity.getString(R.string.home_shortcuts_title);
        String marker = expanded ? "▾" : "▸";
        SpannableString label = new SpannableString(titleText + "\u00A0\u00A0" + marker);
        label.setSpan(new HomeDisclosureSpan(titleText, expanded), label.length() - 1, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(label);
        title.setContentDescription(activity.getString(expanded
                ? R.string.home_shortcuts_collapse : R.string.home_shortcuts_expand));
    }

    private void open(HomeShortcut row) {
        if (closed || !expanded || !activity.homeMode || activity.searchMode) return;
        cancelPendingOpen();
        final long token = openGeneration;
        try {
            openTask = worker.submit(() -> {
                HomeShortcut.Status status = probe.inspect(row.path);
                if (Thread.currentThread().isInterrupted()) return;
                activity.runOnUiThread(() -> {
                    if (closed || activity.activityDestroyed || token != openGeneration
                            || !activity.homeMode || activity.searchMode) return;
                    if (activity.prefs == null || !activity.prefs.isFolderShortcut(row.path)) return;
                    if (status == HomeShortcut.Status.AVAILABLE) {
                        activity.showBrowseModeFromDrawerShortcut(new File(row.path));
                    } else {
                        ShortToast.show(activity, R.string.home_shortcut_unavailable_hint);
                        refresh();
                    }
                });
            });
        } catch (RejectedExecutionException stopped) { /* No navigation after teardown. */ }
    }

    void cancelPendingOpen() {
        ++openGeneration;
        if (openTask != null) { openTask.cancel(true); openTask = null; }
    }

    void refreshTheme() {
        if (closed || list == null || activity.prefs == null) return;
        View section = activity.findViewById(R.id.home_shortcuts_section);
        section.setBackgroundColor(activity.prefs.getMainBgColor(activity));
        View header = activity.findViewById(R.id.home_shortcuts_header);
        header.setBackgroundColor(activity.prefs.getMainBarColor(activity));
        title.setTextColor(Color.WHITE);
        empty.setTextColor(activity.prefs.getMainSubTextColor(activity));
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
    }

    void close() {
        closed = true;
        ++refreshGeneration;
        cancelPendingOpen();
        if (refreshTask != null) refreshTask.cancel(true);
        worker.shutdownNow();
        if (list != null) list.setAdapter(null);
    }

    private final class ShortcutAdapter extends RecyclerView.Adapter<Holder> {
        private List<HomeShortcut> rows = Collections.emptyList();
        void setRows(List<HomeShortcut> next) {
            rows = next;
            notifyDataSetChanged(); // At most the existing preference's 30 pins; separate from Recent.
        }
        @Override public int getItemCount() { return rows.size(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_home_shortcut, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(rows.get(position));
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView path;
        private final TextView status;
        private final ImageView icon;
        private final ImageView remove;
        Holder(View view) {
            super(view);
            title = view.findViewById(R.id.home_shortcut_name);
            path = view.findViewById(R.id.home_shortcut_path);
            status = view.findViewById(R.id.home_shortcut_status);
            icon = view.findViewById(R.id.home_shortcut_icon);
            remove = view.findViewById(R.id.home_shortcut_remove);
            view.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) open(adapter.rows.get(position));
            });
            remove.setOnClickListener(v -> removePin());
            view.setOnLongClickListener(v -> { removePin(); return true; });
        }
        private void removePin() {
            int position = getBindingAdapterPosition();
            if (closed || !expanded || position == RecyclerView.NO_POSITION) return;
            cancelPendingOpen();
            activity.showShortcutRemoveDialog(new File(adapter.rows.get(position).path));
        }
        void bind(HomeShortcut row) {
            title.setText(row.title);
            path.setText(row.path);
            status.setText(R.string.home_shortcut_unavailable);
            status.setVisibility(row.status == HomeShortcut.Status.AVAILABLE ? View.GONE : View.VISIBLE);
            // Unavailable rows remain interactive: a reconnected drive can be rechecked on tap.
            itemView.setContentDescription(row.title + ", " + row.path
                    + (row.status == HomeShortcut.Status.AVAILABLE ? ""
                    : ", " + activity.getString(R.string.home_shortcut_unavailable)));
            remove.setContentDescription(activity.getString(R.string.home_shortcut_remove_description, row.title));
            if (activity.prefs == null) return;
            int fg = activity.prefs.getMainTextColor(activity);
            int sub = activity.prefs.getMainSubTextColor(activity);
            title.setTextColor(fg); path.setTextColor(sub); status.setTextColor(sub);
            icon.setImageTintList(ColorStateList.valueOf(fg));
            remove.setImageTintList(ColorStateList.valueOf(sub));
            GradientDrawable background = new GradientDrawable();
            background.setColor(activity.prefs.getMainPanelColor(activity));
            background.setCornerRadius(activity.dpToPx(12));
            background.setStroke(Math.max(1, activity.dpToPx(1)), activity.prefs.getMainOutlineColor(activity));
            itemView.setBackground(background);
        }
    }
}
