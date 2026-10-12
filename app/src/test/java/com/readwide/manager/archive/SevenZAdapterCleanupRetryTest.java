package com.readwide.manager.archive;

import java.io.*;
import java.lang.reflect.Constructor;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Failure injection at the owned 7z adapter's delegate/directory boundary. */
public final class SevenZAdapterCleanupRetryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final class Delegate implements ArchiveSupport.ForwardArchiveReader {
        int closes;
        Throwable closeFailure;
        IOException readFailure;
        @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException { return null; }
        @Override public int read(byte[] bytes) throws IOException { if(readFailure!=null)throw readFailure; return -1; }
        @Override public boolean drainCurrentEntry(long maximum) { return true; }
        @Override public boolean skipsUnreadEntryOnAdvance() { return true; }
        @Override public void close() throws IOException {
            closes++;
            if(closes==1 && closeFailure!=null) {
                if(closeFailure instanceof IOException)throw (IOException)closeFailure;
                if(closeFailure instanceof RuntimeException)throw (RuntimeException)closeFailure;
                throw (Error)closeFailure;
            }
        }
    }
    private static final class Directory extends File {
        int attempts;
        final boolean security;
        Directory(File file,boolean security){super(file.getPath());this.security=security;}
        @Override public boolean delete(){
            if(++attempts==1){if(security)throw new SecurityException("temporary denial");return false;}
            return super.delete();
        }
    }
    private ArchiveSupport.ForwardArchiveReader adapter(Delegate delegate,File directory)throws Exception{
        Constructor<SevenZNativeForwardAdapter> constructor=SevenZNativeForwardAdapter.class.getDeclaredConstructor(ArchiveSupport.ForwardArchiveReader.class,File.class);
        constructor.setAccessible(true);return constructor.newInstance(delegate,directory);
    }
    @Test public void retriesDirectoryDeletionWithoutReclosingSuccessfulDelegate()throws Exception{directory(false);}
    @Test public void retriesSecurityFailureWithoutReclosingSuccessfulDelegate()throws Exception{directory(true);}
    private void directory(boolean security)throws Exception{
        Delegate delegate=new Delegate();Directory directory=new Directory(temporary.newFolder(),security);
        ArchiveSupport.ForwardArchiveReader reader=adapter(delegate,directory);
        if(security)assertThrows(SecurityException.class,reader::close);
        else assertThrows(IOException.class,reader::close);
        assertTrue(directory.exists());reader.close();assertFalse(directory.exists());reader.close();
        assertEquals(2,directory.attempts);assertEquals(1,delegate.closes);
    }
    @Test public void retriesDelegateCleanupAfterCheckedFailure()throws Exception{delegate(new IOException("close"));}
    @Test public void retriesDelegateCleanupAfterError()throws Exception{delegate(new AssertionError("close"));}
    private void delegate(Throwable failure)throws Exception{
        Delegate delegate=new Delegate();delegate.closeFailure=failure;
        File directory=temporary.newFolder();ArchiveSupport.ForwardArchiveReader reader=adapter(delegate,directory);
        Throwable caught=null;try{reader.close();}catch(Throwable e){caught=e;}assertSame(failure,caught);
        assertFalse(directory.exists());reader.close();reader.close();assertEquals(2,delegate.closes);
    }
    @Test public void readFailureRemainsPrimaryAndExplicitCloseRetriesBothResources()throws Exception{
        Delegate delegate=new Delegate();delegate.closeFailure=new IOException("close");delegate.readFailure=new IOException("read");
        Directory directory=new Directory(temporary.newFolder(),false);ArchiveSupport.ForwardArchiveReader reader=adapter(delegate,directory);
        assertSame(delegate.readFailure,assertThrows(IOException.class,()->reader.read(new byte[1])));
        assertSame(delegate.closeFailure,delegate.readFailure.getSuppressed()[0]);
        assertEquals(1,delegate.closeFailure.getSuppressed().length);
        assertEquals(-1,reader.read(new byte[1]));assertNull(reader.nextEntry());
        reader.close();assertFalse(directory.exists());assertEquals(2,delegate.closes);reader.close();assertEquals(2,delegate.closes);
    }
    @Test public void eofCleanupFailureCanBeRetriedByCaller()throws Exception{
        Delegate delegate=new Delegate();Directory directory=new Directory(temporary.newFolder(),false);
        ArchiveSupport.ForwardArchiveReader reader=adapter(delegate,directory);
        assertThrows(IOException.class,reader::nextEntry);reader.close();assertFalse(directory.exists());assertEquals(1,delegate.closes);
    }
}
