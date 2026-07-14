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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

class PdfContinuousPageAdapter extends RecyclerView.Adapter<PdfContinuousPageAdapter.PageViewHolder> {
    private final PdfReaderActivity activity;
    private static final float DEFAULT_PDF_PAGE_RATIO = 1.4142f;
    private static final int PAGE_VERTICAL_GAP_DP = 10;

    private int count = 0;
    private int viewportWidth = 0;
    private float adapterZoom = 1.0f;
    private int adapterGeneration = 0;
    private final SparseIntArray pageHeightCache = new SparseIntArray();
    private final SparseIntArray pagePanXCache = new SparseIntArray();
    private long[] pageHeightDeltaTree = new long[1];
    private long[] fastScrollHeightDeltaSnapshot;
    private final Set<String> pagesRendering = new HashSet<>();
    private final Set<Bitmap> displayedBitmaps = Collections.newSetFromMap(new IdentityHashMap<>());
    private final int cacheMaxKb;
    private final LruCache<Integer, Bitmap> bitmapCache;

    PdfContinuousPageAdapter(@NonNull PdfReaderActivity activity) {
        this.activity = activity;
        setHasStableIds(true);
        cacheMaxKb = activity.calculatePdfContinuousCacheKb();
        bitmapCache = new LruCache<Integer, Bitmap>(cacheMaxKb) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                if (value == null || value.isRecycled()) return 0;
                return Math.max(1, value.getByteCount() / 1024);
            }

