package com.readwide.manager.adapter;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.model.DrawerEntry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DrawerEntryAdapterInstrumentedTest {
    @Test public void equalValueListDoesNotRebindRows() {
        check(fixture -> {
            fixture.adapter.setEntries(Collections.singletonList(entry(1, "Books", "SD card", "/books")));
            fixture.events.clear();
            fixture.adapter.setEntries(Collections.singletonList(entry(1, "Books", "SD card", "/books")));
            assertEquals(1, fixture.adapter.getItemCount());
            assertTrue(fixture.events.isEmpty());
        });
    }

    @Test public void changedSubtitleAndIconStillUpdateTheExistingRow() {
        check(fixture -> {
            fixture.adapter.setEntries(Collections.singletonList(entry(1, "Books", "Old", "/books")));
            fixture.events.clear();
            fixture.adapter.setEntries(Collections.singletonList(entry(2, "Books", "New", "/books")));
            assertEquals(1, fixture.events.size());
            assertEquals("change:0:1", fixture.events.get(0).kind);
        });
    }

    @Test public void addingAndRemovingRowsStillDispatchesDiffUpdates() {
        check(fixture -> {
            DrawerEntry books = entry(1, "Books", null, "/books");
            DrawerEntry comics = entry(2, "Comics", null, "/comics");
            fixture.adapter.setEntries(Collections.singletonList(books));
            fixture.events.clear();
            fixture.adapter.setEntries(Arrays.asList(books, comics));
            assertEquals(2, fixture.adapter.getItemCount());
            assertEquals(1, fixture.events.size());
            assertEquals("insert:1:1", fixture.events.get(0).kind);
            fixture.events.clear();
            fixture.adapter.setEntries(Collections.singletonList(comics));
            assertEquals(1, fixture.adapter.getItemCount());
            assertEquals(1, fixture.events.size());
            assertEquals("remove:0:1", fixture.events.get(0).kind);
        });
    }

    @Test public void explicitThemeRefreshUsesANonNullPayloadAfterAnEqualList() {
        check(fixture -> {
            fixture.adapter.setEntries(Collections.singletonList(entry(1, "Books", null, "/books")));
            fixture.events.clear();
            fixture.adapter.setEntries(Collections.singletonList(entry(1, "Books", null, "/books")));
            fixture.adapter.refreshTheme();
            assertEquals(1, fixture.events.size());
            assertEquals("change:0:1", fixture.events.get(0).kind);
            assertNotNull(fixture.events.get(0).payload);
        });
    }

    @Test public void emptyThemeRefreshDoesNotNotifyObservers() {
        check(fixture -> {
            fixture.adapter.refreshTheme();
            fixture.adapter.setEntries(Collections.emptyList());
            fixture.adapter.setUseShortcutBoxColor(true);
            assertTrue(fixture.events.isEmpty());
        });
    }

    @Test public void shortcutColorModeRefreshesOnlyWhenTheModeChanges() {
        check(fixture -> {
            fixture.adapter.setEntries(Collections.singletonList(entry(1, "Books", null, "/books")));
            fixture.events.clear();
            fixture.adapter.setUseShortcutBoxColor(false);
            assertTrue(fixture.events.isEmpty());
            fixture.adapter.setUseShortcutBoxColor(true);
            assertEquals(1, fixture.events.size());
            assertNotNull(fixture.events.get(0).payload);
            fixture.events.clear();
            fixture.adapter.setUseShortcutBoxColor(true);
            assertTrue(fixture.events.isEmpty());
        });
    }

    @Test public void themePayloadStillReachesTheFullBindingMethod() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RecordingAdapter adapter = new RecordingAdapter();
            adapter.onBindViewHolder(null, 3, Collections.singletonList(new Object()));
            assertEquals(1, adapter.bindCalls);
            assertEquals(3, adapter.boundPosition);
        });
    }

    @Test public void inputListChangesDoNotMutateTheAdapterSnapshot() {
        check(fixture -> {
            List<DrawerEntry> entries = new ArrayList<>();
            entries.add(entry(1, "Books", null, "/books"));
            fixture.adapter.setEntries(entries);
            fixture.events.clear();
            entries.clear();
            assertEquals(1, fixture.adapter.getItemCount());
            assertTrue(fixture.events.isEmpty());
            fixture.adapter.setEntries(entries);
            assertEquals(0, fixture.adapter.getItemCount());
            assertEquals("remove:0:1", fixture.events.get(0).kind);
        });
    }

    private static DrawerEntry entry(int icon, String title, String subtitle, String path) {
        return new DrawerEntry(DrawerEntry.ACTION_FOLDER_SHORTCUT, icon, title, subtitle, path);
    }

    private static void check(Consumer<Fixture> assertion) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertion.accept(new Fixture()));
    }

    private static final class Event {
        final String kind;
        final Object payload;

        Event(String kind, Object payload) {
            this.kind = kind;
            this.payload = payload;
        }
    }

    private static final class Fixture {
        final DrawerEntryAdapter adapter = new DrawerEntryAdapter();
        final List<Event> events = new ArrayList<>();

        Fixture() {
            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { events.add(new Event("all", null)); }
                @Override public void onItemRangeChanged(int start, int count, Object payload) {
                    events.add(new Event("change:" + start + ":" + count, payload));
                }
                @Override public void onItemRangeInserted(int start, int count) {
                    events.add(new Event("insert:" + start + ":" + count, null));
                }
                @Override public void onItemRangeRemoved(int start, int count) {
                    events.add(new Event("remove:" + start + ":" + count, null));
                }
                @Override public void onItemRangeMoved(int from, int to, int count) {
                    events.add(new Event("move:" + from + ":" + to + ":" + count, null));
                }
            });
        }
    }

    private static final class RecordingAdapter extends DrawerEntryAdapter {
        int bindCalls;
        int boundPosition;

        @Override public void onBindViewHolder(ViewHolder holder, int position) {
            bindCalls++;
            boundPosition = position;
        }
    }
}
