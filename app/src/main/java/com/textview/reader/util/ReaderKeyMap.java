package com.textview.reader.util;

import android.view.KeyEvent;

/** Pure hardware-key to page-direction mapping for TXT reader navigation. */
public final class ReaderKeyMap {
    private ReaderKeyMap() {
    }

    public static int pageTurnDirectionForKey(int keyCode) {
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
