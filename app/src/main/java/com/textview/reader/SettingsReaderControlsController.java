package com.textview.reader;

import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.textview.reader.util.PrefsManager;

final class SettingsReaderControlsController {
    private static final int STEP_PX = 5;
    private static final int MAX_INSET_PX = 240;
    private static final int MAX_BOUNDARY_PX = 360;

    private final SettingsActivity activity;
    private final PrefsManager prefs;

    SettingsReaderControlsController(@NonNull SettingsActivity activity) {
        this.activity = activity;
        this.prefs = activity.prefs;
    }

    void setupReaderControls() {
        setupFontSize();
        setupLineSpacing();
        setupTextZoneTuning();
        setupLargeTextPartitionMode();
        setupArchiveOpenMode();
        setupEpubBoundary();
        setupEpubPageBehavior();
        setupSwitches();
    }

    private void setupFontSize() {
        SeekBar sb = activity.findViewById(R.id.font_size_seekbar);
        TextView label = activity.findViewById(R.id.font_size_label);
        if (sb == null || label == null) return;
        float current = prefs.getFontSize();
        sb.setMax(40);
        sb.setProgress(Math.round(current - 8f));
        label.setText(String.format(activity.getString(R.string.font_size_format), current));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float size = p + 8f;
                label.setText(String.format(activity.getString(R.string.font_size_format), size));
                if (fromUser) prefs.setFontSize(size);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void setupLineSpacing() {
        SeekBar sb = activity.findViewById(R.id.line_spacing_seekbar);
        TextView label = activity.findViewById(R.id.line_spacing_label);
        if (sb == null || label == null) return;
        float current = prefs.getLineSpacing();
        sb.setMax(20);
        sb.setProgress(Math.round((current - 1.0f) * 10f));
        label.setText(String.format(activity.getString(R.string.line_spacing_format), current));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float spacing = 1.0f + p / 10f;
                label.setText(String.format(activity.getString(R.string.line_spacing_format), spacing));
                if (fromUser) prefs.setLineSpacing(spacing);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void setupTextZoneTuning() {
        bindReaderInsetSeekBar(
                R.id.txt_top_offset_seekbar,
                R.id.txt_top_offset_label,
                prefs.getReaderTextTopOffsetPx(),
                R.string.txt_top_offset_format,
                prefs::setReaderTextTopOffsetPx);
        bindReaderInsetSeekBar(
                R.id.txt_bottom_offset_seekbar,
                R.id.txt_bottom_offset_label,
                prefs.getReaderTextBottomOffsetPx(),
                R.string.txt_bottom_offset_format,
                prefs::setReaderTextBottomOffsetPx);
        bindReaderPixelInsetSeekBar(
                R.id.txt_left_inset_seekbar,
                R.id.txt_left_inset_label,
                prefs.getReaderTextLeftBoundaryPx(),
                R.string.txt_left_inset_format,
                prefs::setReaderTextLeftBoundaryPx);
        bindReaderPixelInsetSeekBar(
                R.id.txt_right_inset_seekbar,
                R.id.txt_right_inset_label,
                prefs.getReaderTextRightBoundaryPx(),
                R.string.txt_right_inset_format,
                prefs::setReaderTextRightBoundaryPx);
    }

    private void bindReaderInsetSeekBar(
            int seekBarId,
            int labelId,
            int currentPx,
            int labelFormatResId,
            @NonNull IntSetter setter
    ) {
        SeekBar seekBar = activity.findViewById(seekBarId);
        TextView label = activity.findViewById(labelId);
        if (seekBar == null || label == null) return;
        int rounded = roundToInsetStep(currentPx);
        seekBar.setMax(MAX_INSET_PX / STEP_PX);
        seekBar.setProgress(rounded / STEP_PX);
        label.setText(activity.getString(labelFormatResId, rounded));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = roundToInsetStep(progress * STEP_PX);
                label.setText(activity.getString(labelFormatResId, value));
                if (fromUser) setter.set(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                setter.set(roundToInsetStep(bar.getProgress() * STEP_PX));
            }
        });
    }

