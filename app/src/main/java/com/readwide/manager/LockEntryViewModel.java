package com.readwide.manager;

import androidx.lifecycle.ViewModel;

import com.readwide.manager.util.PrefsManager;

/** Holds an unfinished PIN flow in memory only, never in saved state or preferences. */
public final class LockEntryViewModel extends ViewModel {
    enum Result { CONTINUE, TOO_SHORT, UNLOCKED, PIN_SET, PIN_CHANGED }

    private boolean initialized;
    private int mode;
    private int messageResId;
    private String input = "";
    private String firstEntry;

    void initialize(int requestedMode) {
        if (initialized) return;
        initialized = true;
        mode = requestedMode;
        switch (mode) {
            case LockActivity.MODE_SET_PIN:
                messageResId = R.string.lock_enter_new_pin;
                break;
            case LockActivity.MODE_CHANGE_PIN:
                messageResId = R.string.lock_enter_current_pin;
                break;
            default:
                messageResId = R.string.lock_enter_pin_to_unlock;
                break;
        }
    }

    String getInput() { return input; }
    void setInput(String value) { input = value == null ? "" : value; }
    int getMessageResId() { return messageResId; }

    Result confirm(PrefsManager prefs) {
        String pin = input;
        if (pin.length() < 4) return Result.TOO_SHORT;

        switch (mode) {
            case LockActivity.MODE_UNLOCK:
                if (prefs.verifyLockPin(pin)) {
                    clearEntry();
                    return Result.UNLOCKED;
                }
                messageResId = R.string.lock_wrong_pin_retry;
                input = "";
                break;

            case LockActivity.MODE_SET_PIN:
                if (firstEntry == null) {
                    firstEntry = pin;
                    messageResId = R.string.lock_confirm_pin;
                    input = "";
                } else if (pin.equals(firstEntry)) {
                    prefs.setLockPin(pin);
                    prefs.setLockEnabled(true);
                    clearEntry();
                    return Result.PIN_SET;
                } else {
                    messageResId = R.string.lock_pin_mismatch_restart;
                    clearEntry();
                }
                break;

            case LockActivity.MODE_CHANGE_PIN:
                if (firstEntry == null) {
                    if (prefs.verifyLockPin(pin)) {
                        firstEntry = "VERIFIED";
                        messageResId = R.string.lock_enter_new_pin;
                    } else {
                        messageResId = R.string.lock_wrong_current_pin;
                    }
                    input = "";
                } else if (firstEntry.equals("VERIFIED")) {
                    firstEntry = pin;
                    messageResId = R.string.lock_confirm_new_pin;
                    input = "";
                } else if (pin.equals(firstEntry)) {
                    prefs.setLockPin(pin);
                    clearEntry();
                    return Result.PIN_CHANGED;
                } else {
                    messageResId = R.string.lock_pin_mismatch_enter_new;
                    firstEntry = "VERIFIED";
                    input = "";
                }
                break;
        }
        return Result.CONTINUE;
    }

    private void clearEntry() {
        input = "";
        firstEntry = null;
    }

    @Override protected void onCleared() {
        clearEntry();
    }
}
