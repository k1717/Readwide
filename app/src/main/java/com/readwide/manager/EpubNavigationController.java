package com.readwide.manager;

import android.app.Dialog;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Owns optional EPUB popups, never the underlying reading position or WebView. */
final class EpubNavigationController {
    private final DocumentPageActivity activity;
    private Dialog dialog;
    private int request;
    private List<EpubNavigationParser.Entry> contents;
    private boolean fallbackContents;

    EpubNavigationController(DocumentPageActivity activity) { this.activity = activity; }

    void close() {
        request++;
        contents = null;
        fallbackContents = false;
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private boolean alive(DocumentZipSession session, int generation, int token) {
        return !activity.activityDestroyed && !activity.isFinishing()
                && activity.loadGeneration == generation && token == request
                && activity.epubNavigationSession() == session;
    }

    void showContents() {
        if (activity.activityDestroyed || !"EPUB".equals(activity.docType)) return;
        if (dialog != null) dialog.dismiss();
        // An optional parser can also return an empty result after a transient
        // resource failure. Reopening retries that path, not just submission.
        if (fallbackContents) contents = null;
        final int token = ++request;
        final int generation = activity.loadGeneration;
        final DocumentZipSession session = activity.epubNavigationSession();
        if (session == null) return;
        LinearLayout box = activity.makeDialogBox();
        box.addView(activity.makeDialogTitle(activity.getString(R.string.txt_contents)));
        EditText search = activity.makeDialogInput(activity.getString(R.string.search));
        box.addView(search);
        TextView status = body(activity.getString(R.string.loading));
        box.addView(status);
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, dp(8), 0, dp(8));
        box.addView(list, new LinearLayout.LayoutParams(-1, listHeight()));
        ContentsAdapter adapter = new ContentsAdapter(session, generation, token);
        list.setAdapter(adapter);
        show(box, R.string.close, true);
        final Dialog opened = dialog;
        final boolean[] initialPositionPending = {true};
        Runnable filter = () -> {
            if (!alive(session, generation, token) || dialog != opened || contents == null) return;
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            adapter.entries.clear();
            for (EpubNavigationParser.Entry entry : contents) {
                if (query.isEmpty() || entry.title.toLowerCase(Locale.ROOT).contains(query)) adapter.entries.add(entry);
            }
            adapter.notifyDataSetChanged();
            if (initialPositionPending[0] && query.isEmpty()) {
                initialPositionPending[0] = false;
                // The current source section is known; do not invent a fragment position.
                for (int i = 0; i < adapter.entries.size(); i++) {
                    EpubNavigationParser.Entry entry = adapter.entries.get(i);
                    if (entry.target != null && activity.findEpubPageBySourcePath(entry.target.path)
                            == activity.currentPage) {
                        ((LinearLayoutManager) list.getLayoutManager())
                                .scrollToPositionWithOffset(Math.max(0, i - 1), 0);
                        break;
                    }
                }
            }
            status.setText(adapter.entries.isEmpty() ? R.string.epub_contents_empty : R.string.epub_contents_fallback);
            status.setVisibility(adapter.entries.isEmpty() || fallbackContents ? View.VISIBLE : View.GONE);
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                initialPositionPending[0] = false;
                filter.run();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        if (contents != null) { filter.run(); return; }
        Set<String> paths = new HashSet<>();
        List<EpubNavigationParser.Entry> fallback = new ArrayList<>();
        for (DocumentPageActivity.Page page : activity.pages) {
            if (page.sourcePath == null || page.sourcePath.isEmpty()) continue;
            paths.add(page.sourcePath);
            fallback.add(new EpubNavigationParser.Entry(page.title,
                    new EpubNavigationParser.Target(page.sourcePath, ""), 0));
        }
        DocumentArchiveUtils.EpubPackageResources metadata = activity.epubPackageResources;
        Consumer<List<EpubNavigationParser.Entry>> complete = result -> {
            if (!alive(session, generation, token) || dialog != opened) return;
            fallbackContents = result.isEmpty();
            contents = fallbackContents ? fallback : result;
            filter.run();
        };
        submitNavigationTask(() -> {
            List<EpubNavigationParser.Entry> parsed;
            try (DocumentZipSession.Lease lease = session.acquire()) {
                if (lease == null) return;
                parsed = EpubNavigationParser.contents(lease.zip(), metadata, paths);
            }
            final List<EpubNavigationParser.Entry> result = parsed;
            activity.runOnUiThread(() -> complete.accept(result));
        }, () -> complete.accept(Collections.emptyList()));
    }

    void followLink(WebView source, EpubNavigationParser.Target target, Runnable ordinaryNavigation) {
        final int token = ++request;
        final int generation = activity.loadGeneration;
        final int pageGeneration = activity.documentAnchorPageGeneration;
        final int primaryLoad = activity.primaryDocumentPageLoadGeneration;
        final int rightLoad = activity.rightDocumentPageLoadGeneration;
        final int interaction = activity.documentInteractionGeneration;
        final int sourcePage = activity.documentPageIndexForWebView(source);
        final String sourcePath = activity.pages.get(sourcePage).sourcePath;
        final int x = source.getScrollX(), y = source.getScrollY();
        final DocumentZipSession session = activity.epubNavigationSession();
        if (session == null) return;
        Consumer<String> complete = result -> {
            if (!alive(session, generation, token)
                    || (source != activity.webView && source != activity.rightWebView)
                    || activity.primaryDocumentPageLoadGeneration != primaryLoad
                    || activity.rightDocumentPageLoadGeneration != rightLoad
                    || activity.documentAnchorPageGeneration != pageGeneration
                    || activity.documentInteractionGeneration != interaction
                    || activity.documentPageIndexForWebView(source) != sourcePage
                    || source.getScrollX() != x || source.getScrollY() != y) return;
            if (result == null) { ordinaryNavigation.run(); return; }
            if (dialog != null) dialog.dismiss();
            LinearLayout box = activity.makeDialogBox();
            box.addView(activity.makeDialogTitle(activity.getString(R.string.epub_footnote)));
            ScrollView scroll = new ScrollView(activity);
            TextView text = body(result);
            text.setTextIsSelectable(true);
            scroll.addView(text, new ScrollView.LayoutParams(-1, -2));
            box.addView(scroll, new LinearLayout.LayoutParams(-1, listHeight()));
            // Closing/back/outside-tap leaves both reader panes untouched.
            show(box, R.string.epub_return_to_text, false);
        };
        submitNavigationTask(() -> {
            String note;
            try (DocumentZipSession.Lease lease = session.acquire()) {
                if (lease == null) return;
                note = EpubNavigationParser.footnote(lease.zip(), target, sourcePath);
            }
            final String result = note;
            activity.runOnUiThread(() -> complete.accept(result));
        }, () -> complete.accept(null));
    }

    private void submitNavigationTask(Runnable work, Runnable failure) {
        if (activity.activityDestroyed) return;
        try {
            // The general document submitter intentionally drops late tasks.
            // Optional navigation needs a completion when a live request fails.
            activity.executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException | OutOfMemoryError | StackOverflowError ignored) {
                    postNavigationFailure(failure);
                }
            });
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError ignored) {
            postNavigationFailure(failure);
        }
    }

    private void postNavigationFailure(Runnable failure) {
        try {
            // Both completion paths retain the original dialog/pane ownership
            // checks. Retired requests cannot navigate or replace newer UI.
            activity.runOnUiThread(() -> {
                try {
                    failure.run();
                } catch (RuntimeException | OutOfMemoryError | StackOverflowError ignored) {
                    // Recovery UI is best effort under continuing exhaustion.
                }
            });
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError ignored) {
            // A dying UI queue or continued exhaustion cannot be retried here.
        }
    }

    private void show(LinearLayout box, int closeLabel, boolean keyboard) {
        final Dialog[] owned = new Dialog[1];
        box.addView(activity.makeDialogActionRow(activity.getString(closeLabel), () -> owned[0].dismiss()));
        owned[0] = activity.createStablePositionedDialog(box,
                DocumentPageActivity.DOCUMENT_TOOLBAR_POPUP_Y_DP, keyboard, false);
        dialog = owned[0];
        dialog.setOnDismissListener(d -> {
            if (dialog == owned[0]) { dialog = null; request++; }
        });
        dialog.show();
    }

    private TextView body(String value) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(activity.dialogFg());
        text.setTextSize(16);
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        return text;
    }

    private int dp(int value) { return activity.dpToPx(value); }
    private int listHeight() {
        return Math.min(dp(360), Math.max(dp(100),
                activity.getResources().getDisplayMetrics().heightPixels / 3));
    }

    private final class ContentsAdapter extends RecyclerView.Adapter<ContentsHolder> {
        final List<EpubNavigationParser.Entry> entries = new ArrayList<>();
        final DocumentZipSession session;
        final int generation, token;
        ContentsAdapter(DocumentZipSession session, int generation, int token) {
            this.session = session;
            this.generation = generation;
            this.token = token;
        }
        @NonNull @Override public ContentsHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout frame = new FrameLayout(activity);
            frame.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new ContentsHolder(frame);
        }
        @Override public void onBindViewHolder(@NonNull ContentsHolder holder, int position) {
            EpubNavigationParser.Entry entry = entries.get(position);
            int page = entry.target == null ? -1 : activity.findEpubPageBySourcePath(entry.target.path);
            TextView row = activity.makeDocumentSelectionRow(entry.title, activity.dialogFg(),
                    page == activity.currentPage || (page >= 0 && page == activity.documentRightSpreadPageIndex()));
            row.setPaddingRelative(dp(18 + Math.min(6, entry.depth) * 12), dp(8), dp(18), dp(8));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            holder.frame.removeAllViews();
            holder.frame.addView(row, lp);
            if (entry.target != null) row.setOnClickListener(v -> {
                if (!alive(session, generation, token) || dialog == null || activity.webView == null) return;
                dialog.dismiss();
                Uri uri = new Uri.Builder().scheme("https").authority(activity.localDocumentHost())
                        .path(DocumentPageActivity.EPUB_PREFIX + entry.target.path)
                        .fragment(entry.target.fragment).build();
                activity.navigateEpubInternal(activity.webView, uri);
            });
        }
        @Override public int getItemCount() { return entries.size(); }
    }

    private static final class ContentsHolder extends RecyclerView.ViewHolder {
        final FrameLayout frame;
        ContentsHolder(FrameLayout frame) { super(frame); this.frame = frame; }
    }
}
