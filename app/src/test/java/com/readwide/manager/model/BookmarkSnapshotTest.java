package com.readwide.manager.model;

import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import static org.junit.Assert.*;

public class BookmarkSnapshotTest {
    @Test public void copyPreservesEveryInstanceFieldAndOwnsItsMutations() throws Exception {
        Bookmark source = new Bookmark();
        int index = 1;
        for (Field field : Bookmark.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            if (field.getType() == String.class) field.set(source, "field-" + index);
            else if (field.getType() == int.class) field.setInt(source, index);
            else if (field.getType() == long.class) field.setLong(source, 10000000000L + index);
            else if (field.getType() == boolean.class) field.setBoolean(source, true);
            else fail("Add snapshot coverage for " + field.getName());
            index++;
        }
        Bookmark snapshot = source.copy();
        assertNotSame(source, snapshot);
        for (Field field : Bookmark.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            assertEquals(field.getName(), field.get(source), field.get(snapshot));
        }
        snapshot.setFilePath("changed");
        snapshot.setPageNumber(0);
        assertNotEquals(source.getFilePath(), snapshot.getFilePath());
        assertNotEquals(source.getPageNumber(), snapshot.getPageNumber());
    }
}
