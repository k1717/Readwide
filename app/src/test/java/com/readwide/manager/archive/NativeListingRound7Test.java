package com.readwide.manager.archive;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.rules.TemporaryFolder;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.zip.*;
import com.readwide.manager.util.FileOperationProgress;
public class NativeListingRound7Test {
 private boolean same(String a,String b)throws Exception{java.lang.reflect.Method m=LibarchiveNativeBridge.class.getDeclaredMethod("normalizeForCompare",String.class);m.setAccessible(true);return Objects.equals(m.invoke(null,a),m.invoke(null,b));}
 private final String header="READWIDE-LISTING/1\n";
 private List<ArchiveSupport.EntryInfo> parse(String s)throws Exception{return RarLibarchiveFallback.parseListingRows(s);}
 @Test public void preservesCaseDistinctFiles()throws Exception{List<ArchiveSupport.EntryInfo> e=parse("F\tPage.jpg\t1\t0\nF\tpage.jpg\t2\t0\n");assertEquals(2,e.size());assertEquals("Page.jpg",e.get(0).path);assertEquals("page.jpg",e.get(1).path);}
 @Test public void preservesCaseDistinctDirectories()throws Exception{List<ArchiveSupport.EntryInfo> e=parse("F\tA/one\t1\t0\nF\ta/two\t2\t0\n");assertEquals(4,e.size());assertEquals("A/",e.get(0).path);assertEquals("a/",e.get(2).path);}
 @Test public void exactNativeComparisonDoesNotFoldCase()throws Exception{assertFalse(same("Page.jpg","page.jpg"));}
 @Test public void nativeComparisonKeepsDotPrefixCompatibility()throws Exception{assertTrue(same("./dir/page.jpg","dir/page.jpg"));}
 @Test public void escapedTabIsNotAColumnSeparator()throws Exception{List<ArchiveSupport.EntryInfo> e=parse(header+"F\ta\\tb.txt\t9\t123\n");assertEquals(1,e.size());assertEquals("a\tb.txt",e.get(0).path);assertEquals(9,e.get(0).size);}
 @Test public void escapedNewlineCannotInjectRows()throws Exception{List<ArchiveSupport.EntryInfo> e=parse(header+"F\ta\\nF\\tfake\\t123\\t0.txt\t9\t123\n");assertEquals(1,e.size());assertEquals("a\nF\tfake\t123\t0.txt",e.get(0).path);}
 @Test public void carriageReturnInMiddlePreserved()throws Exception{List<ArchiveSupport.EntryInfo> e=parse(header+"F\ta\\rb.txt\t9\t123\n");assertEquals("a\rb.txt",e.get(0).path);}
 @Test public void invalidEscapeRejected()throws Exception{assertThrows(IOException.class,()->parse(header+"F\ta\\qb.txt\t9\t123\n"));}
 @Test public void invalidRowTypeRejected()throws Exception{assertThrows(IOException.class,()->parse("X\tfile\t9\t0\n"));}
 @Test public void extraColumnRejected()throws Exception{assertThrows(IOException.class,()->parse("F\tfile\t9\t0\tnoise\n"));}
 @Test public void traversalEntryNotPublished()throws Exception{assertEquals(0,parse(header+"F\t../outside\t1\t0\n").size());}
 @Test public void largeCaseDistinctListingNoArtificial64EntryLimit()throws Exception{StringBuilder b=new StringBuilder(header);for(int i=0;i<200;i++){b.append("F\tPage").append(i).append("\t1\t0\nF\tpage").append(i).append("\t2\t0\n");}assertEquals(400,parse(b.toString()).size());}
}
