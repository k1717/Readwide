package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.CompoundButtonCompat;

import com.readwide.manager.util.SearchMatcher;
import com.readwide.manager.util.SearchOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DocumentSearchController {
    private static final String CURRENT_SEARCH_ID = "rw-document-search-current";
    private final DocumentPageActivity activity;
    private java.util.concurrent.ThreadPoolExecutor countWorker;
    private java.util.concurrent.Future<?> countTask;
    private int countGeneration;
    private String countsQuery = "", countsOptions = "";
    private Object countsFirstPage;
    private int countsPageSize = -1;
    private DocumentSearchCounts countsIndex;
    private Runnable afterCount;
    private boolean afterCountIsNavigation;
    private SearchMatcher countedMatcher;

    private Object firstPageIdentity() { return activity.pages.isEmpty() ? null : activity.pages.get(0); }

    private boolean countsMatch(String query) {
        return query.equals(countsQuery) && currentSearchOptions().signature().equals(countsOptions)
                && countsFirstPage == firstPageIdentity() && countsPageSize == documentSearchPageCount();
    }

    /** UI-thread request; all HTML decoding and matching happens on the worker. */
    private boolean ensureCounts(String query, Runnable ready) {
        return ensureCounts(query, ready, false);
    }

    private boolean ensureCounts(String query, Runnable ready, boolean navigation) {
        if (activity.activityDestroyed) return false;
        if (countsMatch(query) && countsIndex != null) return true;
        if (!countsMatch(query) || navigation || !afterCountIsNavigation) {
            afterCount = ready; afterCountIsNavigation = navigation;
        }
        if (countsMatch(query) && countTask != null) return false; // includes queued UI completion
        if (countTask != null) countTask.cancel(true);
        final int generation = ++countGeneration;
        final int load = activity.loadGeneration;
        final SearchOptions options = currentSearchOptions();
        countsQuery = query; countsOptions = options.signature();
        countsFirstPage = firstPageIdentity(); countsPageSize = documentSearchPageCount();
        final Object firstPage = countsFirstPage;
        final String[] html = new String[countsPageSize];
        for (int i = 0; i < html.length; i++) html[i] = documentSearchPageHtml(i);
        countsIndex = null;
        if (countWorker == null) countWorker = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 10, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1),
                new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
        countWorker.allowCoreThreadTimeOut(true);
        countTask = countWorker.submit(() -> {
            try {
            SearchMatcher matcher = SearchMatcher.compile(query, options);
            int[] counts = new int[html.length];

            for (int i = 0; i < html.length; i++) {
                if (Thread.currentThread().isInterrupted()) return;
                counts[i] = matcher == null ? 0 : countHtmlTextSegmentMatches(html[i], matcher);

            }
            if (Thread.currentThread().isInterrupted()) return;
            final DocumentSearchCounts completed = new DocumentSearchCounts(counts);
            activity.runOnUiThread(() -> {
                if (activity.activityDestroyed || generation != countGeneration || load != activity.loadGeneration
                        || firstPage != firstPageIdentity() || !countsMatch(query)) return;
                countTask = null;
                countsIndex = completed; countedMatcher = matcher;
                Runnable callback = afterCount; afterCount = null;
                afterCountIsNavigation = false;
                if (callback != null) callback.run();
            });
            } catch (RuntimeException | StackOverflowError failure) {
                activity.runOnUiThread(() -> {
                    if (activity.activityDestroyed || generation != countGeneration) return;
                    countTask = null; afterCount = null; afterCountIsNavigation = false; countsPageSize = -1;
                    if (activity.documentSearchStatusView != null) activity.documentSearchStatusView.setText(
                            getString(R.string.error_prefix) + failure.getClass().getSimpleName());
                });
            }
        });
        return false;
    }

    void close() {
        invalidateCounts();
        if (countWorker != null) countWorker.shutdownNow();
        countWorker = null;
    }

    private void invalidateCounts() {
        ++countGeneration;
        if (countTask != null) countTask.cancel(true);
        countTask = null; afterCount = null;
        afterCountIsNavigation = false; countedMatcher = null;
        countsIndex = null; countsFirstPage = null; countsPageSize = -1;
    }

    DocumentSearchController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
    }

    void showDocumentSearchDialog() {
        if (activity.documentSearchDialog != null && activity.documentSearchDialog.isShowing()) {
            if (activity.documentSearchInputView != null) focusDocumentSearchInput(activity.documentSearchInputView);
            return;
        }

        final int bg = dialogBg();
        final int fg = dialogFg();
        final int sub = dialogSub();

        FrameLayout titleBox = new FrameLayout(activity);
        titleBox.setPadding(dpToPx(22), dpToPx(18), dpToPx(22), dpToPx(8));
        titleBox.setBackgroundColor(Color.TRANSPARENT);

        TextView title = new TextView(activity);
        title.setText(getString(R.string.find_in_text));
        title.setTextColor(fg);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        title.setIncludeFontPadding(false);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.START);
        titleLp.setMarginEnd(dpToPx(116));
        titleBox.addView(title, titleLp);

        TextView matchStatus = new TextView(activity);
        matchStatus.setText("0 / 0");
        matchStatus.setTextColor(sub);
        matchStatus.setTextSize(12f);
        matchStatus.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        matchStatus.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        matchStatus.setIncludeFontPadding(false);
        matchStatus.setMinWidth(dpToPx(100));
        titleBox.addView(matchStatus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.END));
        activity.documentSearchStatusView = matchStatus;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(8));

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
            final String initialQuery = rememberedQuery;
            Runnable showInitialCount = () -> {
                activity.activeDocumentSearchTotal = countDocumentMatches(initialQuery);
                matchStatus.setText(String.format(Locale.getDefault(), "0 / %d", activity.activeDocumentSearchTotal));
            };
            if (ensureCounts(initialQuery, showInitialCount)) showInitialCount.run();
        }
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(52)));

        EditText occurrenceInput = activity.makeDialogInput(getString(R.string.search_occurrence_hint));
        occurrenceInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams occurrenceLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48));
        occurrenceLp.setMargins(0, dpToPx(8), 0, 0);
        box.addView(occurrenceInput, occurrenceLp);

        TextView hint = new TextView(activity);
        hint.setText(getString(R.string.search_hint_multiple));
        hint.setTextColor(sub);
        hint.setTextSize(12f);
        hint.setGravity(Gravity.START);
        hint.setPadding(0, dpToPx(6), 0, dpToPx(8));
        box.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final CheckBox caseBox = makeSearchOptionCheck(getString(R.string.search_option_case_sensitive), fg,
                activity.prefs != null && activity.prefs.getReaderSearchCaseSensitive());
        final CheckBox wholeBox = makeSearchOptionCheck(getString(R.string.search_option_whole_word), fg,
                activity.prefs != null && activity.prefs.getReaderSearchWholeWord());
        final CheckBox regexBox = makeSearchOptionCheck(getString(R.string.search_option_regex), fg,
                activity.prefs != null && activity.prefs.getReaderSearchRegex());

        Runnable recount = () -> {
            String q = input.getText() != null ? input.getText().toString().trim() : "";
            if (q.isEmpty()) {
                matchStatus.setText("0 / 0");
                return;
            }
            Runnable showCount = () -> {
                int total = countDocumentMatches(q);
                activity.activeDocumentSearchTotal = total;
                int ordinal = q.equals(activity.activeDocumentSearchQuery) ? activeGlobalOrdinal() : 0;
                matchStatus.setText(String.format(Locale.getDefault(), "%d / %d", Math.max(0, ordinal), Math.max(0, total)));
            };
            if (ensureCounts(q, showCount)) showCount.run();
            else matchStatus.setText("…");
        };

        final Runnable debouncedRecount = () -> {
            if (input == activity.documentSearchInputView && activity.documentSearchDialog != null
                    && activity.documentSearchDialog.isShowing()) recount.run();
        };
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable value) {
                input.removeCallbacks(debouncedRecount);
                invalidateCounts();
                matchStatus.setText(value.toString().trim().isEmpty() ? "0 / 0" : "…");
                input.postDelayed(debouncedRecount, 180L);
            }
        });

        caseBox.setOnCheckedChangeListener((v, checked) -> {
            if (activity.prefs != null) activity.prefs.setReaderSearchCaseSensitive(checked);
            clearDocumentSearchState(true);
            recount.run();
        });
        wholeBox.setOnCheckedChangeListener((v, checked) -> {
            if (activity.prefs != null) activity.prefs.setReaderSearchWholeWord(checked);
            clearDocumentSearchState(true);
            recount.run();
        });
        regexBox.setOnCheckedChangeListener((v, checked) -> {
            if (activity.prefs != null) activity.prefs.setReaderSearchRegex(checked);
            clearDocumentSearchState(true);
            recount.run();
        });

        LinearLayout optionsRow = new LinearLayout(activity);
        optionsRow.setOrientation(LinearLayout.VERTICAL);
        optionsRow.setPadding(0, 0, 0, dpToPx(4));
        optionsRow.addView(caseBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        optionsRow.addView(wholeBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        optionsRow.addView(regexBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(optionsRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dpToPx(8), 0, 0);

        TextView prevButton = makeSearchDialogButton(getString(R.string.find_previous), fg);
        TextView nthButton = makeSearchDialogButton(getString(R.string.find_nth), fg);
        TextView closeButton = makeSearchDialogButton(getString(R.string.close), fg);
        TextView nextButton = makeSearchDialogButton(getString(R.string.find_next), fg);

        buttons.addView(prevButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(nthButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(closeButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(nextButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(bg);
        panelBg.setCornerRadius(dpToPx(14));
        panelBg.setStroke(Math.max(1, dpToPx(1)), activity.readerLine);
        panel.setBackground(panelBg);
        panel.setClipToOutline(true);
        panel.addView(titleBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Dialog dialog = activity.createStablePositionedDialog(
                panel,
                DocumentPageActivity.DOCUMENT_TOOLBAR_POPUP_Y_DP,
                true,
                false);
        activity.documentSearchDialog = dialog;

        prevButton.setOnClickListener(v -> performDocumentSearchMove(
                input.getText() != null ? input.getText().toString() : "", false, matchStatus));
        nthButton.setOnClickListener(v -> {
            int occurrence = parseSearchOccurrenceTarget(occurrenceInput);
            if (occurrence > 0) {
                performDocumentSearchMove(input.getText() != null ? input.getText().toString() : "", true, matchStatus, occurrence);
            }
        });
        nextButton.setOnClickListener(v -> performDocumentSearchMove(
                input.getText() != null ? input.getText().toString() : "", true, matchStatus));
        closeButton.setOnClickListener(v -> dialog.dismiss());

        input.setOnEditorActionListener((v, actionId, event) -> {
            performDocumentSearchMove(input.getText() != null ? input.getText().toString() : "", true, matchStatus);
            return true;
        });
        occurrenceInput.setOnEditorActionListener((v, actionId, event) -> {
            int occurrence = parseSearchOccurrenceTarget(occurrenceInput);
            if (occurrence > 0) {
                performDocumentSearchMove(input.getText() != null ? input.getText().toString() : "", true, matchStatus, occurrence);
            }
            return true;
        });

        dialog.setOnDismissListener(d -> {
            input.removeCallbacks(debouncedRecount);
            if (activity.prefs != null) {
                activity.prefs.setLastReaderSearchQuery(input.getText() != null ? input.getText().toString() : "");
            }
            activity.documentSearchDialog = null;
            activity.documentSearchInputView = null;
            activity.documentSearchStatusView = null;
            clearDocumentSearchState(true);
        });
        dialog.show();
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
        clearDocumentSearchPanelContainers();
        if (activity.documentSearchDialog != null) {
            Dialog dialog = activity.documentSearchDialog;
            activity.documentSearchDialog = null;
            // Programmatic callers choose whether the current WebView must be
            // reloaded. The normal OnDismiss listener always clears with a
            // reload, which used to ignore clearWebView=false and could race a
            // bookmark navigation that immediately loads/restores the target.
            dialog.setOnDismissListener(null);
            if (dialog.isShowing()) dialog.dismiss();
        }
        activity.documentSearchInputView = null;
        activity.documentSearchStatusView = null;
        clearDocumentSearchState(clearWebView);
        activity.setFixedLayoutFindOffsetActive(false);
    }

    boolean isDocumentSearchPanelVisible() {
        return (activity.documentSearchDialog != null && activity.documentSearchDialog.isShowing())
                || (activity.documentSearchPanelContainer != null
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

    private void performDocumentSearchMove(String rawQuery, boolean forward, TextView matchStatus) {
        performDocumentSearchMove(rawQuery, forward, matchStatus, -1);
    }

    private void performDocumentSearchMove(String rawQuery, boolean forward, TextView matchStatus, int targetOccurrence) {
        if (targetOccurrence == 0) return;
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery("");
            clearDocumentSearchState(true);
            if (matchStatus != null) matchStatus.setText("0 / 0");
            ShortToast.show(activity, getString(R.string.enter_search_text));
            return;
        }

        if (!ensureCounts(query, () -> performDocumentSearchMove(query, forward, matchStatus, targetOccurrence), true)) {
            if (matchStatus != null) matchStatus.setText("…");
            return;
        }
        SearchMatcher matcher = countedMatcher;
        if (matcher == null) {
            activity.activeDocumentSearchQuery = query;
            activity.activeDocumentSearchPage = -1;
            activity.activeDocumentSearchOrdinal = 0;
            activity.activeDocumentSearchCountOnPage = 0;
            activity.activeDocumentSearchTotal = 0;
            if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery(query);
            reloadCurrentPageToRefreshSearchMarkup();
            if (matchStatus != null) matchStatus.setText("0 / 0");
            ShortToast.show(activity, getString(R.string.not_found));
            return;
        }

        int total = countDocumentMatches(query, matcher);
        activity.activeDocumentSearchTotal = total;
        if (total <= 0) {
            activity.activeDocumentSearchQuery = query;
            activity.activeDocumentSearchPage = -1;
            activity.activeDocumentSearchOrdinal = 0;
            activity.activeDocumentSearchCountOnPage = 0;
            if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery(query);
            reloadCurrentPageToRefreshSearchMarkup();
            if (matchStatus != null) matchStatus.setText("0 / 0");
            ShortToast.show(activity, getString(R.string.not_found));
            return;
        }

        boolean queryChanged = !query.equals(activity.activeDocumentSearchQuery == null ? "" : activity.activeDocumentSearchQuery);
        activity.activeDocumentSearchQuery = query;
        if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery(query);

        DocumentSearchTarget target;
        if (targetOccurrence > 0) {
            if (targetOccurrence > total) {
                if (matchStatus != null) matchStatus.setText(String.format(Locale.getDefault(), "0 / %d", total));
                ShortToast.show(activity, activity.getString(R.string.search_occurrence_out_of_range, total));
                return;
            }
            target = findDocumentSearchTargetByGlobalOrdinal(query, matcher, targetOccurrence);
        } else {
            target = findNextDocumentSearchTarget(query, matcher, queryChanged, forward);
        }

        if (target == null) {
            if (matchStatus != null) matchStatus.setText(String.format(Locale.getDefault(), "0 / %d", total));
            ShortToast.show(activity, getString(R.string.not_found));
            return;
        }

        activity.activeDocumentSearchPage = target.pageIndex;
        activity.activeDocumentSearchOrdinal = target.ordinalOnPage;
        activity.activeDocumentSearchCountOnPage = target.countOnPage;
        activity.activeDocumentSearchTotal = total;
        activity.documentSearchSelectLastAfterCount = false;

        int direction = Integer.compare(target.pageIndex, activity.currentPage);
        updateDocumentSearchStatus(matchStatus);
        if (direction == 0 && !queryChanged) {
            applySamePageDocumentSearchSelectionOrReload(matchStatus);
        } else {
            // Search chooses an explicit result and owns its final scroll position.
            // The gesture page-turn lock must not discard it after the counter
            // and selected match have already moved to the requested chapter.
            activity.showPage(target.pageIndex, 0);
        }
    }

    private void applySamePageDocumentSearchSelectionOrReload(TextView matchStatus) {
        updateDocumentSearchStatus(matchStatus);
        if (activity.activityDestroyed || activity.webView == null) {
            return;
        }
        final WebView targetView = activity.webView;
        final int targetPage = activity.currentPage;
        final int targetGeneration = activity.documentAnchorPageGeneration;
        final String targetQuery = activity.activeDocumentSearchQuery;
        WebSettings settings = targetView.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        final int selectedOrdinal = Math.max(1, activity.activeDocumentSearchOrdinal);
        targetView.evaluateJavascript(
                "(function(){try{"
                        + "var hits=document.querySelectorAll('span.rw-document-search-hit');"
                        + "if(!hits||!hits.length)return false;"
                        + "var selected=" + selectedOrdinal + ";var found=false;"
                        + "for(var i=0;i<hits.length;i++){"
                        + "var h=hits[i];var ord=parseInt(h.getAttribute('data-rw-doc-search-ordinal')||'0',10);"
                        + "var on=(ord===selected);"
                        + "if(on){h.id='" + CURRENT_SEARCH_ID + "';h.classList.add('rw-document-search-current');"
                        + "h.style.setProperty('background-color','#ff9800','important');h.style.setProperty('color','#111','important');found=true;}"
                        + "else{if(h.id==='" + CURRENT_SEARCH_ID + "')h.removeAttribute('id');"
                        + "h.classList.remove('rw-document-search-current');h.style.setProperty('background-color','#ffeb3b','important');h.style.setProperty('color','#111','important');}"
                        + "}"
                        + "return found;}catch(ex){return false;}})()",
                value -> {
                    if (activity.activityDestroyed || activity.webView == null) return;
                    activity.restoreDocumentJavaScriptPolicy(
                            targetView, targetPage, restoreJavascriptOff);
                    if (activity.webView != targetView || activity.currentPage != targetPage
                            || activity.documentAnchorPageGeneration != targetGeneration
                            || !targetQuery.equals(activity.activeDocumentSearchQuery)
                            || selectedOrdinal != activity.activeDocumentSearchOrdinal) return;
                    if ("true".equals(value)) {
                        scrollDocumentSearchCurrentIntoView();
                    } else {
                        reloadCurrentPageToRefreshSearchMarkup();
                    }
                });
    }

    private DocumentSearchTarget findNextDocumentSearchTarget(String query, SearchMatcher matcher, boolean queryChanged, boolean forward) {
        int count = documentSearchPageCount();
        if (count <= 0) return null;
        int current = Math.max(0, Math.min(count - 1, activity.currentPage));
        int currentPageCount = countDocumentMatchesOnPage(query, matcher, current);

        if (!queryChanged && activity.activeDocumentSearchPage == current && activity.activeDocumentSearchOrdinal > 0) {
            if (forward && activity.activeDocumentSearchOrdinal < currentPageCount) {
                return new DocumentSearchTarget(current, activity.activeDocumentSearchOrdinal + 1, currentPageCount);
            }
            if (!forward && activity.activeDocumentSearchOrdinal > 1) {
                return new DocumentSearchTarget(current, activity.activeDocumentSearchOrdinal - 1, currentPageCount);
            }
        } else if (currentPageCount > 0) {
            return new DocumentSearchTarget(current, forward ? 1 : currentPageCount, currentPageCount);
        }

        if (!countsMatch(query) || countsIndex == null || countsIndex.total() == 0) return null;
        long total = countsIndex.before(count);
        long occurrence = forward ? countsIndex.before(current + 1) + 1 : countsIndex.before(current);
        if (occurrence > total) occurrence = 1;
        if (occurrence <= 0) occurrence = total;
        int page = countsIndex.pageForOccurrence(occurrence);
        return page < 0 ? null : new DocumentSearchTarget(page,
                (int) (occurrence - countsIndex.before(page)), countsIndex.onPage(page));
    }

    private DocumentSearchTarget findDocumentSearchTargetByGlobalOrdinal(String query, SearchMatcher matcher, int occurrence) {
        if (!countsMatch(query) || countsIndex == null) return null;
        int page = countsIndex.pageForOccurrence(occurrence);
        return page < 0 ? null : new DocumentSearchTarget(page,
                (int) (occurrence - countsIndex.before(page)), countsIndex.onPage(page));
    }

    void applyDocumentSearchHighlightAfterPageLoad() {
        if (activity.activeDocumentSearchQuery == null || activity.activeDocumentSearchQuery.trim().isEmpty()) return;
        if (activity.currentPage != activity.activeDocumentSearchPage || activity.activeDocumentSearchOrdinal <= 0) {
            updateDocumentSearchStatus(activity.documentSearchStatusView);
            return;
        }
        activity.webView.postDelayed(this::scrollDocumentSearchCurrentIntoView, 80);
        activity.webView.postDelayed(this::scrollDocumentSearchCurrentIntoView, 180);
        activity.webView.postDelayed(this::scrollDocumentSearchCurrentIntoView, 320);
        updateDocumentSearchStatus(activity.documentSearchStatusView);
    }

    void scheduleDocumentSearchReveal() {
        scrollDocumentSearchCurrentIntoView();
    }

    String applyDocumentSearchMarkupForDisplay(String html, int pageIndex) {
        if (html == null || activity.activeDocumentSearchQuery == null || activity.activeDocumentSearchQuery.trim().isEmpty()) {
            return html;
        }
        if (pageIndex != activity.activeDocumentSearchPage || activity.activeDocumentSearchOrdinal <= 0) {
            return html;
        }
        SearchMatcher matcher = SearchMatcher.compile(activity.activeDocumentSearchQuery, currentSearchOptions());
        if (matcher == null) return html;
        return injectDocumentSearchSpacers(
                highlightHtmlTextSegments(html, matcher, activity.activeDocumentSearchOrdinal));
    }

    private String injectDocumentSearchSpacers(String html) {
        if (html == null || html.isEmpty()) return html;
        String out = html;
        String style = "<style id=\"rw-document-search-spacer-style\">"
                + "#rw-document-search-top-spacer{display:block!important;height:12vh!important;min-height:72px!important;pointer-events:none!important;}"
                + "#rw-document-search-bottom-spacer{display:block!important;height:88vh!important;min-height:420px!important;pointer-events:none!important;}"
                + "</style>";
        if (!out.contains("rw-document-search-spacer-style")) {
            int headClose = indexOfIgnoreCase(out, "</head", 0);
            if (headClose >= 0) out = out.substring(0, headClose) + style + out.substring(headClose);
            else out = style + out;
        }
        if (!out.contains("rw-document-search-top-spacer")) {
            String top = "<div id=\"rw-document-search-top-spacer\" aria-hidden=\"true\"></div>";
            int bodyOpen = indexOfIgnoreCase(out, "<body", 0);
            int bodyOpenEnd = bodyOpen >= 0 ? out.indexOf('>', bodyOpen) : -1;
            if (bodyOpenEnd >= 0) out = out.substring(0, bodyOpenEnd + 1) + top + out.substring(bodyOpenEnd + 1);
            else out = top + out;
        }
        if (!out.contains("rw-document-search-bottom-spacer")) {
            String bottom = "<div id=\"rw-document-search-bottom-spacer\" aria-hidden=\"true\"></div>";
            int bodyClose = indexOfIgnoreCase(out, "</body", 0);
            if (bodyClose >= 0) out = out.substring(0, bodyClose) + bottom + out.substring(bodyClose);
            else out = out + bottom;
        }
        return out;
    }

    static String highlightHtmlTextSegments(String html, SearchMatcher matcher, int selectedOrdinal) {
        if (html == null || html.isEmpty() || matcher == null) return html;
        StringBuilder out = new StringBuilder(html.length() + 128);
        int[] ordinal = new int[]{0};
        int i = 0;
        while (i < html.length()) {
            char ch = html.charAt(i);
            if (ch == '<') {
                if (html.startsWith("<!--", i)) {
                    int close = html.indexOf("-->", i + 4);
                    int commentEnd = close < 0 ? html.length() : close + 3;
                    out.append(html, i, commentEnd);
                    i = commentEnd;
                    continue;
                }
                int tagEnd = findHtmlTagEnd(html, i);
                if (tagEnd < 0) {
                    out.append(html.substring(i));
                    break;
                }
                String tag = html.substring(i, Math.min(tagEnd + 1, html.length())).toLowerCase(Locale.ROOT);
                if (startsRawTextElement(tag, "head")) {
                    int close = indexOfIgnoreCase(html, "</head", tagEnd + 1);
                    if (close >= 0) {
                        int closeEnd = html.indexOf('>', close + 1);
                        if (closeEnd >= 0) {
                            out.append(html, i, closeEnd + 1);
                            i = closeEnd + 1;
                            continue;
                        }
                    }
                } else if (startsRawTextElement(tag, "script")) {
                    int close = indexOfIgnoreCase(html, "</script", tagEnd + 1);
                    if (close >= 0) {
                        int closeEnd = html.indexOf('>', close + 1);
                        if (closeEnd >= 0) {
                            out.append(html, i, closeEnd + 1);
                            i = closeEnd + 1;
                            continue;
                        }
                    }
                } else if (startsRawTextElement(tag, "style")) {
                    int close = indexOfIgnoreCase(html, "</style", tagEnd + 1);
                    if (close >= 0) {
                        int closeEnd = html.indexOf('>', close + 1);
                        if (closeEnd >= 0) {
                            out.append(html, i, closeEnd + 1);
                            i = closeEnd + 1;
                            continue;
                        }
                    }
                }
                out.append(html, i, tagEnd + 1);
                i = tagEnd + 1;
            } else {
                int nextTag = html.indexOf('<', i);
                if (nextTag < 0) nextTag = html.length();
                appendHighlightedTextSegment(html, i, nextTag, matcher, selectedOrdinal, ordinal, out);
                i = nextTag;
            }
        }
        return out.toString();
    }

    private static void appendHighlightedTextSegment(String html, int start, int end, SearchMatcher matcher,
                                              int selectedOrdinal, int[] ordinal, StringBuilder out) {
        TextSegment segment = decodeHtmlTextSegment(html, start, end);
        // CharSequence.isEmpty() is a platform API only from API 35. length()
        // keeps this search path safe on the app's API-24 minimum.
        if (segment.text.length() == 0) {
            out.append(html, start, end);
            return;
        }
        int[] rawCursor = {start};
        forEachRenderableMatch(segment, start, end, matcher, (rawStart, rawEnd) -> {
            ordinal[0] += 1;
            out.append(html, rawCursor[0], rawStart);
            boolean selected = ordinal[0] == selectedOrdinal;
            out.append("<span class=\"rw-document-search-hit");
            if (selected) out.append(" rw-document-search-current");
            out.append("\" data-rw-doc-search-ordinal=\"").append(ordinal[0]).append("\"");
            if (selected) out.append(" id=\"").append(CURRENT_SEARCH_ID).append("\"");
            out.append(selected
                    ? " style=\"background-color:#ff9800!important;color:#111!important;border-radius:2px;padding:0 1px;\">"
                    : " style=\"background-color:#ffeb3b!important;color:#111!important;border-radius:2px;padding:0 1px;\">");
            out.append(html, rawStart, rawEnd);
            out.append("</span>");
            rawCursor[0] = rawEnd;
            return true;
        });
        out.append(html, rawCursor[0], end);
    }

    // HTML spans cannot overlap. Count and markup must accept the exact same
    // raw ranges, including entities that map more than one UTF-16 unit.
    private static void forEachRenderableMatch(TextSegment segment, int start, int end,
                                               SearchMatcher matcher, SearchMatcher.MatchConsumer consumer) {
        int[] cursor = {start};
        matcher.forEachMatch(segment.text.toString(), (s, e) -> {
            if (s < 0 || e <= s || e > segment.text.length()) return true;
            int rawStart = segment.rawStartForChar(s);
            int rawEnd = segment.rawEndForMatchEnd(e, end);
            if (rawStart < cursor[0] || rawEnd <= rawStart || rawEnd > end) return true;
            cursor[0] = rawEnd;
            return consumer.accept(rawStart, rawEnd);
        });
    }

    private static TextSegment decodeHtmlTextSegment(String html, int start, int end) {
        TextSegment out = new TextSegment();
        int i = start;
        while (i < end) {
            char ch = html.charAt(i);
            if (ch == '&') {
                // Longer entities already remain literal. Keep the scan within
                // that accepted length and this text segment, so stray '&'
                // characters cannot repeatedly scan the rest of the chapter.
                int semi = -1;
                for (int candidate = i + 1; candidate < end && candidate - i <= 12; candidate++) {
                    if (html.charAt(candidate) == ';') {
                        semi = candidate;
                        break;
                    }
                }
                if (semi > i && semi < end && semi - i <= 12) {
                    String decoded = decodeHtmlEntity(html.substring(i + 1, semi));
                    if (decoded != null && !decoded.isEmpty()) {
                        for (int c = 0; c < decoded.length(); c++) {
                            out.append(decoded.charAt(c), i, semi + 1);
                        }
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(ch, i, i + 1);
            i++;
        }
        return out;
    }

    private static String decodeHtmlEntity(String entity) {
        if (entity == null || entity.isEmpty()) return null;
        switch (entity) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            default:
                try {
                    if (entity.startsWith("#x") || entity.startsWith("#X")) {
                        int cp = Integer.parseInt(entity.substring(2), 16);
                        return new String(Character.toChars(cp));
                    }
                    if (entity.startsWith("#")) {
                        int cp = Integer.parseInt(entity.substring(1));
                        return new String(Character.toChars(cp));
                    }
                } catch (Exception ignored) {}
                return null;
        }
    }

    private int countDocumentMatches(String query) {
        return countsMatch(query) && countsIndex != null ? countsIndex.total() : 0;
    }

    private int countDocumentMatches(String query, SearchMatcher matcher) { return countDocumentMatches(query); }

    private int countDocumentMatchesOnPage(String query, SearchMatcher matcher, int pageIndex) {
        return countsMatch(query) && countsIndex != null ? countsIndex.onPage(pageIndex) : 0;
    }

    static int countHtmlTextSegmentMatches(String html, SearchMatcher matcher) {
        if (html == null || html.isEmpty() || matcher == null) return 0;
        int total = 0;
        int i = 0;
        while (i < html.length()) {
            if (Thread.currentThread().isInterrupted()) return 0;
            char ch = html.charAt(i);
            if (ch == '<') {
                if (html.startsWith("<!--", i)) {
                    int close = html.indexOf("-->", i + 4);
                    i = close < 0 ? html.length() : close + 3;
                    continue;
                }
                int tagEnd = findHtmlTagEnd(html, i);
                if (tagEnd < 0) break;
                String tag = html.substring(i, Math.min(tagEnd + 1, html.length())).toLowerCase(Locale.ROOT);
                if (startsRawTextElement(tag, "head")) {
                    int close = indexOfIgnoreCase(html, "</head", tagEnd + 1);
                    if (close >= 0) {
                        int closeEnd = html.indexOf('>', close + 1);
                        if (closeEnd >= 0) { i = closeEnd + 1; continue; }
                    }
                } else if (startsRawTextElement(tag, "script")) {
                    int close = indexOfIgnoreCase(html, "</script", tagEnd + 1);
                    if (close >= 0) {
                        int closeEnd = html.indexOf('>', close + 1);
                        if (closeEnd >= 0) { i = closeEnd + 1; continue; }
                    }
                } else if (startsRawTextElement(tag, "style")) {
                    int close = indexOfIgnoreCase(html, "</style", tagEnd + 1);
                    if (close >= 0) {
                        int closeEnd = html.indexOf('>', close + 1);
                        if (closeEnd >= 0) { i = closeEnd + 1; continue; }
                    }
                }
                i = tagEnd + 1;
            } else {
                int nextTag = html.indexOf('<', i);
                if (nextTag < 0) nextTag = html.length();
                TextSegment segment = decodeHtmlTextSegment(html, i, nextTag);
                int[] count = {0};
                forEachRenderableMatch(segment, i, nextTag, matcher, (s, e) -> {
                    if (count[0] < Integer.MAX_VALUE) count[0]++;
                    return true;
                });
                total = (int) Math.min(Integer.MAX_VALUE, (long) total + count[0]);
                i = nextTag;
            }
        }
        return total;
    }

    private int countDocumentMatchesBeforePage(String query, int pageIndex) {
        return countsMatch(query) && countsIndex != null
                ? (int) Math.min(Integer.MAX_VALUE, countsIndex.before(pageIndex)) : 0;
    }

    void updateDocumentSearchStatus(TextView matchStatus) {
        if (matchStatus == null) return;
        if (activity.activeDocumentSearchQuery == null || activity.activeDocumentSearchQuery.trim().isEmpty()) {
            matchStatus.setText("0 / 0");
            return;
        }
        if (activity.activeDocumentSearchTotal <= 0) {
            if (!ensureCounts(activity.activeDocumentSearchQuery, () -> updateDocumentSearchStatus(matchStatus))) {
                matchStatus.setText("…"); return;
            }
            activity.activeDocumentSearchTotal = countDocumentMatches(activity.activeDocumentSearchQuery);
        }
        int globalOrdinal = activeGlobalOrdinal();
        matchStatus.setText(String.format(Locale.getDefault(), "%d / %d", globalOrdinal, Math.max(0, activity.activeDocumentSearchTotal)));
    }

    private int activeGlobalOrdinal() {
        int pageOrdinal = Math.max(0, activity.activeDocumentSearchOrdinal);
        if (pageOrdinal <= 0 || activity.activeDocumentSearchPage < 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, (long) countDocumentMatchesBeforePage(
                activity.activeDocumentSearchQuery, activity.activeDocumentSearchPage) + pageOrdinal);
    }

    void clearDocumentSearchState(boolean clearWebView) {
        invalidateCounts();
        String previousQuery = activity.activeDocumentSearchQuery;
        int previousPage = activity.activeDocumentSearchPage;
        activity.activeDocumentSearchQuery = "";
        activity.activeDocumentSearchPage = -1;
        activity.activeDocumentSearchOrdinal = 0;
        activity.activeDocumentSearchCountOnPage = 0;
        activity.activeDocumentSearchTotal = 0;
        activity.documentSearchSelectLastAfterCount = false;
        if (clearWebView && previousQuery != null && !previousQuery.isEmpty() && previousPage == activity.currentPage) {
            reloadCurrentPageToRefreshSearchMarkup();
        }
    }

    private void reloadCurrentPageToRefreshSearchMarkup() {
        if (activity.activityDestroyed || activity.webView == null || documentSearchPageCount() <= 0) return;
        activity.reloadCurrentDocumentPreservingPosition();
    }

    private void scrollDocumentSearchCurrentIntoView() {
        if (activity.activityDestroyed || activity.webView == null) return;
        if (activity.activeDocumentSearchQuery == null || activity.activeDocumentSearchQuery.trim().isEmpty()) return;
        if (activity.currentPage != activity.activeDocumentSearchPage || activity.activeDocumentSearchOrdinal <= 0) return;

        final int safeBottomPx = currentSearchPanelTopInWebViewPx() - dpToPx(10);
        final WebView targetView = activity.webView;
        final int targetPage = activity.currentPage;
        WebSettings settings = targetView.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        targetView.evaluateJavascript(
                DocumentSearchJavascript.revealCurrentMatch(safeBottomPx, targetView.getHeight()),
                value -> {
                    activity.restoreDocumentJavaScriptPolicy(
                            targetView, targetPage, restoreJavascriptOff);
                });
    }

    private int currentSearchPanelTopInWebViewPx() {
        if (activity.webView == null) return -1;
        int webHeight = activity.webView.getHeight();
        if (webHeight <= 0) return -1;
        try {
            Dialog dialog = activity.documentSearchDialog;
            if (dialog != null && dialog.isShowing() && dialog.getWindow() != null) {
                View decor = dialog.getWindow().getDecorView();
                if (decor != null) {
                    int[] dialogLoc = new int[2];
                    int[] webLoc = new int[2];
                    decor.getLocationOnScreen(dialogLoc);
                    activity.webView.getLocationOnScreen(webLoc);
                    int top = dialogLoc[1] - webLoc[1];
                    if (top > 0 && top < webHeight) return top;
                }
            }
        } catch (Throwable ignored) {
            // Keep the 24% target untouched if the dialog top cannot be measured.
        }
        return -1;
    }

    boolean isDocumentSearchActiveOnCurrentPage() {
        return activity.activeDocumentSearchQuery != null
                && !activity.activeDocumentSearchQuery.trim().isEmpty()
                && activity.currentPage == activity.activeDocumentSearchPage
                && activity.activeDocumentSearchOrdinal > 0;
    }

    private static int findHtmlTagEnd(String html, int start) {
        char quote = 0;
        for (int i = start + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '>') return i;
        }
        return -1;
    }

    private static boolean startsRawTextElement(String lowerTag, String element) {
        return lowerTag != null
                && lowerTag.startsWith("<" + element)
                && (lowerTag.length() <= element.length() + 1
                || Character.isWhitespace(lowerTag.charAt(element.length() + 1))
                || lowerTag.charAt(element.length() + 1) == '>');
    }

    private static int indexOfIgnoreCase(String src, String needle, int from) {
        if (src == null || needle == null) return -1;
        // Lowercasing the entire input can change its UTF-16 length (e.g. İ),
        // invalidating raw HTML offsets. Compare without allocating a copy.
        for (int i = Math.max(0, from); i <= src.length() - needle.length(); i++) {
            if (src.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private int parseSearchOccurrenceTarget(EditText occurrenceInput) {
        if (occurrenceInput == null || occurrenceInput.getText() == null) return -1;
        String raw = occurrenceInput.getText().toString().trim();
        if (raw.isEmpty()) return -1;
        try {
            int occurrence = Integer.parseInt(raw);
            return occurrence > 0 ? occurrence : -1;
        } catch (NumberFormatException ignored) {
            ShortToast.show(activity, activity.getString(R.string.search_occurrence_invalid));
            return -1;
        }
    }

    private CheckBox makeSearchOptionCheck(String label, int fg, boolean checked) {
        CheckBox cb = new CheckBox(activity);
        cb.setText(label);
        cb.setTextColor(fg);
        cb.setTextSize(13f);
        cb.setChecked(checked);
        cb.setIncludeFontPadding(false);
        cb.setMinHeight(dpToPx(40));
        cb.setPadding(cb.getPaddingLeft(), dpToPx(4), cb.getPaddingRight(), dpToPx(4));
        CompoundButtonCompat.setButtonTintList(cb, ColorStateList.valueOf(fg));
        return cb;
    }

    private TextView makeSearchDialogButton(String label, int fg) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(fg);
        button.setTextSize(13f);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setIncludeFontPadding(false);
        button.setMinHeight(dpToPx(44));
        button.setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8));
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private SearchOptions currentSearchOptions() {
        boolean cs = activity.prefs != null && activity.prefs.getReaderSearchCaseSensitive();
        boolean ww = activity.prefs != null && activity.prefs.getReaderSearchWholeWord();
        boolean rx = activity.prefs != null && activity.prefs.getReaderSearchRegex();
        return new SearchOptions(cs, ww, rx, true);
    }

    private int documentSearchPageCount() {
        return Math.max(0, activity.pages != null ? activity.pages.size() : 0);
    }

    private String documentSearchPageHtml(int pageIndex) {
        if (activity.pages == null || pageIndex < 0 || pageIndex >= activity.pages.size()) return "";
        DocumentPageActivity.Page page = activity.pages.get(pageIndex);
        return page != null ? page.html : "";
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

    private static final class DocumentSearchTarget {
        final int pageIndex;
        final int ordinalOnPage;
        final int countOnPage;
        DocumentSearchTarget(int pageIndex, int ordinalOnPage, int countOnPage) {
            this.pageIndex = pageIndex;
            this.ordinalOnPage = ordinalOnPage;
            this.countOnPage = countOnPage;
        }
    }

    private static final class TextSegment {
        final StringBuilder text = new StringBuilder();
        // Primitive offsets avoid two boxed Integer objects per decoded character.
        private int[] rawStartByChar = new int[0];
        private int[] rawEndByChar = new int[0];

        void append(char value, int rawStart, int rawEnd) {
            int index = text.length();
            if (index == rawStartByChar.length) {
                int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(16L, index * 2L));
                rawStartByChar = java.util.Arrays.copyOf(rawStartByChar, capacity);
                rawEndByChar = java.util.Arrays.copyOf(rawEndByChar, capacity);
            }
            text.append(value);
            rawStartByChar[index] = rawStart;
            rawEndByChar[index] = rawEnd;
        }

        int rawStartForChar(int index) {
            if (text.length() == 0) return 0;
            int safe = Math.max(0, Math.min(text.length() - 1, index));
            return rawStartByChar[safe];
        }

        int rawEndForMatchEnd(int matchEnd, int segmentEnd) {
            if (text.length() == 0) return segmentEnd;
            int safe = Math.max(0, Math.min(text.length() - 1, matchEnd - 1));
            return rawEndByChar[safe];
        }
    }
}
