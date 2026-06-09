package com.textview.reader;

import android.view.KeyEvent;

import androidx.annotation.NonNull;

final class PdfPageTurnController {
    private final PdfReaderActivity activity;

    PdfPageTurnController(@NonNull PdfReaderActivity activity) {
        this.activity = activity;
    }

    boolean handlePageTurnKey(KeyEvent event) {
        if (event == null || activity.prefs == null || !activity.prefs.getVolumeKeyScroll()) {
            return false;
        }

        int direction = pageTurnDirectionForKey(event.getKeyCode());
        if (direction == 0) return false;

        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                pageBy(direction);
            }
            return true;
        }
        return action == KeyEvent.ACTION_UP;
    }

    private void pageBy(int direction) {
        if (activity.pageCount <= 0) return;
        int target = Math.max(0, Math.min(activity.pageCount - 1, activity.currentPage + direction));
        if (target != activity.currentPage) {
            activity.goToPage(target, Integer.compare(target, activity.currentPage));
        }
    }

    private int pageTurnDirectionForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                return +1;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                return -1;

            default:
                return 0;
        }
    }
}
