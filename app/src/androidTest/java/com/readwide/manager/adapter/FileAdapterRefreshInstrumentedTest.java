package com.readwide.manager.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.R;
import com.readwide.manager.model.FileListItem;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.PrefsManager;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Main-thread binding checks; synthetic rows do not read/write documents or saved history. */
@RunWith(AndroidJUnit4.class)
public class FileAdapterRefreshInstrumentedTest {
    private Context themed() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_TextViewReader);
    }

    private FileListItem item(Context context, String name, long modified) {
        return new FileListItem(new File(context.getCacheDir(), name), false, 100, modified, modified);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @SuppressWarnings("unchecked")
    private Object seedCover(FileAdapter adapter, FileAdapter.ViewHolder holder,
                             FileListItem item, Bitmap bitmap) throws Exception {
        Method keyMethod = FileAdapter.class.getDeclaredMethod("thumbnailKey",
                FileListItem.class, boolean.class, boolean.class);
        keyMethod.setAccessible(true);
        String key = (String) keyMethod.invoke(null, item,
                PrefsManager.getInstance(holder.itemView.getContext()).getShowHiddenFiles(), false);
        Class<?> entryClass = Class.forName(FileAdapter.class.getName() + "$ThumbnailCacheEntry");
        Constructor<?> constructor = entryClass.getDeclaredConstructor(Bitmap.class, boolean.class, String.class);
        constructor.setAccessible(true);
        Object entry = constructor.newInstance(bitmap, false, "test-source");
        ((android.util.LruCache<String, Object>) field(adapter, "thumbnailCache")).put(key, entry);
        Method show = holder.getClass().getDeclaredMethod("showThumbnail", Bitmap.class);
        show.setAccessible(true);
        show.invoke(holder, bitmap);
        return entry;
    }

    private void assertCover(FileAdapter.ViewHolder holder, Bitmap bitmap) {
        assertTrue(holder.icon.getDrawable() instanceof BitmapDrawable);
        assertSame(bitmap, ((BitmapDrawable) holder.icon.getDrawable()).getBitmap());
        assertNull(holder.icon.getImageTintList());
    }

    @Test public void unchangedAndReorderedRecentRowsDoNotInvalidateWholeDataset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themed();
            FileAdapter adapter = new FileAdapter(context);
            try {
                adapter.setSortEnabled(false);
                FileListItem a = item(context, "refresh-a.cbz", 1);
                FileListItem b = item(context, "refresh-b.cbz", 1);
                adapter.setItems(Arrays.asList(a, b));
                int[] resets = {0};
                int[] moves = {0};
                adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                    @Override public void onChanged() { resets[0]++; }
                    @Override public void onItemRangeMoved(int from, int to, int count) { moves[0] += count; }
                });
                adapter.setItems(Arrays.asList(a, b));
                assertEquals(0, moves[0]);
                adapter.setItems(Arrays.asList(b, a));
                assertEquals(0, resets[0]);
                assertTrue(moves[0] > 0);
                assertSame(b, adapter.getItemAt(0));
            } finally { adapter.release(); }
        });
    }

    @Test public void progressAndSelectionPayloadsKeepSameCoverButRefreshBadge() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themed();
            FileAdapter adapter = new FileAdapter(context);
            Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            try {
                adapter.setShowThumbnails(true);
                adapter.setShowReadingProgress(true);
                FileListItem row = item(context, "refresh-progress.cbz", 1);
                adapter.setItems(Collections.singletonList(row));
                FileAdapter.ViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(context), 0);
                adapter.onBindViewHolder(holder, 0);
                seedCover(adapter, holder, row, bitmap);
                List<Object> payloads = new ArrayList<>();
                adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                    @Override public void onItemRangeChanged(int start, int count, Object payload) {
                        assertNotNull(payload);
                        payloads.add(payload);
                    }
                });
                ReaderState state = new ReaderState(row.getAbsolutePath());
                state.setPageNumber(30);
                state.setTotalPages(100);
                adapter.setReadingProgressStates(Collections.singletonList(state));
                adapter.refreshReadingProgress();
                adapter.setSelectionState(true, Collections.singleton(row.getAbsolutePath()));
                adapter.onBindViewHolder(holder, 0, payloads);
                assertCover(holder, bitmap);
                assertEquals(String.format(java.util.Locale.getDefault(), "%d%%", 30), holder.progress.getText().toString());
                assertTrue(holder.itemView.isSelected());
                adapter.onBindViewHolder(holder, 0); // Theme/full rebind must also retain it.
                assertCover(holder, bitmap);
                adapter.onBindViewHolder(holder, 0, Collections.singletonList("unknown_payload"));
                assertCover(holder, bitmap); // Unknown payload takes the safe full-bind path.
            } catch (Exception e) { throw new AssertionError(e); }
            finally { adapter.release(); bitmap.recycle(); }
        });
    }

    @Test public void changedMetadataDisabledPreviewsAndRecyclingClearOldCover() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themed();
            FileAdapter adapter = new FileAdapter(context);
            Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            try {
                adapter.setShowThumbnails(true);
                FileListItem row = item(context, "refresh-changed.cbz", 1);
                adapter.setItems(Collections.singletonList(row));
                FileAdapter.ViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(context), 0);
                adapter.onBindViewHolder(holder, 0);
                seedCover(adapter, holder, row, bitmap);
                adapter.setItems(Collections.singletonList(item(context, "refresh-changed.cbz", 2)));
                adapter.onBindViewHolder(holder, 0);
                assertNotNull(holder.icon.getImageTintList());
                seedCover(adapter, holder, adapter.getItemAt(0), bitmap);
                adapter.setShowThumbnails(false);
                adapter.onBindViewHolder(holder, 0);
                assertNotNull(holder.icon.getImageTintList());
                adapter.onViewRecycled(holder);
                assertNull(holder.icon.getDrawable());
            } catch (Exception e) { throw new AssertionError(e); }
            finally { adapter.release(); bitmap.recycle(); }
        });
    }

    @SuppressWarnings("unchecked")
    @Test public void rejectedArchiveIdentityClearsRetainedCoverEvenAfterRequestRetires() {
        FileAdapter[] adapter = new FileAdapter[1];
        FileAdapter.ViewHolder[] holder = new FileAdapter.ViewHolder[1];
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Context context = themed();
                adapter[0] = new FileAdapter(context);
                try {
                    adapter[0].setShowThumbnails(true);
                    FileListItem row = item(context, "refresh-rejected.cbz", 1);
                    adapter[0].setItems(Collections.singletonList(row));
                    holder[0] = adapter[0].onCreateViewHolder(new FrameLayout(context), 0);
                    adapter[0].onBindViewHolder(holder[0], 0);
                    Object entry = seedCover(adapter[0], holder[0], row, bitmap);
                    String id = (String) field(holder[0], "thumbnailId");
                    String key = id.substring(id.indexOf('|') + 1);
                    int generation = ((java.util.concurrent.atomic.AtomicInteger)
                            field(adapter[0], "thumbnailGeneration")).get();
                    Field attached = holder[0].getClass().getDeclaredField("thumbnailAttached");
                    attached.setAccessible(true);
                    attached.setBoolean(holder[0], true);
                    ((com.readwide.manager.util.VisibleThumbnailBindings<FileAdapter.ViewHolder>)
                            field(adapter[0], "thumbnailBindings")).bind(holder[0], id);
                    Class<?> requestClass = Class.forName(FileAdapter.class.getName() + "$ThumbnailRequest");
                    Constructor<?> constructor = requestClass.getDeclaredConstructor(FileAdapter.class,
                            String.class, String.class, int.class, FileListItem.class, boolean.class, int.class);
                    constructor.setAccessible(true);
                    Object request = constructor.newInstance(adapter[0], id, key, generation, row, false, 192);
                    Method discard = requestClass.getDeclaredMethod("discardCachedThumbnail", entry.getClass());
                    discard.setAccessible(true);
                    // No worker is started. The request need not remain in the
                    // active map for its queued invalidation to clear its old cover.
                    discard.invoke(request, entry);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    assertNotNull(holder[0].icon.getImageTintList()));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (adapter[0] != null) adapter[0].release();
                bitmap.recycle();
            });
        }
    }
}
