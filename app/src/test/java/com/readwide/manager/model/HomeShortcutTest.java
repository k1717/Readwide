package com.readwide.manager.model;
import org.junit.*;import org.junit.rules.TemporaryFolder;import java.io.*;import java.util.*;
import static org.junit.Assert.*;
public class HomeShortcutTest {
 @Rule public TemporaryFolder temp = new TemporaryFolder();
 private List<HomeShortcut> load(String...paths)throws Exception{return HomeShortcut.load(Arrays.asList(paths),p->HomeShortcut.Status.AVAILABLE);}
 @Test public void emptyAndNullInputAreEmpty()throws Exception {assertTrue(load().isEmpty());assertTrue(HomeShortcut.load(null,p->null).isEmpty());}
 @Test public void preservesSavedOrder()throws Exception {List<HomeShortcut>s=load("/b","/a");assertEquals("/b",s.get(0).path);assertEquals("/a",s.get(1).path);}
 @Test public void deduplicatesExactPathsOnly()throws Exception {assertEquals(2,load("/A","/A","/a").size());}
 @Test public void ignoresNullAndBlankValues()throws Exception {assertEquals(1,load(null,"","  ","/ok").size());}
 @Test public void doesNotTrimOrCanonicalizeSavedLocator()throws Exception {assertEquals(" /folder ",load(" /folder ").get(0).path);assertEquals("/a/../b",load("/a/../b").get(0).path);}
 @Test public void rootHasUsableTitle()throws Exception {assertEquals("/",load("/").get(0).title);}
 @Test public void unicodeTitleAndPathSurvive()throws Exception {HomeShortcut s=load("/storage/책 📚").get(0);assertEquals("책 📚",s.title);}
 @Test public void fullPathDisambiguatesSameFolderNames()throws Exception {List<HomeShortcut>s=load("/sd/books","/usb/books");assertEquals(s.get(0).title,s.get(1).title);assertNotEquals(s.get(0).path,s.get(1).path);}
 @Test public void unavailableRowsAreNotDiscarded()throws Exception {for(HomeShortcut.Status st:HomeShortcut.Status.values()){List<HomeShortcut>s=HomeShortcut.load(Arrays.asList("/a"),p->st);assertEquals(1,s.size());assertEquals(st,s.get(0).status);}}
 @Test public void permissionExceptionsBecomeUnavailable()throws Exception {assertEquals(HomeShortcut.Status.UNREADABLE,HomeShortcut.load(Arrays.asList("/a"),p->{throw new SecurityException();}).get(0).status);}
 @Test public void nullProbeResultIsNotAvailable()throws Exception {assertEquals(HomeShortcut.Status.UNREADABLE,HomeShortcut.load(Arrays.asList("/a"),p->null).get(0).status);}
 @Test(expected=NullPointerException.class) public void nullProbeIsRejected()throws Exception {HomeShortcut.load(Arrays.asList("/a"),null);}
 @Test public void interruptionBeforeProbeDoesNoIo()throws Exception {int[]calls={0};Thread.currentThread().interrupt();try{HomeShortcut.load(Arrays.asList("/a"),p->{calls[0]++;return HomeShortcut.Status.AVAILABLE;});fail();}catch(InterruptedIOException expected){assertEquals(0,calls[0]);assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}}
 @Test public void interruptionDuringLoadPublishesNoPartialSnapshot()throws Exception {try{HomeShortcut.load(Arrays.asList("/a","/b"),p->{Thread.currentThread().interrupt();return HomeShortcut.Status.AVAILABLE;});fail();}catch(InterruptedIOException expected){}finally{Thread.interrupted();}}
 @Test public void resultCannotMutateSavedInputOrSnapshot()throws Exception {List<String>saved=new ArrayList<>(Arrays.asList("/a"));List<HomeShortcut>s=HomeShortcut.load(saved,p->HomeShortcut.Status.MISSING);saved.clear();assertEquals(1,s.size());try{s.clear();fail();}catch(UnsupportedOperationException expected){}}
 @Test public void filesystemProbeDistinguishesDirectoryFileAndMissing()throws Exception {assertEquals(HomeShortcut.Status.AVAILABLE,HomeShortcut.inspectFileSystem(temp.newFolder().getPath()));assertEquals(HomeShortcut.Status.NOT_DIRECTORY,HomeShortcut.inspectFileSystem(temp.newFile().getPath()));assertEquals(HomeShortcut.Status.MISSING,HomeShortcut.inspectFileSystem(new File(temp.getRoot(),"absent").getPath()));}
 @Test public void probesEachDistinctPinOnce()throws Exception {int[]calls={0};HomeShortcut.load(Arrays.asList("/a","/a","/b"),p->{calls[0]++;return HomeShortcut.Status.MISSING;});assertEquals(2,calls[0]);}
 @Test public void modelDoesNotImposeNewPinLimit()throws Exception {List<String>paths=new ArrayList<>();for(int i=0;i<512;i++)paths.add("/folder"+i);assertEquals(512,HomeShortcut.load(paths,p->HomeShortcut.Status.AVAILABLE).size());}
}
