package com.readwide.manager.archive;

import java.io.*;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Existing valid 7z fixture, with failures injected only after spool verification. */
public final class SevenZForwardCleanupFailureTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    @Test public void checkedCloseStillDeletesSpool()throws Exception{check(new IOException("close"),null,false);}
    @Test public void runtimeCloseStillDeletesSpool()throws Exception{check(new IllegalStateException("close"),null,false);}
    @Test public void errorCloseStillDeletesSpool()throws Exception{check(new AssertionError("close"),null,false);}
    @Test public void preparationFailureSurvivesCleanupError()throws Exception{check(new AssertionError("close"),new IOException("length"),false);}
    @Test public void repeatedSameFailureDoesNotSelfSuppress()throws Exception{IOException e=new IOException("shared");check(e,e,false);}
    @Test public void deletionErrorDoesNotMaskCloseAndCanBeRetried()throws Exception{check(new IOException("close"),null,true);}
    private void check(Throwable closeFailure,IOException lengthFailure,boolean deleteError)throws Exception{
        File archive=Round4Fixtures.sevenZ(temporary.newFolder(),Round4Fixtures.data(103),4,false,false,false,false);
        File directory=temporary.newFolder();
        ArchiveSupport.ForwardArchiveReader reader=SevenZBcj2ArchiveReader.openSpecialForwardReader(archive,null,directory);
        assertNotNull(reader);
        Field inputField=reader.getClass().getDeclaredField("input");inputField.setAccessible(true);
        Field spoolField=reader.getClass().getDeclaredField("spool");spoolField.setAccessible(true);
        File spool=null;
        try{
            assertNotNull(reader.nextEntry());assertEquals(1,reader.read(new byte[1]));
            spool=(File)spoolField.get(reader);
            ((RandomAccessFile)inputField.get(reader)).close();
            final int[] closes={0};
            inputField.set(reader,new RandomAccessFile(spool,"r"){
                private boolean closeStarted;
                @Override public long length()throws IOException{if(lengthFailure!=null)throw lengthFailure;return super.length();}
                @Override public void close()throws IOException{
                    // RandomAccessFile's descriptor may call its parent close recursively.
                    if(closeStarted)return;
                    closeStarted=true;
                    closes[0]++;super.close();
                    if(closeFailure instanceof IOException)throw (IOException)closeFailure;
                    if(closeFailure instanceof RuntimeException)throw (RuntimeException)closeFailure;
                    throw (Error)closeFailure;
                }
            });
            if(deleteError)spoolField.set(reader,new File(spool.getPath()){
                @Override public boolean delete(){throw new AssertionError("delete failure");}
            });
            Throwable caught=null;try{if(lengthFailure==null)reader.close();else reader.read(new byte[1]);}catch(Throwable e){caught=e;}
            assertSame(lengthFailure==null?closeFailure:lengthFailure,caught);assertEquals(1,closes[0]);
            if(deleteError){assertEquals(1,caught.getSuppressed().length);assertTrue(spool.exists());spoolField.set(reader,spool);reader.close();}
            assertArrayEquals(new String[0],directory.list());reader.close();reader.close();assertEquals(1,closes[0]);
            assertThrows(IOException.class,()->reader.read(new byte[1]));assertThrows(IOException.class,reader::nextEntry);
        }finally{
            if(spool!=null)spoolField.set(reader,spool);
            reader.close();
        }
    }
}
