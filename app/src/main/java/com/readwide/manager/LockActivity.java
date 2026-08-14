package com.readwide.manager;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.readwide.manager.util.PrefsManager;

/**
 * PIN lock screen. Shown on app launch when lock is enabled.
 */
public class LockActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "lock_mode";
    public static final int MODE_UNLOCK = 0;
    public static final int MODE_SET_PIN = 1;
    public static final int MODE_CHANGE_PIN = 2;

    private EditText pinInput;
    private TextView messageText;
    private PrefsManager prefs;
    private int mode;
    private String firstEntry; // for PIN confirmation during set

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = PrefsManager.getInstance(this);
        prefs.applyLanguage(prefs.getLanguageMode());
        prefs.applyDarkMode(prefs.getDarkMode());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        pinInput = findViewById(R.id.pin_input);
        messageText = findViewById(R.id.lock_message);
        Button btnConfirm = findViewById(R.id.btn_confirm);

        applyLockTheme();

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_UNLOCK);

        switch (mode) {
            case MODE_SET_PIN:
                messageText.setText(R.string.lock_enter_new_pin);
                break;
            case MODE_CHANGE_PIN:
                messageText.setText(R.string.lock_enter_current_pin);
                break;
            default:
                messageText.setText(R.string.lock_enter_pin_to_unlock);
                break;
        }

        btnConfirm.setOnClickListener(v -> onConfirm());

        // Number pad buttons
        int[] numBtnIds = {
                R.id.btn_0, R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4,
                R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9
        };
        for (int i = 0; i < numBtnIds.length; i++) {
            View btn = findViewById(numBtnIds[i]);
            if (btn != null) {
                final String digit = String.valueOf(i);
                btn.setOnClickListener(v -> {
                    String current = pinInput.getText().toString();
                    if (current.length() < 8) {
                        pinInput.getText().append(digit);
                        pinInput.setSelection(pinInput.length());
                    }
                });
            }
        }

        View btnDelete = findViewById(R.id.btn_delete);
        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                String current = pinInput.getText().toString();
                if (!current.isEmpty()) {
                    pinInput.setText(current.substring(0, current.length() - 1));
                    pinInput.setSelection(pinInput.length());
                }
            });
        }
    }

    private void applyLockTheme() {
        if (prefs == null) return;

        int bg = prefs.getMainBgColor(this);
        int panel = prefs.getMainPanelColor(this);
        int elevated = prefs.getMainElevatedPanelColor(this);
        int text = prefs.getMainTextColor(this);
        int sub = prefs.getMainSubTextColor(this);
        int outline = prefs.getMainOutlineColor(this);
        int control = prefs.getMainControlColor(this);

        ViewGroup content = findViewById(android.R.id.content);
        View root = content != null && content.getChildCount() > 0 ? content.getChildAt(0) : null;
        if (root != null) root.setBackgroundColor(bg);
        if (messageText != null) messageText.setTextColor(text);

        if (pinInput != null) {
            pinInput.setTextColor(text);
            pinInput.setHintTextColor(sub);
            pinInput.setBackgroundTintList(ColorStateList.valueOf(control));
        }

        int[] outlineButtons = {
                R.id.btn_0, R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4,
                R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9,
                R.id.btn_delete
        };
        for (int id : outlineButtons) {
            View v = findViewById(id);
            if (v instanceof Button) {
                styleLockButton((Button) v, text, panel, outline);
            }
        }

        View confirm = findViewById(R.id.btn_confirm);
        if (confirm instanceof Button) {
            styleLockTextActionButton((Button) confirm, control);
        }
    }

    private void styleLockButton(Button button, int textColor, int bgColor, int outlineColor) {
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        CharSequence label = button.getText();
        if (label != null && label.length() == 1 && Character.isDigit(label.charAt(0))) {
            button.setTextSize(26f);
        } else {
            button.setTextSize(23f);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dpToPx(14));
        bg.setStroke(dpToPx(1), outlineColor);
        button.setBackground(bg);
    }


    private void styleLockTextActionButton(Button button, int textColor) {
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setTextSize(23f);
        button.setBackground(null);
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setTranslationZ(0f);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dpToPx(8), 0, dpToPx(8), 0);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void onConfirm() {
        String pin = pinInput.getText().toString();
        if (pin.length() < 4) {
            ShortToast.show(this, R.string.lock_pin_minimum_digits);
            return;
        }

        if (prefs == null) prefs = PrefsManager.getInstance(this);

        switch (mode) {
            case MODE_UNLOCK:
                if (prefs.verifyLockPin(pin)) {
                    setResult(RESULT_OK);
                    finish();
                } else {
                    messageText.setText(R.string.lock_wrong_pin_retry);
                    pinInput.setText("");
                }
                break;

            case MODE_SET_PIN:
                if (firstEntry == null) {
                    firstEntry = pin;
                    messageText.setText(R.string.lock_confirm_pin);
                    pinInput.setText("");
                } else {
                    if (pin.equals(firstEntry)) {
                        prefs.setLockPin(pin);
                        prefs.setLockEnabled(true);
                        ShortToast.show(this, R.string.lock_pin_set_success);
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        messageText.setText(R.string.lock_pin_mismatch_restart);
                        firstEntry = null;
                        pinInput.setText("");
                    }
                }
                break;

            case MODE_CHANGE_PIN:
                if (firstEntry == null) {
                    // Verify current PIN
                    if (prefs.verifyLockPin(pin)) {
                        firstEntry = "VERIFIED";
                        messageText.setText(R.string.lock_enter_new_pin);
                        pinInput.setText("");
                    } else {
                        messageText.setText(R.string.lock_wrong_current_pin);
                        pinInput.setText("");
                    }
                } else if (firstEntry.equals("VERIFIED")) {
                    firstEntry = pin;
                    messageText.setText(R.string.lock_confirm_new_pin);
                    pinInput.setText("");
                } else {
                    if (pin.equals(firstEntry)) {
                        prefs.setLockPin(pin);
                        ShortToast.show(this, R.string.lock_pin_changed);
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        messageText.setText(R.string.lock_pin_mismatch_enter_new);
                        firstEntry = "VERIFIED";
                        pinInput.setText("");
                    }
                }
                break;
        }
    }

    @Override
    public void onBackPressed() {
        if (mode == MODE_UNLOCK) {
            // Can't back out of unlock
            finishAffinity();
        } else {
            setResult(RESULT_CANCELED);
            super.onBackPressed();
        }
    }
}
