package com.textview.reader.widget;

import com.textview.reader.UiColorUtils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;

import androidx.core.graphics.drawable.DrawableCompat;

import java.util.Locale;

/**
 * WebView wrapper for document pages.
 *
 * Android WebView normally opens text selection through the platform floating
 * toolbar. On some devices/themes that toolbar ignores app-level popup styling
 * and appears as a low-contrast gray bubble. This wrapper forces WebView text
 * selection into the normal contextual ActionMode path and tints the menu items,
 * giving the document reader a stable high-contrast selection UI independent of
 * MainActivity's light/dark theme.
 */
public class SelectionContrastWebView extends WebView {
    private int toolbarBackground = Color.rgb(250, 250, 250);
    private int toolbarForeground = Color.BLACK;
    private int toolbarBorder = Color.rgb(218, 218, 218);

    public SelectionContrastWebView(Context context) {
        super(context);
    }

    public SelectionContrastWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SelectionContrastWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setSelectionToolbarColors(int background, int foreground) {
        this.toolbarBackground = background;
        this.toolbarForeground = foreground;
        this.toolbarBorder = isDark(background) ? Color.rgb(72, 72, 72) : Color.rgb(220, 220, 220);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback) {
        return startActionMode(callback, ActionMode.TYPE_PRIMARY);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback, int type) {
        // Do not use TYPE_FLOATING here. The floating WebView toolbar is exactly
        // the gray bubble that Android can draw outside this app's theme control.
        return super.startActionMode(wrapCallback(callback), ActionMode.TYPE_PRIMARY);
    }

    @Override
    public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback) {
        return startActionModeForChild(originalView, callback, ActionMode.TYPE_PRIMARY);
    }

    @Override
    public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback, int type) {
        return super.startActionModeForChild(originalView, wrapCallback(callback), ActionMode.TYPE_PRIMARY);
    }

    private ActionMode.Callback wrapCallback(ActionMode.Callback callback) {
        if (callback instanceof ActionMode.Callback2) {
            ActionMode.Callback2 callback2 = (ActionMode.Callback2) callback;
            return new ActionMode.Callback2() {
                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    boolean ok = callback2.onCreateActionMode(mode, menu);
                    styleActionMode(mode, menu);
                    return ok;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    boolean result = callback2.onPrepareActionMode(mode, menu);
                    styleActionMode(mode, menu);
                    return result;
                }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    return callback2.onActionItemClicked(mode, item);
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    callback2.onDestroyActionMode(mode);
                }

                @Override
                public void onGetContentRect(ActionMode mode, View view, android.graphics.Rect outRect) {
                    callback2.onGetContentRect(mode, view, outRect);
                }
            };
        }

        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                boolean ok = callback.onCreateActionMode(mode, menu);
                styleActionMode(mode, menu);
                return ok;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                boolean result = callback.onPrepareActionMode(mode, menu);
                styleActionMode(mode, menu);
                return result;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return callback.onActionItemClicked(mode, item);
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                callback.onDestroyActionMode(mode);
            }
        };
    }

    private void styleActionMode(ActionMode mode, Menu menu) {
        if (mode != null) {
            mode.setTitle(optionalStyledText(mode.getTitle()));
            mode.setSubtitle(optionalStyledText(mode.getSubtitle()));
        }
        tintMenu(menu);
        postDelayed(this::styleVisibleActionModeViews, 16);
        postDelayed(this::styleVisibleActionModeViews, 80);
    }

    private CharSequence optionalStyledText(CharSequence text) {
        if (text == null) return null;
        SpannableString styled = new SpannableString(text);
        styled.setSpan(new ForegroundColorSpan(toolbarForeground), 0, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private void tintMenu(Menu menu) {
        if (menu == null) return;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            CharSequence title = item.getTitle();
            if (title != null) {
                SpannableString styled = new SpannableString(title);
                styled.setSpan(new ForegroundColorSpan(toolbarForeground), 0, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                item.setTitle(styled);
            }
            android.graphics.drawable.Drawable icon = item.getIcon();
            if (icon != null) {
                android.graphics.drawable.Drawable wrapped = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(wrapped, toolbarForeground);
                item.setIcon(wrapped);
            }
        }
    }

    private void styleVisibleActionModeViews() {
        View root = getRootView();
        if (root != null) styleTree(root, 0);
    }

    private void styleTree(View view, int depth) {
        if (view == null || depth > 8) return;
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        if (cls.contains("action") || cls.contains("toolbar") || cls.contains("floating") || cls.contains("menu")) {
            view.setBackground(makeToolbarBackground());
        }
        if (view instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) view;
            CharSequence text = tv.getText();
            if (looksLikeSelectionCommand(text)) {
                tv.setTextColor(toolbarForeground);
                tv.setHintTextColor(toolbarForeground);
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleTree(group.getChildAt(i), depth + 1);
            }
        }
    }

    private boolean looksLikeSelectionCommand(CharSequence text) {
        if (text == null) return false;
        String t = text.toString().trim().toLowerCase(Locale.ROOT);
        return t.contains("copy") || t.contains("select") || t.contains("paste")
                || t.contains("cut") || t.contains("share") || t.contains("translate")
                || t.contains("복사") || t.contains("선택") || t.contains("붙여넣기")
                || t.contains("잘라내기") || t.contains("공유") || t.contains("번역");
    }

    private android.graphics.drawable.Drawable makeToolbarBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(toolbarBackground);
        bg.setCornerRadius(dp(22));
        bg.setStroke(Math.max(1, dp(1)), toolbarBorder);
        return bg;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isDark(int color) {
        return UiColorUtils.isDarkColor(color);
    }
}
