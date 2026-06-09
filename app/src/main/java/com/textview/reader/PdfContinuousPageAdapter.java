package com.textview.reader;

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
            clearCacheAndRenderingState();
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
        clearCacheAndRenderingState();
        if (!activity.activityDestroyed) notifyDataSetChanged();
    }

    void release() {
        adapterGeneration++;
        count = 0;
        clearCacheAndRenderingState();
    }

    private void clearCacheAndRenderingState() {
        synchronized (pagesRendering) {
            pagesRendering.clear();
        }
        pageHeightCache.clear();
        pagePanXCache.clear();
        bitmapCache.evictAll();
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
        int cached = pageHeightCache.get(Math.max(0, activity.currentPage), 0);
        if (cached > 0) return cached;
        int baseWidth = Math.max(1, viewportWidth - activity.dpToPx(24));
        int estimated = Math.round(baseWidth * DEFAULT_PDF_PAGE_RATIO * adapterZoom);
        long pixels = (long) baseWidth * (long) estimated;
        if (pixels > activity.getContinuousPageMaxPixels()) {
            float shrink = (float) Math.sqrt(activity.getContinuousPageMaxPixels() / (double) pixels);
            estimated = Math.max(1, Math.round(estimated * shrink));
        }
        return Math.max(activity.dpToPx(220), estimated);
    }

    private int estimatedHeightForPage(int pageIndex) {
        int cached = pageHeightCache.get(pageIndex, 0);
        return cached > 0 ? cached : estimatePageRowHeight();
    }

    private void rememberPageHeight(int pageIndex, int height) {
        if (pageIndex < 0 || height <= 0) return;
        int old = pageHeightCache.get(pageIndex, 0);
        if (Math.abs(old - height) > activity.dpToPx(2)) {
            pageHeightCache.put(pageIndex, height);
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
                Math.max(1, viewportWidth), adapterZoom);
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
            setImageFrame(nextBitmap.getWidth(), nextBitmap.getHeight());
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

        private int getHorizontalPanRange() {
            if (activity.pdfContinuousList == null) return 0;
            int viewport = Math.max(1, activity.pdfContinuousList.getWidth());
            return Math.max(0, imageWidth - viewport);
        }

        private int getHorizontalPanOffset(int range) {
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
            float zoomForRender
    ) {
        final int pageToRender = pageIndex;

        activity.executor.execute(() -> {
            Bitmap bitmap = null;
            int renderedHeight = 0;
            try {
                synchronized (activity.rendererLock) {
                    if (activity.activityDestroyed || activity.pdfRenderer == null || pageToRender >= activity.pageCount) {
                        throw new IllegalStateException("PDF renderer is closed");
                    }
                    PdfRenderer.Page page = activity.pdfRenderer.openPage(pageToRender);
                    try {
                        int baseWidth = Math.max(1, widthForRender - activity.dpToPx(24));
                        float fitScale = baseWidth / (float) page.getWidth();
                        float renderScale = Math.max(0.2f, fitScale * zoomForRender);
                        int width = Math.max(1, Math.round(page.getWidth() * renderScale));
                        int height = Math.max(1, Math.round(page.getHeight() * renderScale));

                        long pixels = (long) width * (long) height;
                        long maxPixels = activity.getContinuousPageMaxPixels();
                        if (pixels > maxPixels) {
                            float shrink = (float) Math.sqrt(maxPixels / (double) pixels);
                            width = Math.max(1, Math.round(width * shrink));
                            height = Math.max(1, Math.round(height * shrink));
                        }

                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        renderedHeight = height;
                    } finally {
                        page.close();
                    }
                }

                Bitmap finalBitmap = bitmap;
                int finalRenderedHeight = renderedHeight;
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

