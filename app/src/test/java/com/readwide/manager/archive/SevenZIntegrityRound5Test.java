package com.readwide.manager.archive;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class SevenZIntegrityRound5Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private InputStream exact(InputStream input,long size)throws Exception{
        Constructor<?> ctor=Class.forName(SevenZBcj2ArchiveReader.class.getName()+"$ExactStream").getDeclaredConstructor(InputStream.class,long.class);
        ctor.setAccessible(true);return(InputStream)ctor.newInstance(input,size);
    }
    private InputStream integrity(InputStream input,long size,long crc,long[] sizes,long[] crcs)throws Exception{
        Constructor<?> ctor=Class.forName(SevenZBcj2ArchiveReader.class.getName()+"$IntegrityStream").getDeclaredConstructor(InputStream.class,long.class,long.class,long[].class,long[].class);
        ctor.setAccessible(true);return(InputStream)ctor.newInstance(input,size,crc,sizes,crcs);
    }
    private InputStream integrity(InputStream input,long size,long crc)throws Exception{return integrity(input,size,crc,new long[0],new long[0]);}
    private void sticky(InputStream stream)throws Exception{
        IOException first=assertThrows(IOException.class,()->stream.read());
        assertSame(first,assertThrows(IOException.class,()->stream.read()));
        assertSame(first,assertThrows(IOException.class,()->stream.read(new byte[3])));
    }
    @Test public void exactOverflowFailureCannotBecomeEofOnRetry()throws Exception{
        try(InputStream s=exact(new ByteArrayInputStream(new byte[]{1,2}),1)){assertEquals(1,s.read());sticky(s);}
    }
    @Test public void integrityOverflowFailureCannotBecomeEofOnRetry()throws Exception{
        try(InputStream s=integrity(new ByteArrayInputStream(new byte[]{1,2}),1,-1)){assertEquals(1,s.read());sticky(s);}
    }
    @Test public void exactTruncationRemainsTerminal()throws Exception{try(InputStream s=exact(new ByteArrayInputStream(new byte[0]),1)){sticky(s);}}
    @Test public void integrityTruncationRemainsTerminal()throws Exception{try(InputStream s=integrity(new ByteArrayInputStream(new byte[0]),1,-1)){sticky(s);}}
    private InputStream stallsOnce(){return new InputStream(){boolean first=true;public int read(){return 9;}public int read(byte[] b,int o,int n){if(first){first=false;return 0;}b[o]=9;return 1;}};}
    @Test public void exactZeroProgressDoesNotResumeAfterFailure()throws Exception{try(InputStream s=exact(stallsOnce(),1)){sticky(s);}}
    @Test public void integrityZeroProgressDoesNotResumeAfterFailure()throws Exception{try(InputStream s=integrity(stallsOnce(),1,-1)){sticky(s);}}
    @Test public void exactCloseIsIdempotentAndDisablesReads()throws Exception{
        int[] closes={0};InputStream source=new ByteArrayInputStream(new byte[]{1}){public void close(){closes[0]++;}};
        InputStream s=exact(source,1);s.close();s.close();assertEquals(1,closes[0]);assertThrows(IOException.class,()->s.read());
    }
    @Test public void integrityCloseIsIdempotentAndDisablesReads()throws Exception{
        int[] closes={0};InputStream source=new ByteArrayInputStream(new byte[]{1}){public void close(){closes[0]++;}};
        InputStream s=integrity(source,1,-1);s.close();s.close();assertEquals(1,closes[0]);assertThrows(IOException.class,()->s.read());
    }
    @Test public void exactCancelledFailureStaysTerminalAfterFlagCleared()throws Exception{
        try(InputStream s=exact(new ByteArrayInputStream(new byte[]{1}),1)){
            IOException first;try{Thread.currentThread().interrupt();first=assertThrows(InterruptedIOException.class,()->s.read());}finally{Thread.interrupted();}
            assertSame(first,assertThrows(IOException.class,()->s.read()));
        }
    }
    @Test public void integrityCancelledFailureStaysTerminalAfterFlagCleared()throws Exception{
        try(InputStream s=integrity(new ByteArrayInputStream(new byte[]{1}),1,-1)){
            IOException first;try{Thread.currentThread().interrupt();first=assertThrows(InterruptedIOException.class,()->s.read());}finally{Thread.interrupted();}
            assertSame(first,assertThrows(IOException.class,()->s.read()));
        }
    }
    @Test public void exactInvalidArgumentsDoNotConsumeOrPoisonInput()throws Exception{
        try(InputStream s=exact(new ByteArrayInputStream(new byte[]{3}),1)){
            assertThrows(IndexOutOfBoundsException.class,()->s.read(new byte[1],0,2));assertThrows(NullPointerException.class,()->s.read(null,0,1));
            assertEquals(0,s.read(new byte[1],1,0));assertEquals(3,s.read());assertEquals(-1,s.read());
        }
    }
    @Test public void integrityInvalidArgumentsDoNotConsumeOrPoisonInput()throws Exception{
        try(InputStream s=integrity(new ByteArrayInputStream(new byte[]{3}),1,-1)){
            assertThrows(IndexOutOfBoundsException.class,()->s.read(new byte[1],0,2));assertThrows(NullPointerException.class,()->s.read(null,0,1));
            assertEquals(0,s.read(new byte[1],1,0));assertEquals(3,s.read());assertEquals(-1,s.read());
        }
    }
    @Test public void integrityChecksZeroLengthSubstreams()throws Exception{
        try(InputStream s=integrity(new ByteArrayInputStream(new byte[]{1}),1,-1,new long[]{0,1},new long[]{1,-1})){sticky(s);}
    }
    @Test public void integrityTotalCrcFailureRemainsTerminal()throws Exception{
        try(InputStream s=integrity(new ByteArrayInputStream(new byte[]{1}),1,1)){sticky(s);}
    }
    @Test public void validSlicesAndEmptySlicesVerifyAfterSkip()throws Exception{
        byte[] bytes={1,2,3};long first=Round4Fixtures.crc(new byte[]{1});long rest=Round4Fixtures.crc(new byte[]{2,3});
        try(InputStream s=integrity(new ByteArrayInputStream(bytes),3,Round4Fixtures.crc(bytes),new long[]{0,1,0,2,0},new long[]{0,first,0,rest,0})){
            assertEquals(3,s.skip(8));assertEquals(-1,s.read());
        }
    }
    private File fixture(boolean bad,byte[] data)throws Exception{return Round4Fixtures.sevenZ(temp.newFolder(),data,4,false,false,bad,true);}
    @Test public void badFolderCrcRestoresBothExistingFiles()throws Exception{
        File file=fixture(true,Round4Fixtures.data(100)),dir=temp.newFolder();byte[] old={7,8,9};
        for(String n:new String[]{"1.bin","2.bin"})Files.write(new File(dir,n).toPath(),old);
        assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(file,dir,null,null,null));
        for(String n:new String[]{"1.bin","2.bin"})assertArrayEquals(old,Files.readAllBytes(new File(dir,n).toPath()));assertEquals(2,dir.list().length);
    }
    @Test public void badFolderCrcRemovesEveryNewOutput()throws Exception{
        File file=fixture(true,Round4Fixtures.data(100)),dir=temp.newFolder();
        assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(file,dir,null,null,null));assertEquals(0,dir.list().length);
    }
    @Test public void successfulFolderCommitsBothFiles()throws Exception{
        byte[] data=Round4Fixtures.data(101);File file=fixture(false,data),dir=temp.newFolder();
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(file,dir,null,null,null));
        assertArrayEquals(Arrays.copyOfRange(data,0,50),Files.readAllBytes(new File(dir,"1.bin").toPath()));
        assertArrayEquals(Arrays.copyOfRange(data,50,101),Files.readAllBytes(new File(dir,"2.bin").toPath()));assertEquals(2,dir.list().length);
    }
    @Test public void zeroSizedFolderCanContainTwoEmptyEntries()throws Exception{
        File file=fixture(false,new byte[0]),dir=temp.newFolder();assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(file,dir,null,null,null));
        assertTrue(new File(dir,"1.bin").isFile());assertTrue(new File(dir,"2.bin").isFile());assertEquals(2,dir.list().length);
    }

    private Object folder(InputStream input,List<InputStream> owned,long size)throws Exception{
        Class<?> c=Class.forName(SevenZBcj2ArchiveReader.class.getName()+"$FolderStream");
        Constructor<?> ctor=c.getDeclaredConstructor(InputStream.class,long.class,List.class,List.class,com.readwide.manager.util.FileOperationProgress.class);
        ctor.setAccessible(true);return ctor.newInstance(input,size,owned,Collections.emptyList(),null);
    }
    private void write(Object folder,long offset,long size,File out,boolean finish)throws Exception{
        Method m=folder.getClass().getDeclaredMethod("writeEntry",long.class,long.class,File.class,boolean.class);m.setAccessible(true);
        try{m.invoke(folder,offset,size,out,finish);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    @Test public void decoderCloseFailureRollsBackAlreadyVerifiedFolder()throws Exception{
        InputStream source=new ByteArrayInputStream(new byte[]{1,2}){public void close()throws IOException{throw new IOException("injected decoder close failure");}};
        File dir=temp.newFolder(),out=new File(dir,"target");byte[] old={8,9};Files.write(out.toPath(),old);
        Object folder=folder(source,Collections.singletonList(source),2);write(folder,0,2,out,true);
        assertThrows(IOException.class,()->((Closeable)folder).close());assertArrayEquals(old,Files.readAllBytes(out.toPath()));assertEquals(1,dir.list().length);
    }
    @Test public void closingUndrainedFolderRollsBackEveryPendingOutput()throws Exception{
        InputStream source=new ByteArrayInputStream(new byte[]{1,2,3,4});File dir=temp.newFolder();Object folder=folder(source,Collections.singletonList(source),4);
        write(folder,0,1,new File(dir,"first"),false);write(folder,1,1,new File(dir,"second"),false);
        ((Closeable)folder).close();assertEquals(0,dir.list().length);
    }
    @Test public void repeatedSuccessfulFolderCloseDoesNotDeleteCommittedOutput()throws Exception{
        InputStream source=new ByteArrayInputStream(new byte[]{1});File dir=temp.newFolder(),out=new File(dir,"target");Object folder=folder(source,Collections.singletonList(source),1);
        write(folder,0,1,out,true);((Closeable)folder).close();((Closeable)folder).close();assertArrayEquals(new byte[]{1},Files.readAllBytes(out.toPath()));assertEquals(1,dir.list().length);
    }
}
