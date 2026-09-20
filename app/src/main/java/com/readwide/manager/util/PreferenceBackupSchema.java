package com.readwide.manager.util;

/** Stored types consumed by this app; unknown backup keys remain forward compatible. */
final class PreferenceBackupSchema {
    private PreferenceBackupSchema() {}

    static boolean accepts(String key, Object value) {
        Class<?> expected = valueType(key);
        return expected == null || expected.isInstance(value);
    }

    static Class<?> valueType(String key) {
        if (key == null) return null;
        switch (key) {
            case "brightness_value":
            case "font_size":
            case "line_spacing":
                return Float.class;
            case "archive_landscape_spread_enabled":
            case "auto_save_position":
            case "brightness_override":
            case "epub_force_reader_theme_colors":
            case "epub_tap_paging_enabled":
            case "file_search_all_folders":
            case "file_thumbnails_enabled":
            case "image_tap_paging_enabled":
            case "keep_screen_on":
            case "markdown_tap_paging_enabled":
            case "pdf_tap_paging_enabled":
            case "reader_search_case_sensitive":
            case "reader_search_regex":
            case "reader_search_whole_word":
            case "show_hidden":
            case "show_status_bar":
            case "tap_paging_enabled":
            case "tts_last_continuous":
            case "tts_sleep_timer_finish_sentence":
            case "txt_collapse_blank_lines":
            case "volume_key_scroll":
            case "word_tap_paging_enabled":
                return Boolean.class;
            case "archive_open_mode":
            case "archive_sort_mode":
            case "archive_viewer_background_timeout_minutes":
            case "auto_page_turn_interval_seconds":
            case "dark_mode":
            case "document_side_padding_dp":
            case "epub_bottom_padding_dp":
            case "epub_left_padding_dp":
            case "epub_page_direction":
            case "epub_right_padding_dp":
            case "epub_side_padding_dp":
            case "epub_top_padding_dp":
            case "file_display_mode":
            case "image_slider_direction":
            case "language_mode":
            case "large_text_partition_mode":
            case "main_custom_bar":
            case "main_custom_bg":
            case "main_custom_drawer_action_icon":
            case "main_custom_file_type_chip":
            case "main_custom_file_type_chip_selected":
            case "main_custom_outline":
            case "main_custom_panel":
            case "main_custom_reading_card":
            case "main_custom_selected":
            case "main_custom_shortcut_box":
            case "main_custom_sub_text":
            case "main_custom_text":
            case "page_margin_h":
            case "page_margin_v":
            case "page_status_alignment":
            case "paging_overlap_lines":
            case "reader_text_bottom_offset_px":
            case "reader_text_left_inset_px":
            case "reader_text_right_inset_px":
            case "reader_text_top_offset_px":
            case "recent_sort_mode":
            case "sort_mode":
            case "tap_leading_zone_percent":
            case "tap_trailing_zone_percent":
            case "tap_zone_mode":
            case "text_alignment":
            case "tts_last_char_position":
            case "tts_last_page_number":
            case "tts_last_sleep_timer_min":
            case "tts_last_text_format_version":
            case "tts_pause_reduction":
            case "tts_phrase_length_level":
            case "tts_pitch_percent":
            case "tts_sleep_timer_minutes":
            case "tts_speech_rate_percent":
                return Integer.class;
            case "tts_last_timestamp":
                return Long.class;
            case "active_theme_id":
            case "epub_font_family":
            case "folder_shortcuts":
            case "font_family":
            case "hidden_recent_folders":
            case "last_directory":
            case "last_reader_search_query":
            case "recent_folders":
            case "tts_language_tag":
            case "tts_last_file_path":
            case "tts_voice_name":
            case "txt_display_replacement_rules_json":
                return String.class;
            default:
                // Each family is read with getString, regardless of group/path.
                if (key.startsWith("button_order_") || key.startsWith("archive_last_image_")
                        || key.startsWith("manual_text_encoding::")) return String.class;
                return null;
        }
    }
}
