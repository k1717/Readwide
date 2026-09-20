package com.readwide.manager.archive;
import org.junit.Test;
import java.io.*;import java.lang.reflect.Field;import java.nio.file.*;
import static org.junit.Assert.*;
public class Rar5HistoryCleanupReviewTest {
    @Test public void failedDeletionCanBeRetriedWithoutReopeningHistory() throws Exception {
        Path root=Files.createTempDirectory("rar-cleanup-review-");Path spool=Files.createDirectory(root.resolve("spool"));Path blocker=Files.write(spool.resolve("blocker"),new byte[]{1});
        Rar5HistoryStore history=new Rar5HistoryStore(65536,2,root.toFile(),ignored->Long.MAX_VALUE);
        Field field=Rar5HistoryStore.class.getDeclaredField("spool");field.setAccessible(true);field.set(history,spool.toFile());
        try{
            try{history.close();fail("Nonempty directory should simulate a failed deletion");}catch(IOException expected){}
            assertTrue(Files.exists(spool));Files.delete(blocker);history.close();assertFalse(Files.exists(spool));history.close();
            try{history.append((byte)1);fail("Closed store must remain closed");}catch(IOException expected){}
        }finally{Files.deleteIfExists(blocker);Files.deleteIfExists(spool);Files.deleteIfExists(root);}
    }
}
