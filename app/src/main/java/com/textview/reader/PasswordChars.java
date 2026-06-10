package com.textview.reader;

import androidx.annotation.Nullable;

import java.util.Arrays;

/** Small utilities for short-lived archive password char arrays. */
final class PasswordChars {
    private PasswordChars() {}

    static boolean hasPassword(@Nullable char[] password) {
        return password != null && password.length > 0;
    }

    @Nullable
    static char[] cloneOf(@Nullable char[] password) {
        return password == null ? null : password.clone();
    }

    static void clear(@Nullable char[] password) {
        if (password != null) Arrays.fill(password, '\0');
    }
}
