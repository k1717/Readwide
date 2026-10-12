package com.readwide.manager.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.view.CustomReaderView;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class LargeTextExactPageIndexState {
    private final Object lock = new Object();
    private final AtomicInteger generation = new AtomicInteger(0);
    private ArrayList<CustomReaderView.PageTextAnchor> anchors = new ArrayList<>();
    private boolean ready;
    private boolean building;
    // A fresh load checks saved anchors before deciding whether a recount is needed.
    private boolean checkingCache = true;
    private boolean failed;
    private String failure = "";
    private String signature = "";

    public void invalidate() {
        synchronized (lock) {
            generation.incrementAndGet();
            // A cancelled worker cannot block a later same-signature restart.
            building = false;
            checkingCache = false;
        }
    }

    public void reset() {
        synchronized (lock) {
            generation.incrementAndGet();
            anchors = new ArrayList<>();
            ready = false;
            building = false;
            checkingCache = true;
            failed = false;
            failure = "";
            signature = "";
        }
    }

    /**
     * Retire a different page model before the debounced replacement starts.
     * Matching layout notifications keep both ready anchors and in-flight cache
     * validation. An uninitialized state has no old model to retire.
     */
    public boolean invalidateIfSignatureChanged(@NonNull String currentSignature) {
        synchronized (lock) {
            if (signature.isEmpty() || currentSignature.equals(signature)) return false;
            generation.incrementAndGet();
            anchors = new ArrayList<>();
            ready = false;
            building = false;
            checkingCache = false;
            failed = false;
            failure = "";
            signature = currentSignature;
            return true;
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
            checkingCache = true;
            failed = false;
            failure = "";
            signature = nextSignature;
            return generation.incrementAndGet();
        }
    }

    public boolean isGenerationCurrent(int expectedGeneration) {
        return expectedGeneration == generation.get();
    }

    public boolean isCheckingCache() {
        synchronized (lock) {
            return checkingCache && !ready && !failed;
        }
    }

    /** Only the current worker may turn cache validation into a genuine recount. */
    public boolean beginCalculation(int expectedGeneration, @NonNull String expectedSignature) {
        synchronized (lock) {
            if (!building || expectedGeneration != generation.get() || !expectedSignature.equals(signature)) return false;
            checkingCache = false;
            return true;
        }
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
            checkingCache = false;
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
            checkingCache = false;
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

    public int findPageForChar(int position) {
        synchronized (lock) {
            return LargeTextAnchorMath.findExactPageForChar(anchors, position);
        }
    }

    /** Read-only lookup: no anchor copy, no guessed page from a previous layout. */
    public int[] pageForCharIfUsable(@NonNull String currentSignature, int position) {
        synchronized (lock) {
            if (currentSignature.isEmpty() || !ready || anchors.isEmpty()
                    || !currentSignature.equals(signature)) return new int[] { 0, 0 };
            return new int[] { LargeTextAnchorMath.findExactPageForChar(anchors, position), anchors.size() };
        }
    }

    @Nullable public CustomReaderView.PageTextAnchor anchorForPage(int page) {
        synchronized (lock) {
            if (anchors.isEmpty()) return null;
            return anchors.get(Math.max(0, Math.min(anchors.size() - 1, page - 1)));
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

    /** Select one destination under the lock instead of copying every page per tap. */
    @Nullable
    public TapTarget tapTargetIfUsable(@NonNull String currentSignature, int currentAbs, int direction) {
        synchronized (lock) {
            if (currentSignature.isEmpty() || !ready || anchors.isEmpty()
                    || !currentSignature.equals(signature)) return null;
            int index = LargeTextAnchorMath.findTapTargetAnchorIndex(anchors, currentAbs, direction);
            if (index < 0) return new TapTarget(0, anchors.size(), null);
            // Keep the existing queued-delta arithmetic and final-page behavior.
            if (Math.abs(direction) > 1) {
                int extraSteps = Math.abs(direction) - 1;
                index += direction > 0 ? extraSteps : -extraSteps;
                index = Math.max(0, Math.min(anchors.size() - 1, index));
            }
            return new TapTarget(index + 1, anchors.size(), anchors.get(index));
        }
    }

    public static final class TapTarget {
        public final int page;
        public final int total;
        @Nullable public final CustomReaderView.PageTextAnchor anchor;

        private TapTarget(int page, int total, @Nullable CustomReaderView.PageTextAnchor anchor) {
            this.page = page;
            this.total = total;
            this.anchor = anchor;
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
