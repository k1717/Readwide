package com.textview.reader.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.view.CustomReaderView;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class LargeTextExactPageIndexState {
    private final Object lock = new Object();
    private final AtomicInteger generation = new AtomicInteger(0);
    private ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>();
    private boolean ready;
    private boolean building;
    private boolean failed;
    private String failure = "";
    private String signature = "";

    public void invalidate() {
        generation.incrementAndGet();
    }

    public void reset() {
        generation.incrementAndGet();
        synchronized (lock) {
            anchors = new ArrayList<>();
            ready = false;
            building = false;
            failed = false;
            failure = "";
            signature = "";
        }
    }

    public int beginBuild(@NonNull String nextSignature) {
        synchronized (lock) {
            if (nextSignature.equals(signature) && (building || ready)) {
                return -1;
            }
            ready = false;
            anchors = new ArrayList<>();
            building = true;
            failed = false;
            failure = "";
            signature = nextSignature;
            return generation.incrementAndGet();
        }
    }

    public boolean isGenerationCurrent(int expectedGeneration) {
        return expectedGeneration == generation.get();
    }

    public boolean isCurrent(int expectedGeneration, @NonNull String expectedSignature) {
        synchronized (lock) {
            return expectedGeneration == generation.get() && expectedSignature.equals(signature);
        }
    }

    public void discardCurrent(int expectedGeneration, @NonNull String expectedSignature) {
        synchronized (lock) {
            if (expectedGeneration != generation.get() || !expectedSignature.equals(signature)) return;
            building = false;
            ready = false;
            anchors = new ArrayList<>();
            failed = false;
            failure = "";
        }
    }

    @NonNull
    public CommitResult completeCurrent(int expectedGeneration,
                                        @NonNull String expectedSignature,
                                        @Nullable ArrayList<CustomReaderView.PageTextAnchor> builtAnchors,
                                        boolean buildFailed,
                                        @Nullable String failureReason) {
        synchronized (lock) {
            if (expectedGeneration != generation.get() || !expectedSignature.equals(signature)) {
                return new CommitResult(false, false, 0);
            }
            building = false;
            if (builtAnchors == null || builtAnchors.isEmpty()) {
                ready = false;
                anchors = new ArrayList<>();
                failed = true;
                failure = buildFailed && failureReason != null && !failureReason.isEmpty()
                        ? failureReason
                        : "empty index";
                return new CommitResult(true, false, 0);
            }
            anchors = builtAnchors;
            ready = true;
            failed = false;
            failure = "";
            return new CommitResult(true, true, anchors.size());
        }
    }

    public boolean isReady() {
        synchronized (lock) {
            return ready && !anchors.isEmpty();
        }
    }

    public boolean isFailed() {
        synchronized (lock) {
            return failed;
        }
    }

    @NonNull
    public ArrayList<CustomReaderView.PageTextAnchor> copyAnchors() {
        synchronized (lock) {
            return new ArrayList<>(anchors);
        }
    }

    public int readyPageCount() {
        synchronized (lock) {
            return ready ? anchors.size() : 0;
        }
    }

    @Nullable
    public ArrayList<CustomReaderView.PageTextAnchor> copyAnchorsIfUsable(@NonNull String currentSignature) {
        if (currentSignature.isEmpty()) return null;
        synchronized (lock) {
            if (!ready || anchors.isEmpty() || !currentSignature.equals(signature)) return null;
            return new ArrayList<>(anchors);
        }
    }

    @NonNull
    public String failureReason() {
        synchronized (lock) {
            return failure;
        }
    }

    public static final class CommitResult {
        public final boolean current;
        public final boolean ready;
        public final int pageCount;

        private CommitResult(boolean current, boolean ready, int pageCount) {
            this.current = current;
            this.ready = ready;
            this.pageCount = pageCount;
        }
    }
}