    private void bindReaderPixelInsetSeekBar(
            int seekBarId,
            int labelId,
            int currentPx,
            int labelFormatResId,
            @NonNull IntSetter setter
    ) {
        SeekBar seekBar = activity.findViewById(seekBarId);
        TextView label = activity.findViewById(labelId);
        if (seekBar == null || label == null) return;
        int current = Math.max(0, Math.min(MAX_BOUNDARY_PX, currentPx));
        seekBar.setMax(MAX_BOUNDARY_PX);
        seekBar.setProgress(current);
        label.setText(activity.getString(labelFormatResId, current));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = Math.max(0, Math.min(MAX_BOUNDARY_PX, progress));
                label.setText(activity.getString(labelFormatResId, value));
                if (fromUser) setter.set(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                int value = Math.max(0, Math.min(MAX_BOUNDARY_PX, bar.getProgress()));
                setter.set(value);
            }
        });
    }

    private void setupLargeTextPartitionMode() {
        Spinner modeSpinner = activity.findViewById(R.id.spinner_large_txt_partition_mode);
        if (modeSpinner == null) return;

        String[] choices = {
                activity.getString(R.string.large_txt_partition_mode_standard),
                activity.getString(R.string.large_txt_partition_mode_high_buffer)
        };
        ArrayAdapter<String> adapter = makeSettingsSpinnerAdapter(choices);
        modeSpinner.setAdapter(adapter);
        modeSpinner.setSelection(prefs.getLargeTextPartitionMode() == PrefsManager.LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                ? PrefsManager.LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                : PrefsManager.LARGE_TEXT_PARTITION_MODE_STANDARD);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                styleSpinnerText(view);
                prefs.setLargeTextPartitionMode(position == PrefsManager.LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                        ? PrefsManager.LARGE_TEXT_PARTITION_MODE_HIGH_BUFFER
                        : PrefsManager.LARGE_TEXT_PARTITION_MODE_STANDARD);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupArchiveOpenMode() {
        Spinner modeSpinner = activity.findViewById(R.id.spinner_archive_open_mode);
        if (modeSpinner == null) return;

        String[] choices = {
                activity.getString(R.string.archive_open_mode_normal),
                activity.getString(R.string.archive_open_mode_comic)
        };
        modeSpinner.setAdapter(makeSettingsSpinnerAdapter(choices));
        modeSpinner.setSelection(prefs.getArchiveOpenMode() == PrefsManager.ARCHIVE_OPEN_MODE_COMIC
                ? PrefsManager.ARCHIVE_OPEN_MODE_COMIC
                : PrefsManager.ARCHIVE_OPEN_MODE_NORMAL);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                styleSpinnerText(view);
                prefs.setArchiveOpenMode(position == PrefsManager.ARCHIVE_OPEN_MODE_COMIC
                        ? PrefsManager.ARCHIVE_OPEN_MODE_COMIC
                        : PrefsManager.ARCHIVE_OPEN_MODE_NORMAL);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupEpubBoundary() {
        bindEpubBoundarySeekBar(
                R.id.epub_left_spacing_seekbar,
                R.id.epub_left_spacing_label,
                prefs.getEpubLeftPaddingDp(),
                R.string.epub_left_spacing_format,
                prefs::setEpubLeftPaddingDp);
        bindEpubBoundarySeekBar(
                R.id.epub_right_spacing_seekbar,
                R.id.epub_right_spacing_label,
                prefs.getEpubRightPaddingDp(),
                R.string.epub_right_spacing_format,
                prefs::setEpubRightPaddingDp);
        bindEpubBoundarySeekBar(
                R.id.epub_top_spacing_seekbar,
                R.id.epub_top_spacing_label,
                prefs.getEpubTopPaddingDp(),
                R.string.epub_top_spacing_format,
                prefs::setEpubTopPaddingDp);
        bindEpubBoundarySeekBar(
                R.id.epub_bottom_spacing_seekbar,
                R.id.epub_bottom_spacing_label,
                prefs.getEpubBottomPaddingDp(),
                R.string.epub_bottom_spacing_format,
                prefs::setEpubBottomPaddingDp);
    }

    private void bindEpubBoundarySeekBar(
            int seekBarId,
            int labelId,
            int currentPx,
            int labelFormatResId,
            @NonNull IntSetter setter
    ) {
        SeekBar seekBar = activity.findViewById(seekBarId);
        TextView label = activity.findViewById(labelId);
        if (seekBar == null || label == null) return;
        int current = roundToInsetStep(currentPx);
        seekBar.setMax(MAX_INSET_PX / STEP_PX);
        seekBar.setProgress(current / STEP_PX);
        label.setText(activity.getString(labelFormatResId, current));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = roundToInsetStep(progress * STEP_PX);
                label.setText(activity.getString(labelFormatResId, value));
                if (fromUser) setter.set(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                setter.set(roundToInsetStep(bar.getProgress() * STEP_PX));
            }
        });
    }

    private void setupEpubPageBehavior() {
        Spinner directionSpinner = activity.findViewById(R.id.spinner_epub_page_direction);
        Spinner effectSpinner = activity.findViewById(R.id.spinner_epub_page_effect);

        if (directionSpinner != null) {
            String[] directionChoices = {
                    activity.getString(R.string.epub_page_direction_ltr),
                    activity.getString(R.string.epub_page_direction_rtl)
            };
            ArrayAdapter<String> adapter = makeSettingsSpinnerAdapter(directionChoices);
            directionSpinner.setAdapter(adapter);
            directionSpinner.setSelection(prefs.getEpubPageDirection());
            directionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    styleSpinnerText(view);
                    prefs.setEpubPageDirection(position == PrefsManager.EPUB_PAGE_DIRECTION_RTL
                            ? PrefsManager.EPUB_PAGE_DIRECTION_RTL
                            : PrefsManager.EPUB_PAGE_DIRECTION_LTR);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        if (effectSpinner != null) {
            String[] effectChoices = {
                    activity.getString(R.string.epub_page_effect_slide),
                    activity.getString(R.string.epub_page_effect_none)
            };
            ArrayAdapter<String> adapter = makeSettingsSpinnerAdapter(effectChoices);
            effectSpinner.setAdapter(adapter);
            effectSpinner.setSelection(prefs.getEpubPageEffect());
            effectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    styleSpinnerText(view);
                    prefs.setEpubPageEffect(position == PrefsManager.EPUB_PAGE_EFFECT_NONE
                            ? PrefsManager.EPUB_PAGE_EFFECT_NONE
                            : PrefsManager.EPUB_PAGE_EFFECT_SLIDE);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }

    private void setupSwitches() {
        bindSwitch(R.id.switch_keep_screen_on, prefs.getKeepScreenOn(), prefs::setKeepScreenOn);
        bindSwitch(R.id.switch_auto_save, prefs.getAutoSavePosition(), prefs::setAutoSavePosition);
        bindSwitch(R.id.switch_status_bar, prefs.getShowStatusBar(), prefs::setShowStatusBar);
        bindPageStatusAlignmentSpinner();
        bindSwitch(R.id.switch_volume_scroll, prefs.getVolumeKeyScroll(), prefs::setVolumeKeyScroll);
        bindSwitch(R.id.switch_tap_paging, prefs.getTapPagingEnabled(), prefs::setTapPagingEnabled);
        bindTapZoneModeSpinner();
        setupTapZoneRatioControl();
        bindPagingOverlapSpinner();
    }

    private void bindSwitch(int switchId, boolean checked, @NonNull BooleanSetter setter) {
        Switch sw = activity.findViewById(switchId);
        if (sw == null) return;
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((v, enabled) -> setter.set(enabled));
    }

    private void bindPageStatusAlignmentSpinner() {
        Spinner spinner = activity.findViewById(R.id.spinner_page_status_alignment);
        if (spinner == null) return;
        String[] choices = {
                activity.getString(R.string.page_status_align_left),
                activity.getString(R.string.page_status_align_center),
                activity.getString(R.string.page_status_align_right),
                activity.getString(R.string.page_status_align_hidden)
        };
        spinner.setAdapter(makeSettingsSpinnerAdapter(choices));
        spinner.setSelection(prefs.getPageStatusAlignment());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                styleSpinnerText(view);
                prefs.setPageStatusAlignment(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void bindTapZoneModeSpinner() {
        Spinner spinner = activity.findViewById(R.id.spinner_tap_zone_mode);
        if (spinner == null) return;
        String[] choices = {
                activity.getString(R.string.tap_zone_vertical),
                activity.getString(R.string.tap_zone_horizontal)
        };
        spinner.setAdapter(makeSettingsSpinnerAdapter(choices));
        spinner.setSelection(prefs.getTapZoneMode());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                styleSpinnerText(view);
                prefs.setTapZoneMode(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void bindPagingOverlapSpinner() {
        Spinner spinner = activity.findViewById(R.id.spinner_overlap_lines);
        if (spinner == null) return;
        String[] choices = {
                activity.getString(R.string.no_overlap),
                activity.getString(R.string.keep_1_line),
                activity.getString(R.string.keep_2_lines),
                activity.getString(R.string.keep_3_lines),
                activity.getString(R.string.keep_4_lines)
        };
        spinner.setAdapter(makeSettingsSpinnerAdapter(choices));
        spinner.setSelection(prefs.getPagingOverlapLines());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                styleSpinnerText(view);
                prefs.setPagingOverlapLines(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupTapZoneRatioControl() {
        SeekBar leadingSeekBar = activity.findViewById(R.id.tap_zone_leading_seekbar);
        SeekBar trailingSeekBar = activity.findViewById(R.id.tap_zone_trailing_seekbar);
        TextView ratioLabel = activity.findViewById(R.id.tap_zone_ratio_label);
        TextView leadingLabel = activity.findViewById(R.id.tap_zone_leading_label);
        TextView trailingLabel = activity.findViewById(R.id.tap_zone_trailing_label);
        if (leadingSeekBar == null || trailingSeekBar == null || ratioLabel == null
                || leadingLabel == null || trailingLabel == null) return;

        final boolean[] suppress = new boolean[]{false};
        int leading = clampTapEdgePercent(prefs.getTapLeadingZonePercent());
        int trailing = clampTapEdgePercent(prefs.getTapTrailingZonePercent());
        if (leading + trailing > 90) trailing = Math.max(5, 90 - leading);

        leadingSeekBar.setMax(75);
        trailingSeekBar.setMax(75);
        leadingSeekBar.setProgress(leading - 5);
        trailingSeekBar.setProgress(trailing - 5);
        updateTapZoneRatioLabels(ratioLabel, leadingLabel, trailingLabel, leading, trailing);

        leadingSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (suppress[0]) return;
                int leading = progress + 5;
                int trailing = trailingSeekBar.getProgress() + 5;
                if (leading + trailing > 90) {
                    trailing = Math.max(5, 90 - leading);
                    suppress[0] = true;
                    trailingSeekBar.setProgress(trailing - 5);
                    suppress[0] = false;
                }
                updateTapZoneRatioLabels(ratioLabel, leadingLabel, trailingLabel, leading, trailing);
                if (fromUser) prefs.setTapZonePercents(leading, trailing);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.setTapZonePercents(seekBar.getProgress() + 5, trailingSeekBar.getProgress() + 5);
            }
        });

        trailingSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (suppress[0]) return;
                int leading = leadingSeekBar.getProgress() + 5;
                int trailing = progress + 5;
                if (leading + trailing > 90) {
                    leading = Math.max(5, 90 - trailing);
                    suppress[0] = true;
                    leadingSeekBar.setProgress(leading - 5);
                    suppress[0] = false;
                }
                updateTapZoneRatioLabels(ratioLabel, leadingLabel, trailingLabel, leading, trailing);
                if (fromUser) prefs.setTapZonePercents(leading, trailing);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.setTapZonePercents(leadingSeekBar.getProgress() + 5, seekBar.getProgress() + 5);
            }
        });
    }

    private ArrayAdapter<String> makeSettingsSpinnerAdapter(String[] choices) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_spinner_item, choices) {
            @NonNull @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleSpinnerText(view);
                return view;
            }
            @Override
            public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                styleSpinnerText(view);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void styleSpinnerText(View view) {
        if (view instanceof TextView) {
            boolean dark = isDarkUi();
            int bg = prefs != null ? prefs.getMainBgColor(activity) : (dark ? Color.rgb(0, 0, 0) : Color.rgb(255, 255, 255));
            int text = prefs != null ? prefs.getMainTextColor(activity) : (dark ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36));
            TextView tv = (TextView) view;
            tv.setTextColor(text);
            tv.setBackgroundColor(bg);
            tv.setTextSize(16f);
            int pad = Math.round(14 * activity.getResources().getDisplayMetrics().density);
            tv.setPadding(pad, 0, pad, 0);
        }
    }

    private boolean isDarkUi() {
        if (prefs != null) return prefs.shouldUseDarkColors(activity);
        int mask = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private int roundToInsetStep(int px) {
        int clamped = Math.max(0, Math.min(MAX_INSET_PX, px));
        return Math.round(clamped / (float) STEP_PX) * STEP_PX;
    }

    private int clampTapEdgePercent(int percent) {
        return Math.max(5, Math.min(80, percent));
    }

    private void updateTapZoneRatioLabels(TextView ratioLabel, TextView leadingLabel,
                                          TextView trailingLabel, int leadingPercent,
                                          int trailingPercent) {
        int leading = clampTapEdgePercent(leadingPercent);
        int trailing = clampTapEdgePercent(trailingPercent);
        if (leading + trailing > 90) trailing = Math.max(5, 90 - leading);
        int middle = Math.max(10, 100 - leading - trailing);

        ratioLabel.setText(activity.getString(R.string.tap_zone_ratio_format, leading, middle, trailing));
        leadingLabel.setText(activity.getString(R.string.tap_zone_leading_format, leading));
        trailingLabel.setText(activity.getString(R.string.tap_zone_trailing_format, trailing));
    }

    private interface IntSetter {
        void set(int value);
    }

    private interface BooleanSetter {
        void set(boolean value);
    }
}
