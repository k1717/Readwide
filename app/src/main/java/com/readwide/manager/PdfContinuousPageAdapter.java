package com.readwide.manager;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.util.LruCache;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

class PdfContinuousPageAdapter extends RecyclerView.Adapter<PdfContinuousPageAdapter.PageViewHolder> {
    private final PdfReaderActivity activity;
    private static final float DEFAULT_PDF_PAGE_RATIO = 1.4142f;
    private static final int PAGE_VERTICAL_GAP_DP = 10;

    private int count = 0;
    private int viewportWidth = 0;
    private float adapterZoom = 1.0f;
    private volatile int adapterGeneration = 0;
    private final SparseIntArray pageHeightCache = new SparseIntArray();
    private final SparseIntArray pagePanXCache = new SparseIntArray();
    private long[] pageHeightDeltaTree = new long[1];
    private long[] fastScrollHeightDeltaSnapshot;
    private final PdfContinuousRenderQueue renderQueue = new PdfContinuousRenderQueue();
    private final Set<PageViewHolder> boundHolders = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Bitmap, Integer> displayedBitmaps = new IdentityHashMap<>();
    private final Runnable renderPump = this::pumpRenderQueue;
    private boolean renderPumpPosted;
    private int readingDirection = 1;
    private int fallbackPage;
    private final int cacheMaxKb;
    private final LruCache<Integer, RenderedPage> bitmapCache;

    /** Keep display geometry with the bitmap, including cache hits and OOM retries. */
    private static final class RenderedPage {
        final Bitmap bitmap;
        final int displayWidth;
        final int displayHeight;

        RenderedPage(Bitmap bitmap, int displayWidth, int displayHeight) {
            this.bitmap = bitmap;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
        }
    }

    PdfContinuousPageAdapter(@NonNull PdfReaderActivity activity) {
        this.activity = activity;
        setHasStableIds(true);
        cacheMaxKb = activity.calculatePdfContinuousCacheKb();
        bitmapCache = new LruCache<Integer, RenderedPage>(cacheMaxKb) {
            @Override
            protected int sizeOf(Integer key, RenderedPage value) {
                return value == null ? 0 : bitmapSizeKb(value.bitmap);
            }

            @Override
            protected void entryRemoved(boolean evicted, Integer key, RenderedPage oldValue,
                                        RenderedPage newValue) {
                if (oldValue != null && (newValue == null || oldValue.bitmap != newValue.bitmap)) {
                    recycleIfNotDisplayed(oldValue.bitmap);
                }
            }
        };
    }

    void configure(int newCount, int newViewportWidth, float newZoom) {
        int clampedCount = Math.max(0, newCount);
        int clampedWidth = Math.max(1, newViewportWidth);
        float clampedZoom = Math.max(0.55f, Math.min(4.5f, newZoom));
        boolean changed = count != clampedCount
                || viewportWidth != clampedWidth
                || Math.abs(adapterZoom - clampedZoom) > 0.01f;
        count = clampedCount;
        viewportWidth = clampedWidth;
        adapterZoom = clampedZoom;
        if (changed) {
            adapterGeneration++;
            clearAllState();
            notifyDataSetChanged();
        }
        onViewportChanged(0);
    }

    void prefetchAround(int pageIndex) {
        fallbackPage = Math.max(0, Math.min(count - 1, pageIndex));
        onViewportChanged(0);
    }

    void clearBitmaps() {
        adapterGeneration++;
        clearBitmapAndRenderingState();
        if (!activity.activityDestroyed) notifyDataSetChanged();
    }

    void release() {
        adapterGeneration++;
        count = 0;
        clearAllState();
        for (PageViewHolder holder : boundHolders.toArray(new PageViewHolder[0])) {
            holder.clear();
        }
    }

    private void clearBitmapAndRenderingState() {
        activity.handler.removeCallbacks(renderPump);
        renderPumpPosted = false;
        renderQueue.cancelPending();
        bitmapCache.evictAll();
    }

    private void clearAllState() {
        clearBitmapAndRenderingState();
        pageHeightCache.clear();
        pagePanXCache.clear();
        pageHeightDeltaTree = new long[Math.max(1, count + 1)];
        fastScrollHeightDeltaSnapshot = null;
    }

