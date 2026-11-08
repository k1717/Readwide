package com.readwide.manager;

import android.content.Context;
import android.content.res.Resources;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.util.SearchOptions;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReaderTextSearchSubmissionInstrumentedTest {
    @Test public void workerStartupFailureKeepsPositionAndAllowsLaterSearch() throws Exception {
        for (Error failure : new Error[] {new OutOfMemoryError("search allocation"), new StackOverflowError("search stack")}) {
            SearchActivity[] activity = new SearchActivity[1];
            ReaderSearchController[] controller = new ReaderSearchController[1];
            ThreadPoolExecutor[] worker = new ThreadPoolExecutor[1];
            TextView[] status = new TextView[1];
            try {
                main(() -> {
                    activity[0] = new SearchActivity();
                    activity[0].filePath = "/search-startup.txt";
                    activity[0].fileContent = "target target";
                    activity[0].activeSearchQuery = "target";
                    activity[0].activeSearchIndex = 0;
                    activity[0].activeSearchOrdinal = 1;
                    controller[0] = new ReaderSearchController(activity[0]);
                    status[0] = new TextView(activity[0].getApplicationContext());
                    try {
                        Field field = ReaderSearchController.class.getDeclaredField("textSearchExecutor");
                        field.setAccessible(true);
                        worker[0] = (ThreadPoolExecutor) field.get(controller[0]);
                    } catch (Exception e) { throw new AssertionError(e); }
                    worker[0].setThreadFactory(task -> { throw failure; });
                    controller[0].refreshTextSearchStatus("target", status[0]);
                    assertEquals("1 / …", status[0].getText().toString());
                    assertEquals(0, activity[0].activeSearchIndex);
                    worker[0].setThreadFactory(Thread::new);
                    controller[0].refreshTextSearchStatus("target", status[0]);
                });
                worker[0].submit(() -> { }).get(5, TimeUnit.SECONDS);
                main(() -> {
                    assertEquals("1 / 2", status[0].getText().toString());
                    assertEquals(0, activity[0].activeSearchIndex);
                });
            } finally {
                main(() -> {
                    if (controller[0] != null) controller[0].clearTextSearchWork(true);
                    if (activity[0] != null) {
                        activity[0].activityDestroyed = true;
                        activity[0].handler.removeCallbacksAndMessages(null);
                        activity[0].executor.shutdownNow();
                        activity[0].largeTextPartitionExecutor.shutdownNow();
                        activity[0].largeTextSearchExecutor.shutdownNow();
                        activity[0].largeTextSearchCountExecutor.shutdownNow();
                    }
                });
            }
        }
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static final class SearchActivity extends ReaderActivity {
        @Override public Context getApplicationContext() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
        @Override public Resources getResources() { return getApplicationContext().getResources(); }
        @Override SearchOptions currentSearchOptions() { return SearchOptions.literal(); }
    }
}
