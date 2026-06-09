package com.textview.reader;

import androidx.annotation.NonNull;

import com.textview.reader.model.LargeTextLinePartitionResult;
import com.textview.reader.util.LargeTextPartitionReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;

final class ReaderLargeTextPartitionReadController {
    private final ReaderActivity activity;

    ReaderLargeTextPartitionReadController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    float estimateBytesPerChar(@NonNull File file) {
        return LargeTextPartitionReader.estimateBytesPerChar(file, ReaderActivity.LARGE_TEXT_PREVIEW_BYTES);
    }

    BufferedReader openReader(@NonNull File file) throws IOException {
        return LargeTextPartitionReader.openReader(file, activity.resolveTextEncodingForFile(file));
    }

    LargeTextLinePartitionResult readForChar(@NonNull File file,
                                             int targetCharPosition) throws IOException {
        return LargeTextPartitionReader.readPartitionForChar(
                activity.getApplicationContext(),
                file,
                activity.resolveTextEncodingForFile(file),
                targetCharPosition,
                activity.getLargeTextPartitionLines(),
                activity.getLargeTextPartitionLookaheadLines(),
                activity.getLargeTextPartitionLookbehindLines());
    }

    LargeTextLinePartitionResult readAtStartLine(@NonNull File file,
                                                 int requestedStartLine) throws IOException {
        return LargeTextPartitionReader.readPartitionAtStartLine(
                activity.getApplicationContext(),
                file,
                activity.resolveTextEncodingForFile(file),
                requestedStartLine,
                activity.getLargeTextPartitionLines(),
                activity.getLargeTextPartitionLookaheadLines(),
                activity.getLargeTextPartitionLookbehindLines());
    }

    LargeTextLinePartitionResult readAtStartLine(@NonNull File file,
                                                 int requestedStartLine,
                                                 int knownTotalLines,
                                                 int knownTotalChars,
                                                 boolean includeLookbehind) throws IOException {
        return LargeTextPartitionReader.readPartitionAtStartLine(
                activity.getApplicationContext(),
                file,
                activity.resolveTextEncodingForFile(file),
                requestedStartLine,
                knownTotalLines,
                knownTotalChars,
                activity.getLargeTextPartitionLines(),
                activity.getLargeTextPartitionLookaheadLines(),
                activity.getLargeTextPartitionLookbehindLines(),
                includeLookbehind);
    }
}
