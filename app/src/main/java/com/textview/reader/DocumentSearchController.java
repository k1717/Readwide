package com.textview.reader;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.Locale;

final class DocumentSearchController {
    private final DocumentPageActivity activity;

    DocumentSearchController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
    }

    void showDocumentSearchDialog() {
        FrameLayout targetContainer = getDocumentSearchPanelTargetContainer();
        if (targetContainer == null) return;

        if (isDocumentSearchPanelVisible() && activity.documentSearchInputView != null) {
            focusDocumentSearchInput(activity.documentSearchInputView);
            return;
        }

        final int fg = dialogFg();
        final int sub = dialogSub();

        clearDocumentSearchPanelContainers();
        targetContainer.setBackgroundColor(shouldOverlayDocumentSearchPanel() ? Color.TRANSPARENT : activity.readerBg);

        FrameLayout titleBox = new FrameLayout(activity);
        titleBox.setPadding(dpToPx(18), dpToPx(10), dpToPx(18), dpToPx(4));
        titleBox.setBackgroundColor(Color.TRANSPARENT);

        TextView title = new TextView(activity);
        title.setText(getString(R.string.find_in_text));
        title.setTextColor(fg);
        title.setTextSize(20f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        titleBox.addView(title, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        TextView matchStatus = new TextView(activity);
        matchStatus.setText("0 / 0");
        matchStatus.setTextColor(sub);
        matchStatus.setTextSize(12f);
        matchStatus.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        titleBox.addView(matchStatus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.END));
        activity.documentSearchStatusView = matchStatus;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(dpToPx(18), dpToPx(8), dpToPx(18), dpToPx(6));

        EditText input = activity.makeDialogInput(getString(R.string.search_text_hint));
        activity.documentSearchInputView = input;
        String rememberedQuery = activity.activeDocumentSearchQuery;
        if ((rememberedQuery == null || rememberedQuery.isEmpty()) && activity.prefs != null) {
            rememberedQuery = activity.prefs.getLastReaderSearchQuery();
        }
        if (rememberedQuery == null) rememberedQuery = "";
        input.setText(rememberedQuery);
        if (!rememberedQuery.isEmpty()) {
            input.setSelection(input.getText().length());
            activity.activeDocumentSearchTotal = countDocumentMatches(rememberedQuery);
            updateDocumentSearchStatus(matchStatus);
        }
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(50)));

        TextView hint = new TextView(activity);
        hint.setText(getString(R.string.search_hint_multiple));
        hint.setTextColor(sub);
        hint.setTextSize(12f);
        hint.setGravity(Gravity.START);
        hint.setPadding(0, dpToPx(5), 0, dpToPx(5));
        box.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dpToPx(4), 0, 0);

        TextView prevButton = makeDocumentSearchDialogButton(getString(R.string.find_previous), fg);
        TextView closeButton = makeDocumentSearchDialogButton(getString(R.string.close), fg);
        TextView nextButton = makeDocumentSearchDialogButton(getString(R.string.find_next), fg);

        buttons.addView(prevButton, new LinearLayout.LayoutParams(0, dpToPx(42), 1f));
        buttons.addView(closeButton, new LinearLayout.LayoutParams(0, dpToPx(42), 1f));
        buttons.addView(nextButton, new LinearLayout.LayoutParams(0, dpToPx(42), 1f));
        box.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout panel = makeDialogBox();
        panel.setPadding(0, 0, 0, dpToPx(6));
        panel.addView(titleBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        prevButton.setOnClickListener(v -> performDocumentSearchMove(
                input.getText() != null ? input.getText().toString() : "", false, matchStatus));
        nextButton.setOnClickListener(v -> performDocumentSearchMove(
                input.getText() != null ? input.getText().toString() : "", true, matchStatus));
        closeButton.setOnClickListener(v -> hideDocumentSearchPanel(true, true));

        input.setOnEditorActionListener((v, actionId, event) -> {
            performDocumentSearchMove(input.getText() != null ? input.getText().toString() : "", true, matchStatus);
            return true;
        });

        targetContainer.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        targetContainer.setVisibility(View.VISIBLE);
        targetContainer.requestLayout();
        if (shouldOverlayDocumentSearchPanel()) {
            activity.fixedLayoutFindOffsetActive = true;
            targetContainer.post(activity::applyFixedLayoutFindOffsetCssIfNeeded);
            targetContainer.postDelayed(activity::applyFixedLayoutFindOffsetCssIfNeeded, 180);
        } else if (activity.webView != null) {
            activity.webView.requestLayout();
        }
        focusDocumentSearchInput(input);
    }

    private void focusDocumentSearchInput(@NonNull EditText input) {
        input.postDelayed(() -> {
            if (activity.activityDestroyed || input != activity.documentSearchInputView) return;
            input.requestFocus();
            Object service = activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (service instanceof android.view.inputmethod.InputMethodManager) {
                ((android.view.inputmethod.InputMethodManager) service).showSoftInput(input,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }, 80);
    }

    void hideDocumentSearchPanel(boolean saveQuery, boolean clearWebView) {
        if (saveQuery && activity.prefs != null && activity.documentSearchInputView != null) {
            activity.prefs.setLastReaderSearchQuery(activity.documentSearchInputView.getText() != null
                    ? activity.documentSearchInputView.getText().toString()
                    : "");
        }
        if (activity.documentSearchInputView != null) {
            Object service = activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (service instanceof android.view.inputmethod.InputMethodManager) {
                ((android.view.inputmethod.InputMethodManager) service).hideSoftInputFromWindow(
                        activity.documentSearchInputView.getWindowToken(), 0);
            }
        }
        activity.documentSearchInputView = null;
        activity.documentSearchStatusView = null;
        boolean wasInlineVisible = activity.documentSearchPanelContainer != null
                && activity.documentSearchPanelContainer.getVisibility() == View.VISIBLE;
        boolean wasFixedLayoutOverlayVisible = shouldOverlayDocumentSearchPanel()
                && activity.documentSearchOverlayContainer != null
                && activity.documentSearchOverlayContainer.getVisibility() == View.VISIBLE;
        clearDocumentSearchPanelContainers();
        clearDocumentSearchState(clearWebView);
        if (wasFixedLayoutOverlayVisible) activity.setFixedLayoutFindOffsetActive(false);
        if (wasInlineVisible && activity.webView != null) activity.webView.requestLayout();
    }

    private boolean shouldOverlayDocumentSearchPanel() {
        // Fixed-layout EPUB pages are deliberately kept at their original page geometry.
        // Do not insert the search panel into the vertical document layout for those
        // pages; shrinking the WebView makes the centered fixed page appear to drop
        // far below its normal position when Find opens. Overlaying the panel keeps
        // the page box stable while still allowing native WebView find/highlight.
        return "EPUB".equals(activity.docType) && activity.epubFixedLayoutLike;
    }

    private FrameLayout getDocumentSearchPanelTargetContainer() {
        if (shouldOverlayDocumentSearchPanel() && activity.documentSearchOverlayContainer != null) {
            return activity.documentSearchOverlayContainer;
        }
        return activity.documentSearchPanelContainer;
    }

    boolean isDocumentSearchPanelVisible() {
        return (activity.documentSearchPanelContainer != null
                && activity.documentSearchPanelContainer.getVisibility() == View.VISIBLE)
                || (activity.documentSearchOverlayContainer != null
                && activity.documentSearchOverlayContainer.getVisibility() == View.VISIBLE);
    }

    private void clearDocumentSearchPanelContainers() {
        if (activity.documentSearchPanelContainer != null) {
            activity.documentSearchPanelContainer.removeAllViews();
            activity.documentSearchPanelContainer.setVisibility(View.GONE);
            activity.documentSearchPanelContainer.requestLayout();
        }
        if (activity.documentSearchOverlayContainer != null) {
            activity.documentSearchOverlayContainer.removeAllViews();
            activity.documentSearchOverlayContainer.setVisibility(View.GONE);
            activity.documentSearchOverlayContainer.requestLayout();
        }
    }

    private TextView makeDocumentSearchDialogButton(String label, int fg) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(fg);
        button.setTextSize(14f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dpToPx(4), 0, dpToPx(4), 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private void performDocumentSearchMove(String rawQuery, boolean forward, TextView matchStatus) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery("");
            clearDocumentSearchState(true);
            if (matchStatus != null) matchStatus.setText("0 / 0");
            ShortToast.show(activity, getString(R.string.enter_search_text));
            return;
        }

        activity.activeDocumentSearchTotal = countDocumentMatches(query);
        if (activity.activeDocumentSearchTotal <= 0) {
            activity.activeDocumentSearchQuery = query;
            activity.activeDocumentSearchPage = -1;
            activity.activeDocumentSearchOrdinal = 0;
            activity.activeDocumentSearchCountOnPage = 0;
            if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery(query);
            clearWebViewDocumentMatches();
            if (matchStatus != null) matchStatus.setText("0 / 0");
            ShortToast.show(activity, getString(R.string.not_found));
            return;
        }

        boolean queryChanged = !query.equalsIgnoreCase(activity.activeDocumentSearchQuery == null ? "" : activity.activeDocumentSearchQuery);
        activity.activeDocumentSearchQuery = query;
        if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery(query);

        if (queryChanged || activity.activeDocumentSearchPage != activity.currentPage || activity.activeDocumentSearchCountOnPage <= 0) {
            if (pageContainsDocumentQuery(activity.currentPage, query)) {
                applyDocumentSearchHighlight(query, forward);
                updateDocumentSearchStatus(matchStatus);
                return;
            }
        } else {
            boolean canMoveWithinPage = forward
                    ? activity.activeDocumentSearchOrdinal < activity.activeDocumentSearchCountOnPage
                    : activity.activeDocumentSearchOrdinal > 1;
            if (canMoveWithinPage && activity.webView != null) {
                // Roll back the forced same-page follow correction.  Let WebView's
                // native findNext()/FindListener own match selection and scrolling;
                // the previous manual ordinal + reveal pass made Word results drift
                // under the find popup on some documents.
                activity.webView.findNext(forward);
                return;
            }
        }

        int target = findNextDocumentSearchPage(query, activity.currentPage, forward);
        if (target >= 0) {
            activity.activeDocumentSearchPage = target;
            activity.showPage(target, Integer.compare(target, activity.currentPage));
            activity.documentSearchSelectLastAfterCount = !forward;
            updateDocumentSearchStatus(matchStatus);
        } else {
            ShortToast.show(activity, getString(R.string.not_found));
            updateDocumentSearchStatus(matchStatus);
        }
    }

    void applyDocumentSearchHighlightAfterPageLoad() {
        if (activity.webView == null || activity.activeDocumentSearchQuery == null || activity.activeDocumentSearchQuery.trim().isEmpty()) return;
        if (!pageContainsDocumentQuery(activity.currentPage, activity.activeDocumentSearchQuery)) {
            clearWebViewDocumentMatches();
            updateDocumentSearchStatus(activity.documentSearchStatusView);
            return;
        }
        activity.webView.postDelayed(() -> {
            if (!activity.activityDestroyed) applyDocumentSearchHighlight(activity.activeDocumentSearchQuery, !activity.documentSearchSelectLastAfterCount);
        }, 60);
    }

    void scheduleDocumentSearchReveal() {
        // Intentionally no-op. Word/EPUB document find now uses native WebView
        // selection/scroll behavior only, avoiding extra reveal/follow correction.
    }

    private void applyDocumentSearchHighlight(String query, boolean forward) {
        if (activity.webView == null || query == null || query.trim().isEmpty()) return;
        activity.activeDocumentSearchPage = activity.currentPage;
        activity.documentSearchSelectLastAfterCount = !forward;
        activity.webView.clearMatches();
        activity.webView.findAllAsync(query);
    }

    private int findNextDocumentSearchPage(String query, int fromPage, boolean forward) {
        if (activity.documentPageCount() == 0 || query == null || query.trim().isEmpty()) return -1;
        int count = activity.documentPageCount();
        for (int step = 1; step <= count; step++) {
            int idx = forward
                    ? (fromPage + step) % count
                    : (fromPage - step + count) % count;
            if (pageContainsDocumentQuery(idx, query)) return idx;
        }
        return -1;
    }

    private boolean pageContainsDocumentQuery(int pageIndex, String query) {
        if (pageIndex < 0 || pageIndex >= activity.documentPageCount() || query == null || query.trim().isEmpty()) return false;
        String text = activity.htmlToText(activity.documentPageHtml(pageIndex)).toLowerCase(Locale.ROOT);
        return text.contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private int countDocumentMatches(String query) {
        if (query == null || query.trim().isEmpty()) return 0;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        int total = 0;
        for (int i = 0; i < activity.documentPageCount(); i++) {
            total += countOccurrencesIgnoreCase(activity.htmlToText(activity.documentPageHtml(i)), needle);
        }
        return total;
    }

    private int countDocumentMatchesBeforePage(String query, int pageIndex) {
        if (query == null || query.trim().isEmpty()) return 0;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        int total = 0;
        for (int i = 0; i < Math.min(pageIndex, activity.documentPageCount()); i++) {
            total += countOccurrencesIgnoreCase(activity.htmlToText(activity.documentPageHtml(i)), needle);
        }
        return total;
    }

    private int countOccurrencesIgnoreCase(String text, String lowerNeedle) {
        if (text == null || lowerNeedle == null || lowerNeedle.isEmpty()) return 0;
        String haystack = text.toLowerCase(Locale.ROOT);
        int total = 0;
        int pos = 0;
        while ((pos = haystack.indexOf(lowerNeedle, pos)) >= 0) {
            total++;
            pos += Math.max(1, lowerNeedle.length());
        }
        return total;
    }

    void updateDocumentSearchStatus(TextView matchStatus) {
        if (matchStatus == null) return;
        if (activity.activeDocumentSearchQuery == null || activity.activeDocumentSearchQuery.trim().isEmpty()) {
            matchStatus.setText("0 / 0");
            return;
        }
        if (activity.activeDocumentSearchTotal <= 0) {
            activity.activeDocumentSearchTotal = countDocumentMatches(activity.activeDocumentSearchQuery);
        }
        int pageOrdinal = Math.max(0, activity.activeDocumentSearchOrdinal);
        int globalOrdinal = pageOrdinal > 0
                ? countDocumentMatchesBeforePage(activity.activeDocumentSearchQuery, activity.currentPage) + pageOrdinal
                : 0;
        matchStatus.setText(String.format(Locale.getDefault(), "%d / %d", globalOrdinal, Math.max(0, activity.activeDocumentSearchTotal)));
    }

    void clearDocumentSearchState(boolean clearWebView) {
        activity.activeDocumentSearchQuery = "";
        activity.activeDocumentSearchPage = -1;
        activity.activeDocumentSearchOrdinal = 0;
        activity.activeDocumentSearchCountOnPage = 0;
        activity.activeDocumentSearchTotal = 0;
        activity.documentSearchSelectLastAfterCount = false;
        if (clearWebView) clearWebViewDocumentMatches();
    }

    private void clearWebViewDocumentMatches() {
        if (activity.webView == null) return;
        try {
            activity.webView.clearMatches();
        } catch (Throwable ignored) {
            // WebView search cleanup should not crash the viewer.
        }
    }

    private LinearLayout makeDialogBox() {
        return activity.makeDialogBox();
    }

    private String getString(int resId) {
        return activity.getString(resId);
    }

    private int dpToPx(float dp) {
        return activity.dpToPx(dp);
    }

    private int dialogBg() {
        return activity.dialogBg();
    }

    private int dialogFg() {
        return activity.dialogFg();
    }

    private int dialogSub() {
        return activity.dialogSub();
    }
}
