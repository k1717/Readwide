package com.readwide.manager.util;

import android.util.AtomicFile;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Crash-safe UTF-8 text persistence for small app-owned JSON files. */
final class AtomicUtf8File {
    private AtomicUtf8File() {}

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
            atomicFile.finishWrite(output);
            output = null;
        } catch (IOException | RuntimeException failure) {
            if (output != null) atomicFile.failWrite(output);
            throw failure;
        }
    }
}
