package com.readwide.manager;

import android.view.KeyEvent;

import androidx.annotation.NonNull;

final class DocumentPageTurnController {
    private final DocumentPageActivity activity;

    DocumentPageTurnController(@NonNull DocumentPageActivity activity) {
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

    void pageBy(int direction) {
        if (activity.documentPageCount() <= 0) return;
        activity.turnDocumentDisplayPageBy(direction);
    }

    private int pageTurnDirectionForKey(int keyCode) {
        boolean rtlEpub = "EPUB".equals(activity.docType)
                && activity.prefs != null
                && activity.prefs.getEpubPageDirection()
                == com.readwide.manager.util.PrefsManager.EPUB_PAGE_DIRECTION_RTL;
        if (rtlEpub) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return +1;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return -1;
        }
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
