package com.readwide.manager;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.util.TextDisplayRuleManager;
import com.readwide.manager.util.TxtContentsRepository;
import com.readwide.manager.util.TxtContentsOptions;
import com.readwide.manager.util.TxtContentsScanner;

import java.io.File;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** TXT contents UI and task ownership. Does not build or alter page indexes. */
final class ReaderContentsController {
    private final ReaderActivity activity;
    private final TxtContentsRepository repository;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "txt-contents"));
    private final AtomicInteger request = new AtomicInteger();
    private volatile boolean closed;
    private Future<?> scan;
    private Dialog dialog;
    private AlertDialog editor;
    private TextView status, add, rescan, restore, optionsAction;
    private EditText search;
    private ContentsAdapter adapter;
    private RecyclerView contentsList;
    private boolean initialContentsPositionPending;
    private TxtContentsRepository.Result result;
    private int documentGeneration, foreground, background, currentPosition;
    private String path, transform;
    private boolean large;
    private final Runnable filterTick = this::filter;

    ReaderContentsController(ReaderActivity activity) {
        this.activity = activity;
        repository = new TxtContentsRepository(activity.getApplicationContext());
    }

    void show() {
        if (closed || !activity.textContentReadyForPersistence || activity.filePath == null || activity.readerView == null) {
            ShortToast.show(activity, activity.getString(R.string.file_not_loaded));
            return;
        }
        if (dialog != null && dialog.isShowing()) return;
        documentGeneration = activity.loadGeneration.get();
        path = activity.filePath;
        transform = activity.appliedTextDisplayRuleSignature;
        large = activity.largeTextEstimateActive;
        currentPosition = activity.getBookmarkSaveCharPosition();
        background = activity.dialogStyler().readerDialogBgColor();
        foreground = activity.dialogStyler().readerDialogTextColor(background);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), activity.dpToPx(12));
        panel.addView(activity.dialogStyler().makeReaderDialogTitle(activity.getString(R.string.txt_contents), background, foreground));
        search = activity.dialogStyler().makeReaderDialogEditText(activity.getString(R.string.search), background, foreground,
                activity.dialogStyler().readerDialogSubTextColor(background));
        search.setSingleLine(true);
        panel.addView(search, new LinearLayout.LayoutParams(-1, activity.dpToPx(48)));
        status = new TextView(activity);
        status.setTextColor(foreground);
        status.setTextSize(12);
        status.setPadding(activity.dpToPx(12), activity.dpToPx(8), activity.dpToPx(12), activity.dpToPx(8));
        panel.addView(status);
        RecyclerView list = new RecyclerView(activity);
        contentsList = list;
        initialContentsPositionPending = true;
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setItemAnimator(null);
        adapter = new ContentsAdapter();
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(-1,
                Math.min(activity.dpToPx(300), activity.getResources().getDisplayMetrics().heightPixels / 3)));
        add = action(R.string.txt_contents_add);
        optionsAction = action(R.string.txt_contents_options);
        LinearLayout primaryActions = new LinearLayout(activity);
        LinearLayout.LayoutParams addLayout = actionLayout(0, 1);
        addLayout.setMarginEnd(activity.dpToPx(4));
        LinearLayout.LayoutParams optionsLayout = actionLayout(0, 1);
        optionsLayout.setMarginStart(activity.dpToPx(4));
        primaryActions.addView(add, addLayout);
        primaryActions.addView(optionsAction, optionsLayout);
        panel.addView(primaryActions);
        LinearLayout actions = new LinearLayout(activity);
        rescan = action(R.string.txt_contents_rescan);
        restore = action(R.string.txt_contents_restore);
        LinearLayout.LayoutParams rescanLayout = actionLayout(0, 1);
        rescanLayout.setMarginEnd(activity.dpToPx(4));
        LinearLayout.LayoutParams restoreLayout = actionLayout(0, 1);
        restoreLayout.setMarginStart(activity.dpToPx(4));
        actions.addView(rescan, rescanLayout);
        actions.addView(restore, restoreLayout);
        panel.addView(actions);
        TextView close = action(R.string.close);
        panel.addView(close, actionLayout(-1, 0));
        dialog = activity.dialogStyler().createPositionedReaderDialog(panel, background,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 34, 14, 460, true);
        dialog.setOnDismissListener(d -> {
            cancelScan();
            activity.handler.removeCallbacks(filterTick);
            if (editor != null) editor.dismiss();
        });
        close.setOnClickListener(v -> dialog.dismiss());
        add.setOnClickListener(v -> addCurrent());
        optionsAction.setOnClickListener(v -> showOptions());
        rescan.setOnClickListener(v -> load(true));
        restore.setOnClickListener(v -> edit(null, null, true));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                initialContentsPositionPending = false;
                activity.handler.removeCallbacks(filterTick);
                activity.handler.postDelayed(filterTick, 120);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();
        dialog.show();
        load(false);
    }

    private TextView action(int label) {
        TextView view = activity.dialogStyler().makeReaderDialogActionText(activity.getString(label), foreground, Gravity.CENTER);
        view.setSingleLine(false);
        view.setFocusable(true);
        roundAction(view, false);
        return view;
    }

    private LinearLayout.LayoutParams actionLayout(int width, float weight) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(width, -2, weight);
        layout.topMargin = activity.dpToPx(8);
        return layout;
    }

    /** Local to Contents, including its child alerts; other reader dialogs keep their own style. */
    private void roundAction(TextView view, boolean compact) {
        if (view == null) return;
        int textColor = view.getCurrentTextColor();
        int[][] states = { new int[] { -android.R.attr.state_enabled }, new int[] {} };
        view.setTextColor(new ColorStateList(states, new int[] {
                activity.dialogStyler().blendColors(background, textColor, 0.38f), textColor }));
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(activity.dpToPx(10));
        surface.setColor(new ColorStateList(states, new int[] {
                activity.dialogStyler().blendColors(background, foreground, 0.025f),
                activity.dialogStyler().blendColors(background, foreground, 0.06f) }));
        surface.setStroke(activity.dpToPx(1), new ColorStateList(states, new int[] {
                activity.dialogStyler().blendColors(background, foreground, 0.06f),
                activity.dialogStyler().blendColors(background, foreground, 0.18f) }));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(activity.dpToPx(10));
        int ripple = Color.argb(36, Color.red(foreground), Color.green(foreground), Color.blue(foreground));
        view.setBackgroundTintList(null);
        RippleDrawable drawable = new RippleDrawable(ColorStateList.valueOf(ripple), surface, mask);
        view.setBackground(compact ? new InsetDrawable(drawable, 0, activity.dpToPx(4), 0, activity.dpToPx(4)) : drawable);
        int minimumHeight = activity.dpToPx(compact ? 48 : 40);
        view.setMinHeight(minimumHeight);
        view.setMinimumHeight(minimumHeight);
        int verticalPadding = activity.dpToPx(compact ? 2 : 4);
        view.setPadding(activity.dpToPx(14), verticalPadding, activity.dpToPx(14), verticalPadding);
        if (compact && view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams layout) {
            int horizontalMargin = activity.dpToPx(12);
            layout.setMargins(horizontalMargin, activity.dpToPx(2), horizontalMargin, activity.dpToPx(2));
            // Material's dialog styles can supply marginStart=0. Relative margins
            // take precedence over left/right when Android resolves LTR/RTL layout.
            layout.setMarginStart(horizontalMargin);
            layout.setMarginEnd(horizontalMargin);
            view.setLayoutParams(layout);
        }
    }

    private boolean validDocument() {
        return !closed && !activity.activityDestroyed && !activity.isFinishing()
                && documentGeneration == activity.loadGeneration.get() && path.equals(activity.filePath)
                && large == activity.largeTextEstimateActive && activity.textContentReadyForPersistence
                && transform.equals(activity.appliedTextDisplayRuleSignature)
                && transform.equals(activity.textContentTransformSignatureForPath(path));
    }

    private boolean visible(int token) {
        return token == request.get() && validDocument() && dialog != null && dialog.isShowing();
    }

    private void cancelScan() {
        request.incrementAndGet();
        if (scan != null) scan.cancel(true);
        scan = null;
    }

    private void load(boolean force) {
        if (!validDocument()) { status.setText(R.string.txt_contents_error); return; }
        cancelScan();
        int token = request.get();
        String sourcePath = path;
        String text = large ? null : activity.fileContent; // Immutable reference, not another whole-file copy.
        boolean sourceLarge = large;
        boolean collapse = activity.largeTextActiveCollapseBlankLines;
        long sourceLength = activity.contentsSourceLength, sourceModified = activity.contentsSourceModified;
        String exactRules = TextDisplayRuleManager.getExactSignature(activity.getApplicationContext(), sourcePath);
        TextDisplayRuleManager.ActiveSnapshot rules = TextDisplayRuleManager.captureActive(activity.getApplicationContext(), sourcePath);
        int generation = documentGeneration;
        result = null;
        filter();
        busy(true);
        Consumer<Throwable> failed = failure -> activity.handler.post(() -> {
            if (!visible(token)) return;
            busy(false);
            status.setText(R.string.txt_contents_error);
        });
        scan = submitTask(worker, () -> {
            com.readwide.manager.util.LargeTextPageIndexCache.Checkpoint check = () -> {
                if (closed || request.get() != token || generation != activity.loadGeneration.get() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Contents scan cancelled");
                }
            };
            try {
                check.check();
                TxtContentsRepository.Result loaded;
                if (sourceLarge) {
                    File file = new File(sourcePath);
                    String encoding = activity.resolveTextEncodingForFile(file);
                    loaded = repository.loadLarge(file, encoding, exactRules, rules.compiled, collapse,
                            sourceLength, sourceModified, force, check);
                } else loaded = repository.loadText(sourcePath, text, force, check);
                check.check();
                activity.handler.post(() -> {
                    if (!visible(token)) return;
                    result = loaded;
                    busy(false);
                    filter();
                });
            } catch (InterruptedIOException cancelled) {
                // A closed/replaced dialog must never receive old results.
            } catch (Exception | OutOfMemoryError | StackOverflowError failure) {
                failed.accept(failure);
            }
        }, failed);
    }

    /** Cancel a task rejected during worker startup, even if the executor queued it first. */
    static Future<?> submitTask(ExecutorService worker, Runnable action, Consumer<Throwable> failed) {
        FutureTask<Void> task = null;
        try {
            task = new FutureTask<>(action, null);
            worker.execute(task);
            return task;
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            if (task != null) task.cancel(false);
            failed.accept(failure);
            return null;
        }
    }

    private void busy(boolean busy) {
        optionsAction.setEnabled(!busy);
        add.setEnabled(!busy && result != null);
        restore.setEnabled(!busy && result != null);
        rescan.setEnabled(!busy);
        search.setEnabled(!busy);
        adapter.enabled = !busy;
        if (busy) status.setText(R.string.loading);
    }

    private void filter() {
        if (adapter == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        adapter.rows.clear();
        String activeId = null;
        if (result != null) {
            for (TxtContentsScanner.Entry e : result.entries) {
                if (e.position <= currentPosition) activeId = e.id;
                if (query.isEmpty() || e.title.toLowerCase(Locale.ROOT).contains(query)) adapter.rows.add(e);
            }
        }
        adapter.activeId = activeId;
        adapter.notifyDataSetChanged();
        if (initialContentsPositionPending && result != null && query.isEmpty()) {
            initialContentsPositionPending = false;
            for (int i = 0; i < adapter.rows.size(); i++) {
                if (adapter.rows.get(i).id.equals(activeId)) {
                    ((LinearLayoutManager) contentsList.getLayoutManager())
                            .scrollToPositionWithOffset(Math.max(0, i - 1), 0);
                    break;
                }
            }
        }
        if (result != null) {
            String message = activity.getString(adapter.rows.isEmpty() ? R.string.txt_contents_empty : R.string.txt_contents_hint);
            if (result.previousEdits) message += "\n" + activity.getString(R.string.txt_contents_old_edits);
            if (result.cacheUnavailable) message += "\n" + activity.getString(R.string.txt_contents_cache_error);
            status.setText(message);
        }
    }

    private void addCurrent() {
        if (!validDocument() || result == null) return;
        int position = activity.getBookmarkSaveCharPosition();
        if (position < 0 || position > result.characters) return;
        String before = activity.getAnchorTextBefore(position), after = activity.getAnchorTextAfter(position);
        EditText title = activity.dialogStyler().makeReaderDialogEditText(activity.getString(R.string.txt_contents_title), background,
                foreground, activity.dialogStyler().readerDialogSubTextColor(background));
        title.setSingleLine(true);
        title.setFilters(new InputFilter[] { new InputFilter.LengthFilter(TxtContentsScanner.MAX_TITLE) });
        String excerpt = activity.getExcerpt(position);
        if (excerpt != null) title.setText(excerpt.replace('\n', ' ').trim());
        title.selectAll();
        LinearLayout box = editorBody();
        box.addView(title, new LinearLayout.LayoutParams(-1, activity.dpToPx(52)));
        AlertDialog next = new AlertDialog.Builder(activity)
                .setCustomTitle(activity.dialogStyler().makeReaderDialogTitle(
                        activity.getString(R.string.txt_contents_add), background, foreground)).setView(box)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create();
        showEditor(next, () -> next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = title.getText().toString().trim();
            if (value.isEmpty()) { title.setError(activity.getString(R.string.txt_contents_title)); return; }
            edit(new TxtContentsScanner.Entry("manual:" + UUID.randomUUID(), value, position, 0,
                    before == null ? "" : before, after == null ? "" : after, true), null, false);
            next.dismiss();
        }));
    }

    private void remove(TxtContentsScanner.Entry entry) {
        if (!validDocument()) return;
        int label = entry.manual ? R.string.delete : R.string.txt_contents_hide;
        LinearLayout box = editorBody();
        box.setPadding(activity.dpToPx(22), activity.dpToPx(4), activity.dpToPx(22), activity.dpToPx(4));
        TextView message = new TextView(activity);
        message.setText(entry.title);
        message.setTextColor(foreground);
        message.setTextSize(16f);
        message.setLineSpacing(0f, 1.15f);
        box.addView(message, new LinearLayout.LayoutParams(-1, -2));
        TextView heading = activity.dialogStyler().makeReaderDialogTitle(activity.getString(label), background, foreground);
        heading.setPadding(activity.dpToPx(18), activity.dpToPx(8), activity.dpToPx(18), activity.dpToPx(6));
        AlertDialog next = new AlertDialog.Builder(activity)
                .setCustomTitle(heading).setView(box)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(label, (d, which) -> edit(null, entry.id, false)).create();
        showEditor(next, () -> {
            if (entry.manual) {
                int danger = activity.isLightColor(background) ? Color.rgb(95, 35, 35) : Color.rgb(255, 170, 170);
                activity.dialogStyler().styleReaderDialogButton(next.getButton(AlertDialog.BUTTON_POSITIVE), danger);
            }
        });
    }

    private LinearLayout editorBody() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(activity.dpToPx(22), activity.dpToPx(12), activity.dpToPx(22), activity.dpToPx(8));
        return box;
    }

    private void showEditor(AlertDialog next, Runnable configureActions) {
        if (!validDocument() || dialog == null || !dialog.isShowing()) return;
        if (editor != null) editor.dismiss();
        editor = next;
        // Use the shared rounded alert skin, not the positioned-dialog factory:
        // that factory would dismiss the contents list and invalidate its edit token.
        activity.dialogStyler().prepareReaderAlertDialogWindowNoJump(next, true);
        next.setOnShowListener(d -> {
            activity.dialogStyler().styleReaderDialogWindow(next, background, foreground,
                    activity.dialogStyler().readerDialogSubTextColor(background));
            activity.dialogStyler().prepareReaderAlertDialogWindowNoJump(next, false);
            configureActions.run();
            roundAction(next.getButton(AlertDialog.BUTTON_POSITIVE), true);
            roundAction(next.getButton(AlertDialog.BUTTON_NEGATIVE), true);
            roundAction(next.getButton(AlertDialog.BUTTON_NEUTRAL), true);
            TextView positive = next.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null && positive.getParent() instanceof View buttonPanel) {
                // Keep the actions away from the lower card edge without translating
                // them over the content or separating drawing from hit-test bounds.
                buttonPanel.setPadding(activity.dpToPx(12), 0, activity.dpToPx(12), activity.dpToPx(10));
                buttonPanel.setMinimumHeight(activity.dpToPx(48));
            }
            if (next.getWindow() != null) {
                View decor = next.getWindow().getDecorView();
                decor.post(() -> {
                    if (editor == next && next.isShowing() && validDocument()) decor.setAlpha(1f);
                });
            }
        });
        next.setOnDismissListener(d -> { if (editor == next) editor = null; });
        next.show();
        activity.dialogStyler().prepareReaderAlertDialogWindowNoJump(next, false);
    }

    private void showOptions() {
        if (!validDocument()) return;
        if (result != null) { showOptions(result.options); return; }
        // A scan failure must not replace existing options with an assumed default draft.
        String sourcePath = path;
        int token = request.get();
        busy(true);
        Consumer<Throwable> failed = failure -> activity.handler.post(() -> {
            if (!visible(token)) return;
            busy(false);
            status.setText(R.string.txt_contents_error);
        });
        submitTask(worker, () -> {
            try {
                TxtContentsOptions saved = repository.loadOptions(sourcePath);
                activity.handler.post(() -> {
                    if (!visible(token)) return;
                    busy(false);
                    status.setText(R.string.txt_contents_error);
                    showOptions(saved);
                });
            } catch (Exception | OutOfMemoryError | StackOverflowError failure) {
                failed.accept(failure);
            }
        }, failed);
    }

    private void showOptions(TxtContentsOptions current) {
        LinearLayout box = editorBody();
        optionsLabel(box, R.string.txt_contents_options_help);
        optionsLabel(box, R.string.txt_contents_formats);
        int[] formats = { TxtContentsOptions.ENGLISH, TxtContentsOptions.KOREAN, TxtContentsOptions.CJK,
                TxtContentsOptions.EQUALS, TxtContentsOptions.HYPHENS, TxtContentsOptions.WRAPPED };
        // These labels are literal examples of the syntax, not untranslated UI prose.
        String[] examples = { "Chapter 1 / Part I / Book 1", "761화 / 제1장 / 제1부 / 제1권",
                "第1章 / 第1回 / 第1節 / 第1卷 / 第1巻 / 第1部", "345===", "345---", "<--Title-->" };
        CheckBox[] selected = new CheckBox[formats.length];
        for (int i = 0; i < formats.length; i++) selected[i] = optionCheck(box, examples[i], current.enabled(formats[i]));
        CheckBox blank = optionCheck(box, activity.getString(R.string.txt_contents_require_blank), current.requireBlank);
        CheckBox repeated = optionCheck(box, activity.getString(R.string.txt_contents_require_repeated), current.requireRepeated);
        CheckBox prose = optionCheck(box, activity.getString(R.string.txt_contents_reduce_prose), current.reduceProse);
        CheckBox combine = optionCheck(box, activity.getString(R.string.txt_contents_combine_title), current.combineTitle);
        CheckBox custom = optionCheck(box, activity.getString(R.string.txt_contents_custom), current.enabled(TxtContentsOptions.CUSTOM));
        LinearLayout advanced = new LinearLayout(activity);
        advanced.setOrientation(LinearLayout.VERTICAL);
        optionsLabel(advanced, R.string.txt_contents_custom_help);
        TextView example = new TextView(activity);
        example.setText("^\\[(.+)\\]$\n^([0-9]+[.)] .+)$");
        example.setTypeface(Typeface.MONOSPACE);
        example.setTextColor(foreground);
        example.setTextSize(13);
        advanced.addView(example);
        EditText rules = activity.dialogStyler().makeReaderDialogEditText("", background, foreground,
                activity.dialogStyler().readerDialogSubTextColor(background));
        rules.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        rules.setSingleLine(false);
        rules.setMinLines(3);
        rules.setMaxLines(7);
        rules.setGravity(Gravity.TOP | Gravity.START);
        rules.setTypeface(Typeface.MONOSPACE);
        rules.setTextSize(14);
        rules.setPadding(activity.dpToPx(12), activity.dpToPx(8), activity.dpToPx(12), activity.dpToPx(8));
        rules.setFilters(new InputFilter[] { new InputFilter.LengthFilter(TxtContentsOptions.MAX_CUSTOM_TEXT) });
        rules.setText(current.customRules);
        LinearLayout.LayoutParams rulesLayout = new LinearLayout.LayoutParams(-1, -2);
        rulesLayout.topMargin = activity.dpToPx(12);
        advanced.addView(rules, rulesLayout);
        box.addView(advanced);
        advanced.setVisibility(custom.isChecked() ? View.VISIBLE : View.GONE);
        custom.setOnCheckedChangeListener((button, checked) -> advanced.setVisibility(checked ? View.VISIBLE : View.GONE));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        LinearLayout holder = new LinearLayout(activity);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(scroll, new LinearLayout.LayoutParams(-1,
                Math.min(activity.dpToPx(380), activity.getResources().getDisplayMetrics().heightPixels / 2)));

        // Own this row: AlertDialog's ButtonBarLayout stacks and reverses the
        // three actions when their labels/margins no longer fit the available width.
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setBaselineAligned(false);
        actions.setPadding(activity.dpToPx(12), 0, activity.dpToPx(12), activity.dpToPx(10));
        Button resetAction = optionAction(R.string.reset_to_default, true);
        Button cancelAction = optionAction(R.string.cancel, true);
        Button saveAction = optionAction(R.string.save, false);
        actions.addView(resetAction, new LinearLayout.LayoutParams(0, -1, 2));
        LinearLayout.LayoutParams cancelLayout = new LinearLayout.LayoutParams(0, -1, 1);
        cancelLayout.setMarginStart(activity.dpToPx(24));
        actions.addView(cancelAction, cancelLayout);
        LinearLayout.LayoutParams saveLayout = new LinearLayout.LayoutParams(0, -1, 1);
        saveLayout.setMarginStart(activity.dpToPx(8));
        actions.addView(saveAction, saveLayout);
        holder.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog next = new AlertDialog.Builder(activity)
                .setCustomTitle(activity.dialogStyler().makeReaderDialogTitle(
                        activity.getString(R.string.txt_contents_options), background, foreground))
                .setView(holder).create();
        showEditor(next, () -> {
            cancelAction.setOnClickListener(v -> next.dismiss());
            resetAction.setOnClickListener(v -> {
                for (CheckBox choice : selected) choice.setChecked(true);
                blank.setChecked(true); repeated.setChecked(true); prose.setChecked(true); combine.setChecked(true);
                custom.setChecked(false); rules.setText(""); rules.setError(null);
            });
            saveAction.setOnClickListener(v -> {
                if (!validDocument()) { next.dismiss(); return; }
                int mask = custom.isChecked() ? TxtContentsOptions.CUSTOM : 0;
                for (int i = 0; i < selected.length; i++) if (selected[i].isChecked()) mask |= formats[i];
                TxtContentsOptions updated = new TxtContentsOptions(mask, blank.isChecked(), repeated.isChecked(),
                        prose.isChecked(), combine.isChecked(), rules.getText().toString());
                saveOptions(next, rules, updated, saveAction, resetAction);
            });
        });
    }

    private Button optionAction(int label, boolean secondary) {
        Button button = new Button(activity);
        button.setText(label);
        int color = secondary ? activity.dialogStyler().readerDialogSubTextColor(background) : foreground;
        activity.dialogStyler().styleReaderDialogButton(button, color);
        roundAction(button, true); // No layout params yet: spacing belongs to the owned row.
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setSingleLine(false);
        button.setEllipsize(null);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setPadding(activity.dpToPx(8), activity.dpToPx(2), activity.dpToPx(8), activity.dpToPx(2));
        return button;
    }

    private CheckBox optionCheck(LinearLayout parent, String label, boolean checked) {
        CheckBox view = new CheckBox(activity);
        view.setText(label);
        view.setTextSize(14);
        view.setMinHeight(activity.dpToPx(48));
        activity.dialogStyler().styleCompoundForReaderDialog(view, background, foreground);
        view.setChecked(checked);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private void optionsLabel(LinearLayout parent, int text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(foreground);
        label.setTextSize(13);
        label.setPadding(0, activity.dpToPx(8), 0, activity.dpToPx(8));
        parent.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    private void saveOptions(AlertDialog next, EditText rules, TxtContentsOptions updated,
                             Button saveAction, Button resetAction) {
        String sourcePath = path;
        int token = request.get();
        busy(true);
        saveAction.setEnabled(false);
        resetAction.setEnabled(false);
        rules.setError(null);
        Consumer<Throwable> failed = failure -> activity.handler.post(() -> {
            if (!visible(token)) return;
            busy(false);
            filter();
            if (editor == next && next.isShowing()) {
                saveAction.setEnabled(true);
                resetAction.setEnabled(true);
                if (failure instanceof TxtContentsOptions.InvalidPattern invalid) {
                    rules.setError(activity.getString(R.string.txt_contents_invalid_pattern, invalid.line, invalid.getMessage()));
                    rules.requestFocus();
                } else ShortToast.show(activity, activity.getString(R.string.txt_contents_save_error));
            } else ShortToast.show(activity, activity.getString(R.string.txt_contents_save_error));
        });
        submitTask(worker, () -> {
            try {
                repository.saveOptions(sourcePath, updated);
                activity.handler.post(() -> {
                    if (!visible(token)) return;
                    if (editor == next) next.dismiss();
                    load(false);
                });
            } catch (Exception | OutOfMemoryError | StackOverflowError failure) {
                failed.accept(failure);
            }
        }, failed);
    }

    private void edit(TxtContentsScanner.Entry addEntry, String removeId, boolean restoreHidden) {
        if (!validDocument() || result == null) return;
        TxtContentsRepository.Result snapshot = result;
        int token = request.get();
        busy(true);
        // Do not cancel an accepted edit on dismissal/destruction: let the atomic write finish.
        Consumer<Throwable> failed = failure -> activity.handler.post(() -> {
            if (!closed && !activity.activityDestroyed) ShortToast.show(activity, activity.getString(R.string.txt_contents_save_error));
            if (visible(token)) { busy(false); filter(); }
        });
        submitTask(worker, () -> {
            try {
                TxtContentsRepository.Result edited = repository.edit(snapshot, addEntry, removeId, restoreHidden);
                activity.handler.post(() -> {
                    if (!visible(token)) return;
                    result = edited;
                    busy(false);
                    filter();
                });
            } catch (Exception | OutOfMemoryError | StackOverflowError failure) {
                failed.accept(failure);
            }
        }, failed);
    }

    private int[] exactPage(TxtContentsScanner.Entry entry) {
        if (!validDocument()) return new int[] { 0, 0 };
        if (!large) return new int[] { activity.readerView.getPageNumberForCharPosition(entry.position), activity.readerView.getTotalPageCount() };
        return activity.largeTextExactPageIndexState.pageForCharIfUsable(
                activity.buildCurrentLargeTextExactPageIndexSignature(path), entry.position);
    }

    void close() {
        closed = true;
        cancelScan();
        if (dialog != null) dialog.dismiss();
        if (editor != null) editor.dismiss();
        activity.handler.removeCallbacks(filterTick);
        worker.shutdown(); // Accepted edits finish; scan checkpoints stop obsolete work.
    }

    private final class ContentsAdapter extends RecyclerView.Adapter<Row> {
        final List<TxtContentsScanner.Entry> rows = new ArrayList<>();
        String activeId;
        boolean enabled;
        @Override public Row onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView text = new TextView(activity);
            text.setTextSize(16);
            text.setMinHeight(activity.dpToPx(52));
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setMaxLines(3);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new Row(text);
        }
        @Override public void onBindViewHolder(Row holder, int position) {
            TxtContentsScanner.Entry entry = rows.get(position);
            boolean active = entry.id.equals(activeId);
            int page = exactPage(entry)[0];
            holder.text.setText(page > 0 ? activity.getString(R.string.txt_contents_page, entry.title, page) : entry.title);
            holder.text.setTextColor(foreground);
            holder.text.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            holder.text.setBackgroundColor(active ? activity.dialogStyler().blendColors(background, foreground, 0.12f) : Color.TRANSPARENT);
            holder.text.setPadding(activity.dpToPx(10 + 16 * entry.level), activity.dpToPx(8), activity.dpToPx(10), activity.dpToPx(8));
            holder.text.setOnClickListener(v -> {
                if (!enabled || !validDocument()) return;
                if (large) {
                    File file = new File(path);
                    if (!file.isFile() || file.length() != activity.contentsSourceLength || file.lastModified() != activity.contentsSourceModified) {
                        status.setText(R.string.txt_contents_error);
                        return;
                    }
                }
                int[] target = exactPage(entry);
                dialog.dismiss();
                activity.jumpToAbsoluteCharPosition(entry.position, target[0], target[1], entry.before, entry.after, true, false);
            });
            holder.text.setOnLongClickListener(v -> {
                if (!enabled || !validDocument()) return false;
                remove(entry);
                return true;
            });
        }
        @Override public int getItemCount() { return rows.size(); }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        final TextView text;
        Row(TextView text) { super(text); this.text = text; }
    }
}
