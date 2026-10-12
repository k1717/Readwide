package com.readwide.manager;

import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReaderEncodingReloadInstrumentedTest {
    @Test public void newDecodeDiscardsOldAnchorsButDoesNotMutateLaunchIntent() {
        Intent launch = new Intent();
        launch.putExtra(ReaderActivity.EXTRA_FILE_URI, "content://example/text");
        launch.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, 1234);
        launch.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, "before");
        launch.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, "after");
        launch.putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, 4001);
        launch.putExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE, 999L);
        launch.putExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION, true);
        launch.putExtra("unrelated", "preserved");
        File file = new File("/example/book.txt");
        Intent reload = ReaderEncodingController.encodingReloadIntent(launch, file);
        assertEquals(file.getAbsolutePath(), reload.getStringExtra(ReaderActivity.EXTRA_FILE_PATH));
        assertEquals(0, reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
        assertEquals(0, reload.getIntExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, -1));
        assertEquals(0, reload.getIntExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, -1));
        for (String key : new String[] {ReaderActivity.EXTRA_FILE_URI,
                ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER,
                ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE, ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE,
                ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION}) assertFalse(reload.hasExtra(key));
        assertEquals("preserved", reload.getStringExtra("unrelated"));
        assertEquals(1234, launch.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1));
        assertEquals("after", launch.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER));
    }
}