            @Override
            protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue, Bitmap newValue) {
                if (oldValue != null && oldValue != newValue && !oldValue.isRecycled()) {
                    if (displayedBitmaps.contains(oldValue)) {
                        return;
                    }
                    oldValue.recycle();
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
    }

    void prefetchPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= count) return;
        if (bitmapCache.get(pageIndex) != null) return;
        startRender(pageIndex, null, adapterGeneration);
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
    }

    private void clearBitmapAndRenderingState() {
        synchronized (pagesRendering) {
            pagesRendering.clear();
        }
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
        for (Bitmap cached : bitmapCache.snapshot().values()) {
            if (cached == bitmap) return true;
        }
        return false;
    }

    private void markBitmapDetached(Bitmap bitmap) {
        if (bitmap == null) return;
        displayedBitmaps.remove(bitmap);
        if (!isBitmapStillCached(bitmap) && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
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

    private void deliverRenderedBitmap(int pageIndex, int generation, int renderedHeight,
                                       @NonNull Bitmap bitmap, PageViewHolder originalHolder) {
        if (bitmap.isRecycled()) return;
        rememberPageHeight(pageIndex, renderedHeight);

        boolean applied = false;
        if (originalHolder != null) {
            applied = originalHolder.setBitmapIfStillBound(bitmap, pageIndex, generation);
        }

        if (activity.pdfContinuousList != null) {
            RecyclerView.ViewHolder visibleHolder = activity.pdfContinuousList.findViewHolderForAdapterPosition(pageIndex);
            if (visibleHolder instanceof PageViewHolder && visibleHolder != originalHolder) {
                applied = ((PageViewHolder) visibleHolder).setBitmapIfStillBound(bitmap, pageIndex, generation) || applied;
            }
        }

        if (canCacheBitmap(bitmap)) {
            bitmapCache.put(pageIndex, bitmap);
        } else if (!applied) {
            bitmap.recycle();
            return;
        }

        if (!applied) {
            notifyItemChanged(pageIndex);
        }
        activity.schedulePdfFastScrollUpdate();
    }

    private String renderKeyFor(int pageIndex, int generation) {
        return generation + ":" + pageIndex + ":" + viewportWidth + ":" + Math.round(adapterZoom * 100f);
    }

    private void startRender(int pageIndex, PageViewHolder holder, int generation) {
        if (activity.pdfRenderer == null || pageIndex < 0 || pageIndex >= activity.pageCount) return;
        String key = renderKeyFor(pageIndex, generation);
        synchronized (pagesRendering) {
            if (pagesRendering.contains(key)) return;
            pagesRendering.add(key);
        }
        renderContinuousPageIntoHolder(holder, pageIndex, generation, key,
                Math.max(1, viewportWidth), adapterZoom,
                activity.getContinuousPageMaxPixels(), false);
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
        private int boundPage = RecyclerView.NO_POSITION;
        private int boundGeneration = -1;
        private int imageWidth = 0;

        PageViewHolder(@NonNull ImageView image) {
            super(image);
            this.image = image;
        }

        void bind(int pageIndex, int generation) {
            clear();
            boundPage = pageIndex;
            boundGeneration = generation;
            image.setBackgroundColor(Color.WHITE);
            setRowHeight(estimatedHeightForPage(pageIndex));

            Bitmap cached = bitmapCache.get(pageIndex);
            if (cached != null && !cached.isRecycled()) {
                setBitmapIfStillBound(cached, pageIndex, generation);
                return;
            }

            image.setImageDrawable(null);
            startRender(pageIndex, this, generation);
        }

        boolean setBitmapIfStillBound(Bitmap nextBitmap, int pageIndex, int generation) {
            if (boundPage != pageIndex || boundGeneration != generation || activity.activityDestroyed) {
                return false;
            }
            if (nextBitmap == null || nextBitmap.isRecycled()) {
                image.setImageDrawable(null);
                return false;
            }
            if (displayedBitmap != nextBitmap) {
                markBitmapDetached(displayedBitmap);
                displayedBitmap = nextBitmap;
                displayedBitmaps.add(nextBitmap);
            }
            // Bitmap is supersampled; frame it at fit size so the extra pixels
            // are detail (sharper text), not an enlarged page.
            float ss = PdfReaderActivity.PDF_SUPERSAMPLE;
            int fitW = Math.max(1, Math.round(nextBitmap.getWidth() / ss));
            int fitH = Math.max(1, Math.round(nextBitmap.getHeight() / ss));
            setImageFrame(fitW, fitH);
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
            image.setImageDrawable(null);
            image.setTranslationX(0f);
            imageWidth = 0;
            markBitmapDetached(displayedBitmap);
            displayedBitmap = null;
            boundPage = RecyclerView.NO_POSITION;
            boundGeneration = -1;
        }
    }

    private void renderContinuousPageIntoHolder(
            PdfContinuousPageAdapter.PageViewHolder holder,
            int pageIndex,
            int generation,
            @NonNull String renderKey,
            int widthForRender,
            float zoomForRender,
            long maxBitmapPixels,
            boolean reducedOomRetry
    ) {
        final int pageToRender = pageIndex;

        activity.executor.execute(() -> {
            Bitmap bitmap = null;
            int intendedDisplayHeight = 0;
            try {
                synchronized (activity.rendererLock) {
                    if (activity.activityDestroyed || activity.pdfRenderer == null || pageToRender >= activity.pageCount) {
                        throw new IllegalStateException("PDF renderer is closed");
                    }
                    PdfRenderer.Page page = activity.pdfRenderer.openPage(pageToRender);
                    try {
                        PdfPageRenderPlan.Plan plan = PdfPageRenderPlan.create(
                                page.getWidth(),
                                page.getHeight(),
                                widthForRender,
                                1,
                                zoomForRender,
                                PdfReaderActivity.PDF_SUPERSAMPLE,
                                activity.dpToPx(24),
                                0,
                                false,
                                Math.max(1L, maxBitmapPixels));
                        intendedDisplayHeight = plan.intendedDisplayHeightPx;
                        int width = plan.bitmapWidthPx;
                        int height = plan.bitmapHeightPx;

                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        page.close();
                    }
                }

                Bitmap finalBitmap = bitmap;
                // Keep layout at the intended fit/zoom height even when the pixel
                // cap reduces the backing bitmap. Deriving row height from the
                // capped bitmap made very tall pages visibly shrink.
                int finalRenderedHeight = Math.max(1, intendedDisplayHeight);
                activity.handler.post(() -> {
                    synchronized (pagesRendering) {
                        pagesRendering.remove(renderKey);
                    }
                    if (activity.activityDestroyed || generation != adapterGeneration) {
                        if (finalBitmap != null && !finalBitmap.isRecycled()) finalBitmap.recycle();
                        return;
                    }
                    if (finalBitmap == null || finalBitmap.isRecycled()) return;
                    deliverRenderedBitmap(pageToRender, generation,
                            finalRenderedHeight, finalBitmap, holder);
                    if (activity.verticalPageSlideMode && pageToRender == activity.currentPage) {
                        activity.updatePageStatus();
                    }
                });
            } catch (OutOfMemoryError oom) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                activity.handler.post(() -> {
                    synchronized (pagesRendering) {
                        pagesRendering.remove(renderKey);
                    }
                    if (activity.activityDestroyed
                            || generation != adapterGeneration
                            || !activity.verticalPageSlideMode) {
                        return;
                    }
                    // Release cached, non-visible bitmaps without invalidating
                    // geometry or rebinding the whole dataset.
                    bitmapCache.evictAll();
                    if (!reducedOomRetry) {
                        boolean accepted;
                        synchronized (pagesRendering) {
                            accepted = pagesRendering.add(renderKey);
                        }
                        if (accepted) {
                            renderContinuousPageIntoHolder(
                                    holder,
                                    pageToRender,
                                    generation,
                                    renderKey,
                                    widthForRender,
                                    zoomForRender,
                                    Math.max(1L, maxBitmapPixels / 2L),
                                    true);
                        }
                    }
                });
            } catch (Exception e) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                activity.handler.post(() -> {
                    synchronized (pagesRendering) {
                        pagesRendering.remove(renderKey);
                    }
                });
            }
        });
    }
}
