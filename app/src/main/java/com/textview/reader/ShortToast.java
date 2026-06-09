package com.textview.reader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

/**
 * Centralized app-wide short toast helper.
 *
 * Android's Toast.LENGTH_SHORT is much longer than this app's preferred brief
 * feedback duration, so all short toasts should go through this helper. It also
 * cancels the previous short toast to avoid a queued stack of stale messages.
 */
final class ShortToast {
    static final long DURATION_MS = 700L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static Toast activeToast;

    private ShortToast() {}

    static void show(@NonNull Context context, @StringRes int messageRes) {
        showInternal(context, messageRes, null);
    }

    static void show(@NonNull Context context, @NonNull CharSequence message) {
        showInternal(context, 0, message);
    }

    static Toast showTracked(@NonNull Context context, @NonNull CharSequence message) {
        return showInternal(context, 0, message);
    }

    private static Toast showInternal(@NonNull Context context,
                                      @StringRes int messageRes,
                                      CharSequence message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN_HANDLER.post(() -> showInternal(context, messageRes, message));
            return null;
        }
        cancelActiveToast();
        Context safeContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        Toast toast = message != null
                ? Toast.makeText(safeContext, message, Toast.LENGTH_SHORT)
                : Toast.makeText(safeContext, messageRes, Toast.LENGTH_SHORT);
        activeToast = toast;
        toast.show();
        MAIN_HANDLER.postDelayed(() -> {
            if (activeToast == toast) {
                toast.cancel();
                activeToast = null;
            }
        }, DURATION_MS);
        return toast;
    }

    private static void cancelActiveToast() {
        if (activeToast != null) {
            activeToast.cancel();
            activeToast = null;
        }
    }
}
