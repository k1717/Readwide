package com.readwide.manager;

import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;

/** Temporary renderer helpers share scripting permission only within one pane load. */
final class DocumentJavascriptExecutor {
    private final DocumentPageActivity activity;
    private Pane primary;
    private Pane right;

    DocumentJavascriptExecutor(DocumentPageActivity activity) {
        this.activity = activity;
    }

    void evaluate(WebView target, int page, String script, ValueCallback<String> callback) {
        if (activity.activityDestroyed || target == null
                || (target != activity.webView && target != activity.rightWebView)
                || activity.documentPageIndexForWebView(target) != page) {
            // Existing capture callbacks need a terminal empty result even when
            // their view has been retired; their own position guards still apply.
            if (callback != null) callback.onReceiveValue(null);
            return;
        }
        boolean isRight = target == activity.rightWebView;
        Pane pane = isRight ? right : primary;
        WebSettings settings = target.getSettings();
        if (pane == null || !pane.isCurrent() || pane.target != target || pane.page != page) {
            pane = new Pane(target, page, isRight, !settings.getJavaScriptEnabled());
        }
        // Allocate the callback/lease before publishing or incrementing ownership.
        Request request = new Request(pane, callback);
        if (isRight) right = pane; else primary = pane;
        pane.pending++;
        try {
            if (!settings.getJavaScriptEnabled()) settings.setJavaScriptEnabled(true);
            target.evaluateJavascript(script, request);
        } catch (RuntimeException | Error failure) {
            request.releaseAfterFailure(failure);
            throw failure;
        }
    }

    /** New loads have already applied their own policy; never restore the old one over it. */
    void retire(WebView target, boolean restoreTemporaryPolicy) {
        if (target == null) return;
        Pane pane = primary != null && primary.target == target ? primary
                : right != null && right.target == target ? right : null;
        if (pane == null) return;
        forget(pane);
        if (restoreTemporaryPolicy && pane.temporaryPermission && !activity.activityDestroyed
                && target == (pane.isRight ? activity.rightWebView : activity.webView)) {
            target.getSettings().setJavaScriptEnabled(
                    activity.documentPageRequiresJavaScript(activity.documentPageIndexForWebView(target)));
        }
    }

    void clear() {
        primary = null;
        right = null;
    }

    private void forget(Pane pane) {
        if (primary == pane) primary = null;
        if (right == pane) right = null;
    }

    private final class Pane {
        final WebView target;
        final int page;
        final boolean isRight;
        final int documentLoad;
        final int paneLoad;
        final boolean temporaryPermission;
        int pending;

        Pane(WebView target, int page, boolean isRight, boolean temporaryPermission) {
            this.target = target;
            this.page = page;
            this.isRight = isRight;
            this.temporaryPermission = temporaryPermission;
            documentLoad = activity.loadGeneration;
            paneLoad = isRight ? activity.rightDocumentPageLoadGeneration
                    : activity.primaryDocumentPageLoadGeneration;
        }

        boolean isCurrent() {
            return !activity.activityDestroyed && documentLoad == activity.loadGeneration
                    && target == (isRight ? activity.rightWebView : activity.webView)
                    && paneLoad == (isRight ? activity.rightDocumentPageLoadGeneration
                    : activity.primaryDocumentPageLoadGeneration)
                    && page == activity.documentPageIndexForWebView(target);
        }
    }

    private final class Request implements ValueCallback<String> {
        private final Pane pane;
        private final ValueCallback<String> callback;
        private boolean delivered;
        private boolean released;

        Request(Pane pane, ValueCallback<String> callback) {
            this.pane = pane;
            this.callback = callback;
        }

        @Override public void onReceiveValue(String value) {
            if (delivered) return;
            delivered = true;
            Throwable failure = null;
            try {
                // A continuation can start another helper before releasing this
                // request, without briefly disabling that helper's permission.
                if (callback != null) callback.onReceiveValue(value);
            } catch (RuntimeException | Error callbackFailure) {
                failure = callbackFailure;
                throw callbackFailure;
            } finally {
                if (failure == null) release(); else releaseAfterFailure(failure);
            }
        }

        void release() {
            if (released) return;
            released = true;
            pane.pending--;
            if (pane.pending != 0 || (pane.isRight ? right : primary) != pane) return;
            try {
                if (pane.isCurrent() && pane.temporaryPermission) {
                    pane.target.getSettings().setJavaScriptEnabled(
                            activity.documentPageRequiresJavaScript(pane.page));
                }
            } finally {
                forget(pane);
            }
        }

        void releaseAfterFailure(Throwable failure) {
            // A rejected submission must not later deliver a retained callback.
            delivered = true;
            try {
                release();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    try {
                        failure.addSuppressed(cleanupFailure);
                    } catch (OutOfMemoryError | StackOverflowError ignored) {
                        // Keep the original failure even if suppression cannot allocate.
                    }
                }
            }
        }
    }
}
