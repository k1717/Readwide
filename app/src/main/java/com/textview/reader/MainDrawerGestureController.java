package com.textview.reader;

import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.reflect.Method;

final class MainDrawerGestureController {
    private final MainActivity activity;

    MainDrawerGestureController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    boolean handleProportionalDrawerEdgeDrag(@NonNull MotionEvent event) {
        if (activity.drawerLayout == null) {
            resetDrawerSwipeState();
            return false;
        }

        View drawerView = activity.findViewById(R.id.nav_drawer);
        if (drawerView == null || drawerView.getWidth() <= 0) {
            return false;
        }

        if (!activity.drawerDismissTracking
                && event.getActionMasked() == MotionEvent.ACTION_DOWN
                && isDrawerDismissible(drawerView)
                && isTouchInsideView(drawerView, event)) {
            resetDrawerSwipeState();
            return false;
        }

        if (shouldRouteToDrawerDismiss(drawerView, event)) {
            return handleOpenDrawerDismissGesture(drawerView, event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activity.drawerSwipeStartX = event.getX();
                activity.drawerSwipeStartY = event.getY();
                activity.drawerSwipeOpened = false;
                activity.drawerManualDragging = false;
                // No edge-only border: drawer gestures may start anywhere on the main screen,
                // except controls that need horizontal/typing gestures themselves.
                activity.drawerSwipeTracking = !isTouchInsideMainBottomControls(event);
                return false;

            case MotionEvent.ACTION_MOVE:
                if (!activity.drawerSwipeTracking) return false;

                float dx = event.getX() - activity.drawerSwipeStartX;
                float dy = event.getY() - activity.drawerSwipeStartY;
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);

                if (!activity.drawerManualDragging) {
                    if (absDy > activity.drawerSwipeTouchSlop * 2.4f && absDy > absDx * 1.30f) {
                        resetDrawerSwipeState();
                        return false;
                    }
                    float startThreshold = Math.max(activity.dpToPx(2), activity.drawerSwipeTouchSlop * 0.45f);
                    if (dx <= startThreshold || absDx <= absDy * 1.00f) {
                        return false;
                    }
                    activity.drawerManualDragging = true;
                    cancelMainListPendingPresses();
                    activity.drawerForceSettling = false;
                    activity.drawerClosePartialOnRelease = false;
                }

                final float dragGain = 1.18f;
                float offset = Math.max(0f, Math.min(1f,
                        (dx * dragGain) / Math.max(1f, drawerView.getWidth())));
                applyDrawerManualOffset(drawerView, offset);
                return true;

            case MotionEvent.ACTION_UP:
                if (!activity.drawerManualDragging) {
                    resetDrawerSwipeState();
                    return false;
                }

                boolean fullyPulled = activity.drawerSlideOffset >= 0.30f;
                resetDrawerSwipeState();
                if (fullyPulled) {
                    openDrawerReliably(drawerView);
                } else {
                    forceDrawerClosedVisualState(drawerView);
                    activity.drawerLayout.closeDrawer(GravityCompat.START);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (activity.drawerManualDragging) {
                    forceDrawerClosedVisualState(drawerView);
                    activity.drawerLayout.closeDrawer(GravityCompat.START);
                    resetDrawerSwipeState();
                    return true;
                }
                resetDrawerSwipeState();
                return false;

            default:
                return false;
        }
    }


    private boolean shouldRouteToDrawerDismiss(@NonNull View drawerView, @NonNull MotionEvent event) {
        if (activity.drawerDismissTracking) return true;
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        if (!isDrawerDismissible(drawerView)) return false;
        if (isTouchInsideView(drawerView, event)) {
            resetDrawerSwipeState();
            return false;
        }
        return true;
    }

    private boolean isDrawerDismissible(@NonNull View drawerView) {
        if (activity.drawerLayout == null) return false;
        if (activity.drawerLayout.isDrawerOpen(GravityCompat.START)) return true;

        // Only treat a partial drawer as dismissible when our tracked offset says
        // it is materially on-screen. Do not use isDrawerVisible() or raw view
        // coordinates here: DrawerLayout can report/lay out the drawer in ways
        // that look visible even when the user is starting a normal right-swipe
        // open gesture from the closed state.
        return drawerView.getVisibility() == View.VISIBLE && activity.drawerSlideOffset >= 0.15f;
    }

    private boolean isTouchInsideMainBottomControls(@NonNull MotionEvent event) {
        return isTouchInsideView(activity.fileSearchBar, event)
                || isTouchInsideView(activity.fileTypeFilterScroll, event)
                || isTouchInsideView(activity.filterAllChip, event)
                || isTouchInsideView(activity.filterGeneralChip, event)
                || isTouchInsideView(activity.filterTxtChip, event)
                || isTouchInsideView(activity.filterArchiveChip, event)
                || isTouchInsideView(activity.filterPdfChip, event)
                || isTouchInsideView(activity.filterEpubChip, event)
                || isTouchInsideView(activity.filterWordChip, event)
                || isTouchInsideView(activity.filterImageChip, event)
                || isTouchInsideView(activity.fileSearchInput, event)
                || isTouchInsideView(activity.fileSearchScopeButton, event)
                || isTouchInsideView(activity.fileSearchClearButton, event)
                || isTouchInsideView(activity.fileSortButton, event);
    }

    private boolean handleOpenDrawerDismissGesture(@NonNull View drawerView, @NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activity.drawerSwipeStartX = event.getX();
                activity.drawerSwipeStartY = event.getY();
                activity.drawerSwipeTracking = true;
                activity.drawerDismissTracking = true;
                activity.drawerManualDragging = false;
                activity.drawerSwipeOpened = false;
                // Take over from any in-progress DrawerLayout settling so the
                // finger starts tracking from the drawer's current position
                // instead of fighting an automatic animation.
                activity.drawerForceSettling = true;
                // Capture the offset the drawer is sitting at when the gesture
                // starts (normally fully open = 1f) so the drag is measured
                // relative to it. This makes a left-slide subtract distance
                // proportionally from wherever the drawer currently is.
                activity.drawerDismissStartOffset =
                        activity.drawerSlideOffset > 0f ? activity.drawerSlideOffset : 1f;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!activity.drawerDismissTracking || !activity.drawerSwipeTracking) return false;

                float dx = event.getX() - activity.drawerSwipeStartX;
                float dy = event.getY() - activity.drawerSwipeStartY;
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);

