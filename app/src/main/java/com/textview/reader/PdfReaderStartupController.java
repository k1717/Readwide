package com.textview.reader;

import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

import com.textview.reader.util.BookmarkManager;

final class PdfReaderStartupController {
    private final PdfReaderActivity activity;

    PdfReaderStartupController(@NonNull PdfReaderActivity activity) {
        this.activity = activity;
    }

    void onCreateAfterSuper(Bundle savedInstanceState) {
        ViewerRegistry.activate(activity);

        activity.resolveReaderThemeColors();
        activity.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        activity.setContentView(R.layout.activity_pdf_reader);
        activity.applyDocumentSystemBarColors();
        com.textview.reader.util.EdgeToEdgeUtil.applyFoldableChromeInsets(
                activity,
                activity.findViewById(R.id.pdf_root),
                activity.findViewById(R.id.pdf_appbar),
                activity.findViewById(R.id.pdf_bottom_bar),
                activity.findViewById(R.id.pdf_viewport),
                () -> activity.pdfChromeVisible);
        activity.applyDocumentSystemBarColors();

        bindToolbar();
        bindViews();
        ButtonOrderManager.applyOrder(activity, activity.prefs, ButtonOrderManager.GROUP_PDF_VIEWER);

        activity.setupContinuousPdfList();
        activity.bookmarkManager = BookmarkManager.getInstance(activity);
        activity.verticalPageSlideMode = activity.getSharedPreferences("pdf_reader", activity.MODE_PRIVATE)
                .getBoolean("vertical_page_slide_mode", false);
        activity.styleControls();
        activity.setupControls();
        activity.installPdfGestures();

        activity.getOnBackPressedDispatcher().addCallback(activity, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { activity.finish(); }
        });

        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        activity.getWindow().setAttributes(lp);

        activity.loadPdfFromIntent();
    }

    void onNewIntent(@NonNull android.content.Intent intent) {
        activity.saveReadingState();
        activity.setIntent(intent);
        activity.loadPdfFromIntent();
    }

    void onResume() {
        activity.cancelPdfBackgroundMemoryTrim();
        activity.resolveReaderThemeColors();
        activity.applyDocumentSystemBarColors();
        ButtonOrderManager.applyOrder(activity, activity.prefs, ButtonOrderManager.GROUP_PDF_VIEWER);
        activity.styleControls();
        activity.restorePdfBitmapsAfterBackgroundTrimIfNeeded();
    }

    private void bindToolbar() {
        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        activity.setSupportActionBar(toolbar);
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setNavigationIcon(activity.tintedBackIcon());
    }

    private void bindViews() {
        activity.root = activity.findViewById(R.id.pdf_root);
        activity.pdfAppBar = activity.findViewById(R.id.pdf_appbar);
        activity.pdfBottomBar = activity.findViewById(R.id.pdf_bottom_bar);
        activity.pageImage = activity.findViewById(R.id.pdf_page_image);
        activity.pdfContinuousList = activity.findViewById(R.id.pdf_continuous_list);
        activity.progressBar = activity.findViewById(R.id.pdf_progress);
        activity.pageStatus = activity.findViewById(R.id.pdf_page_status);
        activity.prevButton = activity.findViewById(R.id.pdf_prev);
        activity.nextButton = activity.findViewById(R.id.pdf_next);
        activity.slideModeButton = activity.findViewById(R.id.pdf_slide_toggle);
        activity.pageButton = activity.findViewById(R.id.pdf_page);
        activity.bookmarkButton = activity.findViewById(R.id.pdf_bookmark);
        activity.zoomMoreButton = activity.findViewById(R.id.pdf_zoom_more);
        activity.pdfViewport = activity.findViewById(R.id.pdf_viewport);
        activity.pdfHScroll = activity.findViewById(R.id.pdf_h_scroll);
        activity.pdfVScroll = activity.findViewById(R.id.pdf_v_scroll);
    }
}
