package com.readwide.manager;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

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
    private LockEntryViewModel entryState;

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
        bindEntryState();

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

    private void bindEntryState() {
        entryState = new ViewModelProvider(this).get(LockEntryViewModel.class);
        entryState.initialize(mode);
        // PINs and verification steps must not enter the saved hierarchy. A new
        // process starts fresh; only a retained in-memory model resumes the flow.
        pinInput.setSaveFromParentEnabled(false);
        messageText.setSaveFromParentEnabled(false);
        renderEntryState();
        pinInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                entryState.setInput(text == null ? "" : text.toString());
            }
        });
    }

    private void renderEntryState() {
        pinInput.setText(entryState.getInput());
        pinInput.setSelection(pinInput.length());
        messageText.setText(entryState.getMessageResId());
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
        View lockContent = findViewById(R.id.lock_content);
        if (lockContent != null) lockContent.setBackgroundColor(bg);
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
        if (prefs == null) prefs = PrefsManager.getInstance(this);
        entryState.setInput(pinInput.getText().toString());
        LockEntryViewModel.Result result = entryState.confirm(prefs);
        renderEntryState();
        switch (result) {
            case TOO_SHORT:
                ShortToast.show(this, R.string.lock_pin_minimum_digits);
                break;
            case PIN_SET:
                ShortToast.show(this, R.string.lock_pin_set_success);
                setResult(RESULT_OK);
                finish();
                break;
            case PIN_CHANGED:
                ShortToast.show(this, R.string.lock_pin_changed);
                setResult(RESULT_OK);
                finish();
                break;
            case UNLOCKED:
                setResult(RESULT_OK);
                finish();
                break;
            default:
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
