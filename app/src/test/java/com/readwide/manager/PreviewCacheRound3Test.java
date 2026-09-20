package com.readwide.manager;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import android.content.Context;
import android.content.ContextWrapper;
import com.readwide.manager.archive.ArchiveSourceSnapshot;
import com.readwide.manager.archive.ArchiveSupport;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class PreviewCacheRound3Test {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 private String name(String n){return ArchivePreviewCache.cacheFileNameForEntry(n);}
 private Context context(){return new ContextWrapper(null){@Override public File getCacheDir(){return temp.getRoot();}@Override public Context getApplicationContext(){return this;}};}
 private File part(String name)throws Exception{File f=temp.newFile(name);Files.write(f.toPath(),new byte[]{1,2,3});assertTrue(f.setLastModified(1700000000000L));return f;}
 private void bounded(String n){String result=name(n);assertTrue(result,result.getBytes(StandardCharsets.UTF_8).length<=197);assertEquals(result,new String(result.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8));}
 @Test public void koreanNamesUseUtf8ByteLimit(){bounded("한".repeat(150)+".png");assertTrue(name("한".repeat(150)+".png").endsWith(".png"));}
 @Test public void emojiNamesDoNotSplitSurrogatePairs(){bounded("📚".repeat(100)+".webp");assertTrue(name("📚".repeat(100)+".webp").endsWith(".webp"));}
 @Test public void veryLongExtensionCannotBypassLimit(){bounded("a."+"x".repeat(400));}
 @Test public void malformedSurrogatesAndControlsAreSanitized(){String result=name("a\u0000b\nc\uD800.png");assertFalse(result.contains("\u0000"));assertFalse(result.contains("\n"));bounded("a\uD800.png");}
 @Test public void longCacheFilenameCanActuallyBeCreated()throws Exception {
  File f=new File(temp.getRoot(),name("한".repeat(120)+".png"));assertTrue(f.createNewFile());
 }
 @Test public void distinctLongEntryPathsRetainDistinctHashes(){assertNotEquals(name("a/"+"x".repeat(300)+".png"),name("b/"+"x".repeat(300)+".png"));}
 @Test public void allNumericPartsMapToSameCacheDirectory()throws Exception {
  File a=part("book.zip.001"),b=part("book.zip.002");Context ctx=context();
  assertEquals(ArchivePreviewCache.outputFileForEntry(ctx,a,"p.png"),ArchivePreviewCache.outputFileForEntry(ctx,b,"p.png"));
 }
 @Test public void laterVolumeChangeMovesPreviewNamespace()throws Exception {
  File a=part("book.7z.001"),b=part("book.7z.002");File before=ArchivePreviewCache.outputFileForEntry(context(),a,"p.png");
  Files.write(b.toPath(),new byte[]{4,5,6,7});File after=ArchivePreviewCache.outputFileForEntry(context(),a,"p.png");assertNotEquals(before,after);
 }
 @Test public void snapshotOverloadAvoidsPerPageDirectoryScans()throws Exception {
  File real=part("book.zip.001");part("book.zip.002");int[] scans={0};
  File parent=new File(real.getParent()){@Override public File[] listFiles(){scans[0]++;return super.listFiles();}};
  File source=new File(real.getPath()){@Override public File getAbsoluteFile(){return this;}@Override public File getParentFile(){return parent;}};
  ArchiveSourceSnapshot snapshot=ArchiveSourceSnapshot.capture(source);assertEquals(1,scans[0]);
  for(int i=0;i<500;i++)ArchivePreviewCache.outputFileForEntry(context(),snapshot,"pages/"+i+".png",false);
  assertEquals(1,scans[0]);
 }
 @Test public void lazySequenceUsesOneCaptureAndOneHandoffValidation()throws Exception {
  File real=part("book.zip.001");part("book.zip.002");int[] scans={0};
  File parent=new File(real.getParent()){@Override public File[] listFiles(){scans[0]++;return super.listFiles();}};
  File source=new File(real.getPath()){@Override public File getAbsoluteFile(){return this;}@Override public File getParentFile(){return parent;}};
  List<ArchiveSupport.EntryInfo> entries=new ArrayList<>();for(int i=0;i<200;i++)entries.add(new ArchiveSupport.EntryInfo("p"+i+".png",false,3,0));
  ArchiveImageSequenceLoader.Result result=ArchiveImageSequenceLoader.loadLazy(context(),source,entries,-1);
  assertEquals(200,result.imagePaths.size());assertEquals(2,scans[0]);
 }
 @Test public void sensitiveAndOrdinaryCachesStaySeparate()throws Exception {
  File source=part("book.zip");ArchiveSourceSnapshot snapshot=ArchiveSourceSnapshot.capture(source);
  File normal=ArchivePreviewCache.outputFileForEntry(context(),snapshot,"p.png",false),sensitive=ArchivePreviewCache.outputFileForEntry(context(),snapshot,"p.png",true);
  assertNotEquals(normal,sensitive);assertEquals(normal.getName(),sensitive.getName());
 }
 @Test public void bulkCompletionHintsAreBounded()throws Exception {
  Field field=ArchiveImageEntryCache.class.getDeclaredField("WHOLE_ARCHIVE_BULK_DONE");field.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Boolean> map=(Map<String,Boolean>)field.get(null);
  synchronized(map){map.clear();try{for(int i=0;i<200;i++)map.put("namespace"+i,true);assertEquals(64,map.size());assertNull(map.get("namespace0"));assertEquals(Boolean.TRUE,map.get("namespace199"));}finally{map.clear();}}
 }
 @Test public void archiveLockIsSharedByEntriesInOneNamespace()throws Exception {
  Method lock=ArchiveImageEntryCache.class.getDeclaredMethod("lockForArchive",File.class);lock.setAccessible(true);
  assertSame(lock.invoke(null,new File(temp.getRoot(),"cache1/a.png")),lock.invoke(null,new File(temp.getRoot(),"cache1/b.png")));
 }
 @Test public void zeroLengthForwardReadIsNotMistakenForEof()throws Exception {
  ArchiveSupport.ForwardArchiveReader reader=new ArchiveSupport.ForwardArchiveReader(){public ArchiveSupport.ForwardEntry nextEntry(){return null;}public int read(byte[] b){return 0;}public void close(){}};
  try{SequentialArchiveImageReader.drainSkippedEntry(reader,new byte[8],100);fail("zero-progress decoder accepted as EOF");}catch(IOException expected){assertTrue(expected.getMessage().contains("progress"));}
 }
 @Test public void forwardReaderDrainsUntilExplicitEof()throws Exception {
  int[] calls={0};ArchiveSupport.ForwardArchiveReader reader=new ArchiveSupport.ForwardArchiveReader(){public ArchiveSupport.ForwardEntry nextEntry(){return null;}public int read(byte[] b){return ++calls[0]<=3?2:-1;}public void close(){}};
  SequentialArchiveImageReader.drainSkippedEntry(reader,new byte[8],10);assertEquals(4,calls[0]);
 }
 @Test public void emptyAndDotNamesStillProduceSafeFileNames(){for(String n:new String[]{"", ".", "..", "./", "////"})assertTrue(name(n).endsWith("_archive_entry"));}
}
