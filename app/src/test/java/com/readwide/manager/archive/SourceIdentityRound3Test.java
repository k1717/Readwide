package com.readwide.manager.archive;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class SourceIdentityRound3Test {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 private File part(String name)throws Exception{File f=temp.newFile(name);Files.write(f.toPath(),new byte[]{1,2,3});assertTrue(f.setLastModified(1700000000000L));return f;}
 private String key(File file){return ArchiveSourceSnapshot.capture(file).cacheFingerprint();}
 @Test public void numericPartsShareOneNamespace()throws Exception {
  File first=part("comic.zip.001"),next=part("comic.zip.002");assertEquals(key(first),key(next));
 }
 @Test public void rarPartsShareOneNamespace()throws Exception {
  File first=part("comic.part01.rar"),next=part("comic.part02.rar");assertEquals(key(first),key(next));
 }
 @Test public void legacyRarPartsShareOneNamespace()throws Exception {
  File first=part("comic.rar"),next=part("comic.r00");assertEquals(key(first),key(next));
 }
 @Test public void laterNumericLengthChangeInvalidatesNamespaceAndHandoff()throws Exception {
  File first=part("comic.tar.gz.001"),next=part("comic.tar.gz.002");ArchiveSourceSnapshot saved=ArchiveSourceSnapshot.capture(first);
  Files.write(next.toPath(),new byte[]{1,2,3,4});assertFalse(saved.matches(first));assertNotEquals(saved.cacheFingerprint(),key(first));
 }
 @Test public void laterRarTimestampChangeInvalidatesNamespace()throws Exception {
  File first=part("comic.part1.rar"),next=part("comic.part2.rar");String saved=key(first);assertTrue(next.setLastModified(next.lastModified()+10000));assertNotEquals(saved,key(first));
 }
 @Test public void appendVolumeChangesNamespace()throws Exception {
  File first=part("comic.7z.001");String saved=key(first);part("comic.7z.002");assertNotEquals(saved,key(first));
 }
 @Test public void removeVolumeChangesNamespace()throws Exception {
  File first=part("comic.7z.001"),next=part("comic.7z.002");String saved=key(first);assertTrue(next.delete());assertNotEquals(saved,key(first));
 }
 @Test public void failedCaptureCannotReuseOldNamespaceOrBecomeValidAfterRepair()throws Exception {
  File first=part("comic.7z.001");part("comic.7z.003");ArchiveSourceSnapshot bad=ArchiveSourceSnapshot.capture(first);
  assertFalse(bad.matches(first));assertNotEquals(bad.cacheFingerprint(),key(first));part("comic.7z.002");assertFalse(bad.matches(first));assertTrue(ArchiveSourceSnapshot.capture(first).matches(first));
 }
 @Test public void ambiguousRarCaptureFailsClosed()throws Exception {
  File first=part("comic.part1.rar");part("comic.part01.rar");ArchiveSourceSnapshot bad=ArchiveSourceSnapshot.capture(first);assertFalse(bad.matches(first));
 }
 @Test public void rawNumericRarDoesNotUseNativeRarVolumeNaming()throws Exception {
  File first=part("comic.rar.001"),next=part("comic.rar.002");ArchiveSourceSnapshot saved=ArchiveSourceSnapshot.capture(next);
  assertTrue(saved.matches(next));assertEquals(key(first),key(next));assertTrue(first.setLastModified(1600000000000L));assertFalse(saved.matches(next));
 }
 @Test public void unrelatedFilesDoNotInvalidateNamespace()throws Exception {
  File first=part("comic.zip.001");String saved=key(first);part("unrelated.zip.002");assertEquals(saved,key(first));
 }
 @Test public void singleFileDeletionInvalidatesSnapshot()throws Exception {
  File file=part("comic.zip");ArchiveSourceSnapshot saved=ArchiveSourceSnapshot.capture(file);assertTrue(saved.matches(file));assertTrue(file.delete());assertFalse(saved.matches(file));
 }
 @Test public void metadataFingerprintDoesNotClaimContentHashing()throws Exception {
  File file=part("comic.zip");String saved=key(file);long time=file.lastModified();Files.write(file.toPath(),new byte[]{9,8,7});assertTrue(file.setLastModified(time));assertEquals(saved,key(file));
 }
 @Test public void snapshotKeyIsFixedWidthAndStableWithoutFurtherFileIO()throws Exception {
  File first=part("comic.zip.001");ArchiveSourceSnapshot saved=ArchiveSourceSnapshot.capture(first);assertEquals(24,saved.cacheFingerprint().length());String key=saved.cacheFingerprint();assertTrue(first.delete());assertEquals(key,saved.cacheFingerprint());assertFalse(saved.matches(first));
 }
 @Test public void selectedPartIdentityStillGuardsHandoffEvenWithSharedNamespace()throws Exception {
  File first=part("comic.7z.001"),next=part("comic.7z.002");ArchiveSourceSnapshot saved=ArchiveSourceSnapshot.capture(first);
  assertEquals(saved.cacheFingerprint(),key(next));assertFalse(saved.matches(next));
 }
}