    private boolean isBitmapStillCached(@NonNull Bitmap bitmap) {
        for (RenderedPage cached : bitmapCache.snapshot().values()) {
            if (cached.bitmap == bitmap) return true;
        }
        return false;
    }

    private void markBitmapDetached(Bitmap bitmap) {
        if (bitmap == null) return;
        Integer references = displayedBitmaps.get(bitmap);
        if (references != null && references > 1) displayedBitmaps.put(bitmap, references - 1);
        else displayedBitmaps.remove(bitmap);
        if (!isBitmapStillCached(bitmap)) recycleIfNotDisplayed(bitmap);
    }

    private void markBitmapDisplayed(Bitmap bitmap) {
        Integer references = displayedBitmaps.get(bitmap);
        displayedBitmaps.put(bitmap, references == null ? 1 : references + 1);
    }

    private void recycleIfNotDisplayed(Bitmap bitmap) {
        if (!displayedBitmaps.containsKey(bitmap) && !bitmap.isRecycled()) bitmap.recycle();
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ImageView image = new ImageView(parent.getContext());
        image.setAdjustViewBounds(false);
        image.setBackgroundColor(Color.WHITE);
        // Do not use FIT_CENTER here: when zoom > 1.0 the rendered bitmap is
        // intentionally wider/taller than the viewport. FIT_CENTER scales that
        // bitmap back down to the row width and makes vertical-mode zoom look
        // like it did not work. CENTER preserves the rendered zoom size.
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setContentDescription(activity.getString(R.string.pdf_page));
        image.setPadding(0, 0, 0, 0);

        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                estimatePageRowHeight());
        lp.setMargins(0, 0, 0, activity.dpToPx(PAGE_VERTICAL_GAP_DP));
        image.setLayoutParams(lp);
        return new PageViewHolder(image);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        holder.bind(position, adapterGeneration);
    }

    @Override
    public int getItemCount() {
        return count;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public void onViewRecycled(@NonNull PageViewHolder holder) {
        holder.clear();
        super.onViewRecycled(holder);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull PageViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        onViewportChanged(0);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull PageViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
        onViewportChanged(0);
    }

    private int estimatePageRowHeight() {
        int baseWidth = Math.max(1, viewportWidth - activity.dpToPx(24));
        int estimated = Math.round(baseWidth * DEFAULT_PDF_PAGE_RATIO * adapterZoom);
        // Pixel caps reduce only bitmap resolution. They must not shrink the
        // logical RecyclerView row, which represents the intended display size.
        return Math.max(activity.dpToPx(220), estimated);
    }

    int getRenderedHeightForPage(int pageIndex) {
        int cached = pageHeightCache.get(pageIndex, 0);
        return cached > 0 ? cached : estimatePageRowHeight();
    }

    private int estimatedHeightForPage(int pageIndex) {
        return getRenderedHeightForPage(pageIndex);
    }

    long getEstimatedScrollRangePx() {
        return estimatedContentHeightBefore(count, pageHeightDeltaTree);
    }

    long getEstimatedScrollOffsetPx(@NonNull LinearLayoutManager layoutManager,
                                    int viewportPaddingTop) {
        int first = layoutManager.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || first >= count) return 0L;
        View firstView = layoutManager.findViewByPosition(first);
        long offset = estimatedContentHeightBefore(first, pageHeightDeltaTree);
        if (firstView != null) {
            offset += (long) viewportPaddingTop - firstView.getTop();
        }
        long maxOffset = Math.max(0L, getEstimatedScrollRangePx() - 1L);
        return Math.max(0L, Math.min(maxOffset, offset));
    }

    void beginFastScroll() {
        fastScrollHeightDeltaSnapshot = pageHeightDeltaTree.clone();
    }

    void endFastScroll() {
        fastScrollHeightDeltaSnapshot = null;
    }

    void scrollToFraction(@NonNull LinearLayoutManager layoutManager,
                          float fraction,
                          int viewportExtent) {
        if (count <= 0) return;
        long[] heightDeltas = fastScrollHeightDeltaSnapshot != null
                ? fastScrollHeightDeltaSnapshot : pageHeightDeltaTree;
        long totalHeight = estimatedContentHeightBefore(count, heightDeltas);
        long maxOffset = Math.max(0L, totalHeight - Math.max(1, viewportExtent));
        float clamped = Math.max(0f, Math.min(1f, fraction));
        long targetOffset = Math.round(clamped * (double) maxOffset);

        int low = 0;
        int high = count - 1;
        while (low < high) {
            int middle = low + ((high - low + 1) >>> 1);
            if (estimatedContentHeightBefore(middle, heightDeltas) <= targetOffset) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        int targetPosition = low;
        long itemStart = estimatedContentHeightBefore(targetPosition, heightDeltas);
        int withinItem = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, targetOffset - itemStart));
        layoutManager.scrollToPositionWithOffset(targetPosition, -withinItem);
    }

    private long estimatedContentHeightBefore(int position, @NonNull long[] heightDeltas) {
        int boundedPosition = Math.max(0, Math.min(count, position));
        int defaultHeight = estimatePageRowHeight();
        int gap = activity.dpToPx(PAGE_VERTICAL_GAP_DP);
        long total = (long) boundedPosition * (defaultHeight + (long) gap);
        int treeIndex = Math.min(boundedPosition, heightDeltas.length - 1);
        while (treeIndex > 0) {
            total += heightDeltas[treeIndex];
            treeIndex -= treeIndex & -treeIndex;
        }
        return Math.max(0L, total);
    }

    private void rememberPageHeight(int pageIndex, int height) {
        if (pageIndex < 0 || height <= 0) return;
        int old = pageHeightCache.get(pageIndex, 0);
        if (Math.abs(old - height) > activity.dpToPx(2)) {
            pageHeightCache.put(pageIndex, height);
            int oldEffectiveHeight = old > 0 ? old : estimatePageRowHeight();
            long delta = height - (long) oldEffectiveHeight;
            for (int treeIndex = pageIndex + 1;
                 treeIndex < pageHeightDeltaTree.length;
                 treeIndex += treeIndex & -treeIndex) {
                pageHeightDeltaTree[treeIndex] += delta;
            }
        }
    }

    private int bitmapSizeKb(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return 0;
        return Math.max(1, bitmap.getByteCount() / 1024);
    }

    private boolean canCacheBitmap(Bitmap bitmap) {
        return bitmapSizeKb(bitmap) <= Math.max(1, cacheMaxKb);
    }

    private void deliverRenderedBitmap(int pageIndex, int generation, @NonNull RenderedPage rendered) {
        Bitmap bitmap = rendered.bitmap;
        if (bitmap.isRecycled()) return;
        rememberPageHeight(pageIndex, rendered.displayHeight);

        boolean applied = false;
        // Include RecyclerView's detached bound cache; it may reattach without a
        // new onBind callback. Reference counts also cover change-animation twins.
        for (PageViewHolder holder : boundHolders) {
            applied = holder.setBitmapIfStillBound(rendered, pageIndex, generation) || applied;
        }

        if (canCacheBitmap(bitmap)) {
            bitmapCache.put(pageIndex, rendered);
        } else if (!applied) {
            bitmap.recycle();
            return;
        }

        activity.schedulePdfFastScrollUpdate();
    }

    /** Called only on the main thread; workers never query RecyclerView state. */
    void onViewportChanged(int dy) {
        if (dy != 0) readingDirection = Integer.signum(dy);
        updateRenderWindow();
        // The posted pump also checks visible priority before retaining an active
        // speculative token. Wait until layout finishes before choosing a new one.
        scheduleRenderPump();
    }

    private boolean renderingEnabled() {
        return !activity.activityDestroyed && activity.verticalPageSlideMode
                && activity.pdfRenderer != null && count > 0
                && activity.pdfContinuousList != null
                && activity.pdfContinuousList.getAdapter() == this
                && activity.pdfContinuousList.getVisibility() == View.VISIBLE;
    }

    private void updateRenderWindow() {
        if (!renderingEnabled()) {
            renderQueue.cancelPending();
            return;
        }
        int first = RecyclerView.NO_POSITION;
        int last = RecyclerView.NO_POSITION;
        if (activity.pdfContinuousList != null) {
            RecyclerView.LayoutManager manager = activity.pdfContinuousList.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                first = ((LinearLayoutManager) manager).findFirstVisibleItemPosition();
                last = ((LinearLayoutManager) manager).findLastVisibleItemPosition();
            }
        }
        PageViewHolder middle = findBestVisibleHolder();
        int center = middle == null ? fallbackPage : middle.boundPage;
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            first = last = fallbackPage;
        }
        renderQueue.update(first, last, center, count, readingDirection, adapterGeneration);
    }

    private boolean hasRenderedPage(int pageIndex) {
        return findRenderedPage(pageIndex) != null;
    }

    private RenderedPage findRenderedPage(int pageIndex) {
        RenderedPage cached = bitmapCache.get(pageIndex);
        if (cached != null && !cached.bitmap.isRecycled()) return cached;
        for (PageViewHolder holder : boundHolders) {
            if (holder.boundPage == pageIndex && holder.boundGeneration == adapterGeneration
                    && holder.displayedPage != null && !holder.displayedBitmap.isRecycled()) {
                return holder.displayedPage;
            }
        }
        return null;
    }

    private void scheduleRenderPump() {
        if (renderPumpPosted || !renderingEnabled()) return;
        renderPumpPosted = true;
        activity.handler.post(renderPump);
    }

    private void pumpRenderQueue() {
        renderPumpPosted = false;
        updateRenderWindow();
        if (!renderingEnabled()) return;
        PdfContinuousRenderQueue.Request request = renderQueue.next(this::hasRenderedPage);
        if (request == null) return;
        long budget = PdfContinuousRenderQueue.pagePixelBudget(cacheMaxKb * 1024L,
                activity.getContinuousPageMaxPixels());
        renderContinuousPage(request, Math.max(1, viewportWidth), adapterZoom, budget, false);
    }

    private PageViewHolder findBestVisibleHolder() {
        if (activity.pdfContinuousList == null) return null;
        RecyclerView.LayoutManager manager = activity.pdfContinuousList.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) return null;
        LinearLayoutManager lm = (LinearLayoutManager) manager;
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return null;

        int viewportCenter = activity.pdfContinuousList.getHeight() / 2;
        PageViewHolder bestHolder = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = first; i <= last; i++) {
            View child = lm.findViewByPosition(i);
            RecyclerView.ViewHolder vh = activity.pdfContinuousList.findViewHolderForAdapterPosition(i);
            if (child == null || !(vh instanceof PageViewHolder)) continue;
            int distance = Math.abs(((child.getTop() + child.getBottom()) / 2) - viewportCenter);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestHolder = (PageViewHolder) vh;
            }
        }
        return bestHolder;
    }

    boolean canPanVisiblePageHorizontally() {
        PageViewHolder holder = findBestVisibleHolder();
        return holder != null && holder.canPanHorizontally();
    }

    boolean panVisiblePageHorizontally(float deltaX) {
        PageViewHolder holder = findBestVisibleHolder();
        return holder != null && holder.panHorizontally(deltaX);
    }

    int getVisiblePageHorizontalPanRange() {
        PageViewHolder holder = findBestVisibleHolder();
        return holder != null ? holder.getHorizontalPanRange() : 0;
    }

    int getVisiblePageHorizontalPanOffset() {
        PageViewHolder holder = findBestVisibleHolder();
        if (holder == null) return 0;
        int range = holder.getHorizontalPanRange();
        return range > 0 ? holder.getHorizontalPanOffset(range) : 0;
    }

    boolean setVisiblePageHorizontalPanOffset(int offset) {
        PageViewHolder holder = findBestVisibleHolder();
        return holder != null && holder.setHorizontalPanOffset(offset);
    }

    private PageViewHolder findHolderForPage(int pageIndex) {
        if (activity.pdfContinuousList == null) return null;
        RecyclerView.ViewHolder vh = activity.pdfContinuousList.findViewHolderForAdapterPosition(pageIndex);
        return vh instanceof PageViewHolder ? (PageViewHolder) vh : null;
    }

    int getRenderedWidthForPage(int pageIndex) {
        PageViewHolder holder = findHolderForPage(pageIndex);
        if (holder != null) return holder.getImageWidth();
        return Math.max(1, viewportWidth);
    }

    int getPageHorizontalPanOffset(int pageIndex) {
        PageViewHolder holder = findHolderForPage(pageIndex);
        if (holder != null) {
            int range = holder.getHorizontalPanRange();
            return range > 0 ? holder.getHorizontalPanOffset(range) : 0;
        }
        return Math.max(0, pagePanXCache.get(pageIndex, 0));
    }

    boolean setPageHorizontalPanOffset(int pageIndex, int offset) {
        int next = Math.max(0, offset);
        pagePanXCache.put(pageIndex, next);
        PageViewHolder holder = findHolderForPage(pageIndex);
        if (holder != null) return holder.setHorizontalPanOffset(next);
        return true;
    }

    class PageViewHolder extends RecyclerView.ViewHolder {
        private final ImageView image;
        private Bitmap displayedBitmap;
        private RenderedPage displayedPage;
        private int boundPage = RecyclerView.NO_POSITION;
        private int boundGeneration = -1;
        private int imageWidth = 0;

        PageViewHolder(@NonNull ImageView image) {
            super(image);
            this.image = image;
        }

        void bind(int pageIndex, int generation) {
            if (boundPage == pageIndex && boundGeneration == generation
                    && displayedPage != null && !displayedBitmap.isRecycled()) {
                setBitmapIfStillBound(displayedPage, pageIndex, generation);
                scheduleRenderPump();
                return;
            }
            clear();
            boundPage = pageIndex;
            boundGeneration = generation;
            boundHolders.add(this);
            image.setBackgroundColor(Color.WHITE);
            setRowHeight(estimatedHeightForPage(pageIndex));

            // An evicted bitmap can still belong to another bound holder. Reuse
            // it here; treating it as ready without binding it leaves a blank row.
            RenderedPage cached = findRenderedPage(pageIndex);
            if (cached != null && !cached.bitmap.isRecycled()) {
                setBitmapIfStillBound(cached, pageIndex, generation);
            } else {
                image.setImageDrawable(null);
            }
            scheduleRenderPump();
        }

        boolean setBitmapIfStillBound(RenderedPage rendered, int pageIndex, int generation) {
            if (boundPage != pageIndex || boundGeneration != generation || activity.activityDestroyed) {
                return false;
            }
            Bitmap nextBitmap = rendered.bitmap;
            if (nextBitmap == null || nextBitmap.isRecycled()) {
                image.setImageDrawable(null);
                return false;
            }
            if (displayedBitmap != nextBitmap) {
                image.setImageDrawable(null);
                markBitmapDetached(displayedBitmap);
                displayedBitmap = nextBitmap;
                markBitmapDisplayed(nextBitmap);
            }
            displayedPage = rendered;
            // Resolution caps and reduced OOM retries affect detail, not page
            // size. Never infer the logical frame by dividing bitmap dimensions.
            setImageFrame(rendered.displayWidth, rendered.displayHeight);
            image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(nextBitmap);
            applyHorizontalPan();
            return true;
        }

        void setRowHeight(int height) {
            setImageFrame(Math.max(1, viewportWidth), height);
        }

        private void setImageFrame(int width, int height) {
            ViewGroup.LayoutParams lp = image.getLayoutParams();
            if (lp == null) return;
            int nextWidth = Math.max(Math.max(1, viewportWidth), width);
            int nextHeight = Math.max(activity.dpToPx(180), height);
            imageWidth = nextWidth;
            if (lp.width != nextWidth || lp.height != nextHeight) {
                lp.width = nextWidth;
                lp.height = nextHeight;
                image.setLayoutParams(lp);
            }
        }

        int getImageWidth() {
            return Math.max(1, imageWidth);
        }

        boolean canPanHorizontally() {
            return getHorizontalPanRange() > 0;
        }

        boolean panHorizontally(float deltaX) {
            int range = getHorizontalPanRange();
            if (range <= 0 || boundPage == RecyclerView.NO_POSITION) return false;
            int current = getHorizontalPanOffset(range);
            int next = Math.max(0, Math.min(range, current + Math.round(deltaX)));
            pagePanXCache.put(boundPage, next);
            applyHorizontalPan();
            return next != current;
        }

        int getHorizontalPanRange() {
            if (activity.pdfContinuousList == null) return 0;
            int viewport = Math.max(1, activity.pdfContinuousList.getWidth());
            return Math.max(0, imageWidth - viewport);
        }

        private boolean setHorizontalPanOffset(int offset) {
            int range = getHorizontalPanRange();
            if (range <= 0 || boundPage == RecyclerView.NO_POSITION) return false;
            int next = Math.max(0, Math.min(range, offset));
            int current = getHorizontalPanOffset(range);
            pagePanXCache.put(boundPage, next);
            applyHorizontalPan();
            return next != current;
        }

        int getHorizontalPanOffset(int range) {
            if (boundPage == RecyclerView.NO_POSITION) return 0;
            int stored = pagePanXCache.get(boundPage, Integer.MIN_VALUE);
            if (stored == Integer.MIN_VALUE) {
                stored = range / 2;
                pagePanXCache.put(boundPage, stored);
            }
            return Math.max(0, Math.min(range, stored));
        }

        private void applyHorizontalPan() {
            int range = getHorizontalPanRange();
            int offset = range > 0 ? getHorizontalPanOffset(range) : 0;
            image.setTranslationX(-offset);
        }

        void clear() {
            boundHolders.remove(this);
            image.setImageDrawable(null);
            image.setTranslationX(0f);
            imageWidth = 0;
            markBitmapDetached(displayedBitmap);
            displayedBitmap = null;
            displayedPage = null;
            boundPage = RecyclerView.NO_POSITION;
            boundGeneration = -1;
        }
    }

    private void renderContinuousPage(
            PdfContinuousRenderQueue.Request request,
            int widthForRender,
            float zoomForRender,
            long maxBitmapPixels,
            boolean reducedOomRetry
    ) {
        final int pageToRender = request.page;
        final int minimumRowHeight = activity.dpToPx(180);
        final int horizontalReserve = activity.dpToPx(24);
        Runnable render = () -> {
            Bitmap bitmap = null;
            PdfPageRenderPlan.Plan plan = null;
            boolean outOfMemory = false;
            try {
                if (isObsoleteRender(request)) return;
                synchronized (activity.rendererLock) {
                    if (isObsoleteRender(request)) return;
                    if (activity.activityDestroyed || activity.pdfRenderer == null || pageToRender >= activity.pageCount) {
                        throw new IllegalStateException("PDF renderer is closed");
                    }
                    PdfRenderer.Page page = activity.pdfRenderer.openPage(pageToRender);
                    try {
                        plan = PdfPageRenderPlan.create(
                                page.getWidth(),
                                page.getHeight(),
                                widthForRender,
                                1,
                                zoomForRender,
                                PdfReaderActivity.PDF_SUPERSAMPLE,
                                horizontalReserve,
                                0,
                                false,
                                Math.max(1L, maxBitmapPixels));
                        int width = plan.bitmapWidthPx;
                        int height = plan.bitmapHeightPx;

                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        page.close();
                    }
                }
            } catch (OutOfMemoryError oom) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                bitmap = null;
                outOfMemory = true;
            } catch (Exception e) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                bitmap = null;
            } finally {
                final Bitmap result = bitmap;
                final PdfPageRenderPlan.Plan resultPlan = plan;
                final boolean retryOom = outOfMemory;
                activity.handler.post(() -> {
                    updateRenderWindow();
                    if (isObsoleteRender(request) || !renderingEnabled()
                            || !renderQueue.wants(pageToRender)) {
                        if (result != null && !result.isRecycled()) result.recycle();
                        renderQueue.finish(request, false);
                        scheduleRenderPump();
                        return;
                    }
                    if (retryOom) {
                        bitmapCache.evictAll();
                        if (!reducedOomRetry && maxBitmapPixels > 1L) {
                            // One smaller attempt, same token and display geometry.
                            renderContinuousPage(request, widthForRender, zoomForRender,
                                    Math.max(1L, maxBitmapPixels / 2L), true);
                            return;
                        }
                    }
                    renderQueue.finish(request, true);
                    if (result != null && !result.isRecycled() && resultPlan != null) {
                        deliverRenderedBitmap(pageToRender, request.generation,
                                new RenderedPage(result, resultPlan.intendedDisplayWidthPx,
                                        Math.max(minimumRowHeight, resultPlan.intendedDisplayHeightPx)));
                        if (pageToRender == activity.currentPage) activity.updatePageStatus();
                    }
                    scheduleRenderPump();
                });
            }
        };
        try {
            activity.executor.execute(render);
        } catch (RejectedExecutionException closedExecutor) {
            renderQueue.finish(request, true);
            // Destruction may shut down the executor between a posted pump and
            // submission. Do not leave a permanently owned token or retry loop.
        }
    }

    /** Worker-safe; all three fields are volatile and no View/cache is read. */
    private boolean isObsoleteRender(PdfContinuousRenderQueue.Request request) {
        return activity.activityDestroyed || request.cancelled
                || request.generation != adapterGeneration;
    }
}
