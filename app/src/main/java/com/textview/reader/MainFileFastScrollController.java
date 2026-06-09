package com.textview.reader;

import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

final class MainFileFastScrollController {
    private static final int MIN_FAST_SCROLL_ITEMS = 40;

    private final RecyclerView recyclerView;
    private final RecyclerView.Adapter<?> adapter;
    private final View rail;
    private final View thumb;
    private boolean dragging;

    MainFileFastScrollController(@NonNull RecyclerView recyclerView,
                                 @NonNull RecyclerView.Adapter<?> adapter,
                                 @NonNull View rail,
                                 @NonNull View thumb) {
        this.recyclerView = recyclerView;
        this.adapter = adapter;
        this.rail = rail;
        this.thumb = thumb;
    }

    void install() {
        rail.bringToFront();
        rail.setOnTouchListener(this::onRailTouch);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateThumbFromRecycler();
            }
        });
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { scheduleThumbUpdate(); }
            @Override public void onItemRangeInserted(int positionStart, int itemCount) { scheduleThumbUpdate(); }
            @Override public void onItemRangeRemoved(int positionStart, int itemCount) { scheduleThumbUpdate(); }
            @Override public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) { scheduleThumbUpdate(); }
            @Override public void onItemRangeChanged(int positionStart, int itemCount) { scheduleThumbUpdate(); }
            @Override public void onItemRangeChanged(int positionStart, int itemCount, Object payload) { scheduleThumbUpdate(); }
        });
        scheduleThumbUpdate();
    }

    private boolean onRailTouch(View view, MotionEvent event) {
        int count = adapter.getItemCount();
        if (count < MIN_FAST_SCROLL_ITEMS) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                view.getParent().requestDisallowInterceptTouchEvent(true);
                setThumbPressed(true);
                scrollToTouchY(event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    scrollToTouchY(event.getY());
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    setThumbPressed(false);
                    updateThumbFromRecycler();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void scrollToTouchY(float y) {
        RecyclerView.LayoutManager rawManager = recyclerView.getLayoutManager();
        if (!(rawManager instanceof LinearLayoutManager)) return;

        int count = adapter.getItemCount();
        if (count <= 1) return;

        int railHeight = rail.getHeight();
        int thumbHeight = Math.max(1, thumb.getHeight());
        int travel = Math.max(1, railHeight - thumbHeight);
        float clampedTop = Math.max(0f, Math.min(travel, y - (thumbHeight / 2f)));
        float ratio = clampedTop / travel;
        int target = Math.max(0, Math.min(count - 1, Math.round(ratio * (count - 1))));
        ((LinearLayoutManager) rawManager).scrollToPositionWithOffset(target, 0);
        moveThumbTo(clampedTop);
    }

    private void scheduleThumbUpdate() {
        rail.post(this::updateThumbFromRecycler);
    }

    private void updateThumbFromRecycler() {
        if (dragging) return;

        int count = adapter.getItemCount();
        int range = recyclerView.computeVerticalScrollRange();
        int extent = recyclerView.computeVerticalScrollExtent();
        boolean visible = count >= MIN_FAST_SCROLL_ITEMS && range > extent;
        rail.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;

        int travel = Math.max(1, rail.getHeight() - thumb.getHeight());
        int offset = recyclerView.computeVerticalScrollOffset();
        int maxOffset = Math.max(1, range - extent);
        moveThumbTo((offset / (float) maxOffset) * travel);
    }

    private void moveThumbTo(float top) {
        thumb.setTranslationY(top);
    }

    private void setThumbPressed(boolean pressed) {
        rail.setPressed(pressed);
        thumb.setPressed(pressed);
    }
}