                if (!activity.drawerManualDragging) {
                    // Ignore mostly-vertical noise so list scrolling near an open
                    // drawer does not start a dismiss, but keep consuming the event
                    // (return true) so the underlying list gets no stray input.
                    if (absDy > activity.drawerSwipeTouchSlop * 2.2f && absDy > absDx * 1.25f) {
                        return true;
                    }
                    // Begin proportional dragging once there is a clear leftward
                    // intent past the touch slop.
                    float startThreshold = Math.max(activity.dpToPx(2), activity.drawerSwipeTouchSlop * 0.45f);
                    if (dx >= -startThreshold || absDx <= absDy * 1.00f) {
                        return true;
                    }
                    activity.drawerManualDragging = true;
                    cancelMainListPendingPresses();
                    activity.drawerForceSettling = false;
                }

                // Track the finger: leftward travel (dx < 0) reduces the offset in
                // direct proportion to the distance, mirroring the open gesture's
                // dragGain so open and close feel identical.
                final float dragGain = 1.18f;
                float offset = activity.drawerDismissStartOffset
                        + (dx * dragGain) / Math.max(1f, drawerView.getWidth());
                offset = Math.max(0f, Math.min(1f, offset));
                applyDrawerManualOffset(drawerView, offset);
                return true;

            case MotionEvent.ACTION_UP:
                if (!activity.drawerDismissTracking || !activity.drawerSwipeTracking) {
                    resetDrawerSwipeState();
                    return true;
                }
                if (!activity.drawerManualDragging) {
                    // A tap outside the drawer (no real drag) must leave it open;
                    // the requested behavior is anywhere-left-swipe close only.
                    // We claimed settling on DOWN, so release it here since no
                    // manual animation is in flight.
                    activity.drawerForceSettling = false;
                    resetDrawerSwipeState();
                    return true;
                }
                // Release decides open vs close from where the drawer ended up.
                // Anything dragged in past ~65% snaps back open; otherwise it
                // finishes closing from its current position.
                boolean keepOpen = activity.drawerSlideOffset >= 0.65f;
                resetDrawerSwipeState();
                if (keepOpen) {
                    openDrawerReliably(drawerView);
                } else {
                    // Let DrawerLayout animate from the current offset down to 0.
                    // Mark settling so the idle-state safety net does not snap it
                    // shut mid-animation and kill the smoothness.
                    activity.drawerForceSettling = true;
                    activity.drawerLayout.closeDrawer(GravityCompat.START);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (activity.drawerManualDragging) {
                    // Settle to the nearest end so we never strand a half state.
                    boolean keepOpenOnCancel = activity.drawerSlideOffset >= 0.65f;
                    resetDrawerSwipeState();
                    if (keepOpenOnCancel) {
                        openDrawerReliably(drawerView);
                    } else {
                        activity.drawerForceSettling = true;
                        activity.drawerLayout.closeDrawer(GravityCompat.START);
                    }
                    return true;
                }
                resetDrawerSwipeState();
                activity.drawerForceSettling = false;
                return false;

            default:
                return activity.drawerDismissTracking && activity.drawerSwipeTracking;
        }
    }

    private void applyDrawerManualOffset(@NonNull View drawerView, float offset) {
        activity.drawerSlideOffset = offset;

        if (offset > 0f) {
            drawerView.setVisibility(View.VISIBLE);
            activity.drawerLayout.bringChildToFront(drawerView);
        }

        try {
            Method method = activity.drawerMoveToOffsetMethod;
            if (method == null) {
                method = DrawerLayout.class.getDeclaredMethod("moveDrawerToOffset", View.class, float.class);
                method.setAccessible(true);
                activity.drawerMoveToOffsetMethod = method;
            }
            method.invoke(activity.drawerLayout, drawerView, offset);
            drawerView.setVisibility(offset > 0f ? View.VISIBLE : View.INVISIBLE);
            activity.drawerLayout.invalidate();
        } catch (Throwable ignored) {
            // Fallback path for AndroidX internals changes. DrawerLayout normally
            // lays a closed left drawer at -drawerWidth; therefore positive
            // translationX must be width * offset. The old negative translation
            // pushed the drawer farther offscreen, leaving only the dark scrim.
            int width = Math.max(1, drawerView.getWidth());
            drawerView.setTranslationX(width * offset);
            drawerView.setVisibility(offset > 0f ? View.VISIBLE : View.INVISIBLE);
            activity.drawerLayout.invalidate();
        }
    }

    private void forceDrawerClosedVisualState(@NonNull View drawerView) {
        activity.drawerSlideOffset = 0f;
        activity.drawerClosePartialOnRelease = false;
        activity.drawerForceSettling = false;
        applyDrawerManualOffset(drawerView, 0f);
        drawerView.setTranslationX(0f);
        drawerView.setVisibility(View.INVISIBLE);
        activity.drawerLayout.postInvalidateOnAnimation();
    }

    private boolean isTouchInsideView(View view, @NonNull MotionEvent event) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        return rawX >= loc[0] && rawX <= loc[0] + view.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + view.getHeight();
    }

    private void openDrawerReliably(@NonNull View drawerView) {
        if (activity.drawerLayout == null) {
            resetDrawerSwipeState();
            return;
        }

        activity.drawerClosePartialOnRelease = false;
        activity.drawerForceSettling = true;
        drawerView.setTranslationX(0f);
        drawerView.setVisibility(View.VISIBLE);

        try {
            activity.drawerLayout.openDrawer(GravityCompat.START);
        } catch (IllegalArgumentException ignored) {
            activity.drawerForceSettling = false;
            return;
        }

        drawerView.postDelayed(() -> {
            if (activity.drawerLayout == null) return;
            if (!activity.drawerLayout.isDrawerOpen(GravityCompat.START)
                    && activity.drawerSlideOffset >= 0.30f) {
                drawerView.setTranslationX(0f);
                drawerView.setVisibility(View.VISIBLE);
                try {
                    activity.drawerLayout.openDrawer(GravityCompat.START);
                } catch (IllegalArgumentException ignored) {
                    activity.drawerForceSettling = false;
                    return;
                }
            }
            if (!activity.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                activity.drawerForceSettling = false;
            }
        }, 360L);
    }

    void closeDrawerAfterSelection() {
        if (activity.drawerLayout == null) {
            resetDrawerSwipeState();
            return;
        }

        View drawerView = activity.findViewById(R.id.nav_drawer);
        closeDrawerReliably(drawerView);
    }

    private void closeDrawerReliably(View drawerView) {
        if (activity.drawerLayout == null) {
            resetDrawerSwipeState();
            return;
        }

        activity.drawerClosePartialOnRelease = false;
        activity.drawerForceSettling = false;
        resetDrawerSwipeState();

        try {
            // Do not gate this behind isDrawerOpen(). On some large-screen/tablet
            // paths the drawer can be visible/interactive while DrawerLayout has not
            // yet promoted it to the fully-open flag. Gating on isDrawerOpen() then
            // leaves the drawer on screen after a shortcut/recent-folder tap.
            activity.drawerLayout.closeDrawer(GravityCompat.START);
        } catch (IllegalArgumentException ignored) {
            if (drawerView != null) {
                forceDrawerClosedVisualState(drawerView);
            }
            return;
        }

        if (drawerView != null) {
            drawerView.postDelayed(() -> {
                if (activity.drawerLayout == null) return;
                if (activity.drawerLayout.isDrawerVisible(GravityCompat.START)
                        && !activity.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    forceDrawerClosedVisualState(drawerView);
                }
            }, 280L);
        }
    }

    void resetDrawerSwipeState() {
        activity.drawerSwipeTracking = false;
        activity.drawerManualDragging = false;
        activity.drawerSwipeOpened = false;
        activity.drawerDismissTracking = false;
    }

    private void cancelMainListPendingPresses() {
        if (activity.fileAdapter != null) activity.fileAdapter.cancelPendingPresses();
        if (activity.recentAdapter != null) activity.recentAdapter.cancelPendingPresses();
        cancelRecyclerTouchState(activity.fileRecyclerView);
        cancelRecyclerTouchState(activity.recentRecyclerView);
    }

    private void cancelRecyclerTouchState(RecyclerView recyclerView) {
        if (recyclerView == null) return;
        recyclerView.cancelLongPress();
        recyclerView.setPressed(false);
        recyclerView.clearFocus();
        clearPressedStateRecursive(recyclerView);
        recyclerView.post(() -> clearPressedStateRecursive(recyclerView));
    }

    private void clearPressedStateRecursive(View view) {
        if (view == null) return;
        view.setPressed(false);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearPressedStateRecursive(group.getChildAt(i));
            }
        }
    }

    void settleHalfOpenedDrawer() {
        if (activity.drawerLayout == null || activity.drawerForceSettling) return;

        // Manual edge-drag release already decides open vs close. This remains as
        // a safety net for any partial state left by the underlying DrawerLayout.
        if (!activity.drawerLayout.isDrawerOpen(GravityCompat.START)
                && activity.drawerLayout.isDrawerVisible(GravityCompat.START)
                && activity.drawerSlideOffset < 0.995f) {
            View drawerView = activity.findViewById(R.id.nav_drawer);
            activity.drawerForceSettling = true;
            if (drawerView != null) {
                forceDrawerClosedVisualState(drawerView);
            }
            activity.drawerLayout.closeDrawer(GravityCompat.START);
        }
    }
}
