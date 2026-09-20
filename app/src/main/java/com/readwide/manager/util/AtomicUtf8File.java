package com.readwide.manager.util;

import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Crash-safe UTF-8 text persistence for small app-owned JSON files. */
final class AtomicUtf8File {
    private AtomicUtf8File() {}

    /** openRead must run even when only the platform's recovery file remains. */
    @Nullable
    static String readIfPresent(@NonNull File file) throws IOException {
        try { return read(file); }
        catch (FileNotFoundException missing) {
            // A surviving base/recovery file means an unreadable store, not an empty one.
            if (file.exists() || new File(file.getPath() + ".bak").exists()) throw missing;
            return null;
        }
    }

    @NonNull
    static String read(@NonNull File file) throws IOException {
        AtomicFile atomicFile = new AtomicFile(file);
        try (FileInputStream input = atomicFile.openRead();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                text.append(buffer, 0, count);
            }
            return text.toString();
        }
    }

    static void write(@NonNull File file, @NonNull String content) throws IOException {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
            // AtomicFile logs some sync/rename failures instead of throwing them.
            // Surface sync failures before it discards the recovery copy.
            output.getFD().sync();
            atomicFile.finishWrite(output);
            output = null;
            if (!file.isFile() || new File(file.getPath() + ".new").exists()
                    || new File(file.getPath() + ".bak").exists()) {
                throw new IOException("Atomic file commit did not complete: " + file.getName());
            }
        } catch (IOException | RuntimeException failure) {
            if (output != null) atomicFile.failWrite(output);
            throw failure;
        }
    }
}
