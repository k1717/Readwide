package com.readwide.manager.adapter;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.R;
import com.readwide.manager.model.Bookmark;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Adapter positions only; synthetic bookmarks never read or change saved documents. */
@RunWith(AndroidJUnit4.class)
public class BookmarkAdapterPositionInstrumentedTest {
    @Test public void clickEditAndDeleteUseBindingRelativePosition() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try {
                RecyclerView.ViewHolder holder = f.bookmarkHolder();
                assertEquals(2, holder.getAbsoluteAdapterPosition());
                assertEquals(1, holder.getBindingAdapterPosition());
                dispatchActions(holder);
                assertEquals(Arrays.asList("click", "edit", "delete"), f.events);
                assertEquals(3, f.bookmarks.size());
                for (Bookmark bookmark : f.bookmarks) assertSame(f.bookmark, bookmark);
            } finally {
                f.recycler.setAdapter(null);
            }
        });
    }

    @Test public void pendingDatasetResetIgnoresOldHolderActionsUntilRebind() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try {
                RecyclerView.ViewHolder oldHolder = f.bookmarkHolder();
                Bookmark replacement = new Bookmark("/synthetic/new.txt", "new.txt", 25, 2, "new");
                f.adapter.setBookmarks(Collections.singletonList(replacement));
                assertEquals(RecyclerView.NO_POSITION, oldHolder.getBindingAdapterPosition());
                dispatchActions(oldHolder);
                assertTrue(f.events.isEmpty());
                assertTrue(f.bookmarks.isEmpty());

                f.layout();
                dispatchActions(f.bookmarkHolder());
                assertEquals(Arrays.asList("click", "edit", "delete"), f.events);
                assertEquals(3, f.bookmarks.size());
                for (Bookmark bookmark : f.bookmarks) assertSame(replacement, bookmark);
            } finally {
                f.recycler.setAdapter(null);
            }
        });
    }

    @Test public void unboundHolderActionsAreIgnored() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Fixture f = new Fixture();
            try {
                RecyclerView.ViewHolder holder = f.adapter.createViewHolder(
                        f.recycler, f.adapter.getItemViewType(1));
                assertEquals(RecyclerView.NO_POSITION, holder.getBindingAdapterPosition());
                dispatchActions(holder);
                assertTrue(f.events.isEmpty());
                assertTrue(f.bookmarks.isEmpty());
            } finally {
                f.recycler.setAdapter(null);
            }
        });
    }

    private static void dispatchActions(RecyclerView.ViewHolder holder) {
        holder.itemView.performClick();
        holder.itemView.performLongClick();
        holder.itemView.findViewById(R.id.bookmark_delete).performClick();
    }

    private static final class Fixture {
        final RecyclerView recycler;
        final BookmarkAdapter adapter = new BookmarkAdapter();
        final Bookmark bookmark = new Bookmark("/synthetic/book.txt", "book.txt", 12, 1, "sample");
        final List<String> events = new ArrayList<>();
        final List<Bookmark> bookmarks = new ArrayList<>();

        Fixture() {
            Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.Theme_TextViewReader);
            recycler = new RecyclerView(context);
            recycler.setLayoutManager(new LinearLayoutManager(context));
            recycler.setItemAnimator(null);
            adapter.setBookmarks(Collections.singletonList(bookmark));
            adapter.setListener(new BookmarkAdapter.OnBookmarkClickListener() {
                @Override public void onBookmarkClick(Bookmark bookmark) { record("click", bookmark); }
                @Override public void onBookmarkEdit(Bookmark bookmark) { record("edit", bookmark); }
                @Override public void onBookmarkDelete(Bookmark bookmark) { record("delete", bookmark); }
                private void record(String event, Bookmark bookmark) {
                    events.add(event);
                    bookmarks.add(bookmark);
                }
            });
            recycler.setAdapter(new ConcatAdapter(new PrefixAdapter(), adapter));
            layout();
        }

        void layout() {
            recycler.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY));
            recycler.layout(0, 0, 1200, 4000);
        }

        RecyclerView.ViewHolder bookmarkHolder() {
            RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(2);
            assertNotNull(holder);
            assertSame(adapter, holder.getBindingAdapter());
            return holder;
        }
    }

    private static final class PrefixAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 32));
            return new RecyclerView.ViewHolder(view) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
        @Override public int getItemCount() { return 1; }
    }
}
