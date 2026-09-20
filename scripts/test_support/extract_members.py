#!/usr/bin/env python3
"""Extract exact selected ArchiveSupport members. No handwritten copies of their algorithms.
Surrounding decoder-routing seams are explicit, and not a full Android dispatcher compilation.
"""
from pathlib import Path
import sys, json, hashlib, re

def span(source, token):
    pos=source.find(token)
    if pos<0: raise ValueError(token)
    start=source.rfind('\n',0,pos)+1
    brace=source.find('{',pos); depth=1;i=brace+1;mode=None
    while depth:
        c=source[i];n=source[i:i+2]
        if mode in ('"',"'"):
            if c=='\\': i+=2;continue
            if c==mode:mode=None
        elif mode=='//':
            if c=='\n':mode=None
        elif mode=='/*':
            if n=='*/':mode=None;i+=2;continue
        elif n in ('//','/*'):mode=n;i+=2;continue
        elif c in ('"',"'"):mode=c
        elif c=='{':depth+=1
        elif c=='}':depth-=1
        i+=1
    return start,i

def member(s,token):
    a,b=span(s,token);return s[a:b]

if __name__=='__main__':
 root=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
 source=root/'app/src/main/java/com/readwide/manager/archive/ArchiveSupport.java';s=source.read_text()
 tokens=['public enum Type','public enum ExtractionFailure','public static final class ExtractionResult',
 'public static final class EntryInfo','public static final class ForwardEntry','public interface ForwardArchiveReader',
 'public static ForwardArchiveReader openForwardReader(@NonNull File archive,',
 'private static ForwardArchiveReader ownForwardReader(',
 'private static boolean shouldOpenSevenZForwardWithLibarchive(',
 'private static String[] sevenZForwardArchivePaths(',
 'private static final class SevenZForwardReader',
 'private static boolean sevenZEntryUsesAes(',
 'private static boolean sevenZArchiveHasAesContext(',
 'private static boolean rawSevenZHeaderContainsAesCoder(',
 'private static boolean rawFileContainsSevenZAesCoder(',
 'private static ExtractionFailure classifyExtractionFailure(@NonNull Type type,',
 'public static final class PasswordRequiredException','public static class UnsupportedArchiveFeatureException',
 'static String sanitizeEntryPathForList(', 'private static String normalizeDisplayPath(', 'static File resolveArchiveEntryOutput(',
 'private static boolean isSameOrDescendant(', 'private static boolean deleteFileSystemItem(',
 'static long runtimeExtractionBudgetBytes(', 'static long runtimeExtractionBudgetForUsableSpace(',
 'static long checkedAddDecodedStreamBytes(', 'public static OutputStream openExtractionOutputStream(',
 'static boolean writeArchiveEntryStream(@NonNull InputStream in, @NonNull File out)',
 'static boolean writeArchiveEntryStream(@NonNull InputStream in,\n',
 'public static ExtractionResult extractSingleEntryDetailed(',
 'public static ExtractionResult extractArchiveDetailed(', 'private static boolean extractArchiveIntoDirectory(',
 'private static long estimatePayloadBytesFromEntries(', 'private static long addMeasuredBytes(',
 'private static boolean hasUsableSpaceForExtraction(', 'private static File buildTempExtractDirectory(',
 'private static boolean replaceExistingDirectoryWithTemp(', 'private static void restoreDirectoryBackup(',
 'private static boolean renameFileSystemItem(', 'private static boolean copyDirectoryRecursively(',
 'private static boolean copyRegularFile(',
 'public static boolean extractSingleEntry(@NonNull File archive,',
 'public static boolean createZipArchive(',
 'public static boolean isNumericSplitArchive(', 'public static File normalizeExtractionQueueArchive(',
 'private static final class PreparedArchive', 'private static PreparedArchive prepareArchiveForRead(',
 'private static File combineSplitParts(', 'private static List<File> collectNumericSplitParts(',
 'private static List<File> collectRarSplitParts(', 'private static File resolveFirstAlzipPart(',
 'private static boolean isRarSplitPart(', 'private static boolean isAlzipSplitPart(',
 'static boolean shouldUseLibarchiveForwardForSevenZ(boolean libarchiveAvailable,',
 ]
 # Both policy overloads share a start token; extract second explicitly too.
 matches=[m.start() for m in re.finditer(re.escape('static boolean shouldUseLibarchiveForwardForSevenZ('),s)]
 bodies=[member(s,t) for t in tokens]
 # Extract the three-argument factory as well (first token selected two-argument overload).
 start=s.index('public static ForwardArchiveReader openForwardReader(@NonNull File archive,')
 start=s.index('public static ForwardArchiveReader openForwardReader(@NonNull File archive,', start+1)
 bodies.append(member(s[start-4:], 'public static ForwardArchiveReader openForwardReader('))
 if len(matches)>1: bodies.append(member(s[matches[1]-4:], 'static boolean shouldUseLibarchiveForwardForSevenZ('))
 optional=['private static void cleanupPartialSingleEntryOutput(', 'private static void addSourceToZip(',
 'private static String sanitizeZipEntryName(', 'private static boolean isSameFile(']
 for t in optional:
  if t in s:tokens.append(t);bodies.append(member(s,t))
 head='''package com.readwide.manager.archive;
import java.io.*;import java.util.*;import java.util.regex.*;import java.util.zip.*;import androidx.annotation.*;
import com.readwide.manager.util.*;
/** Generated exact production members plus EXPLICIT routing seams; not the full dispatcher. */
public final class ArchiveSupport {
 static final long MIN_EXTRACTION_FREE_MARGIN_BYTES=64L*1024*1024;
 public static Type getSupportedArchiveType(File f){return ArchiveTypeDetector.fromFile(f);}
 public static Type getSupportedArchiveType(String f){return ArchiveTypeDetector.fromFileName(f);}
 public static boolean isSupportedArchive(File f){return getSupportedArchiveType(f)!=null;}
 public static boolean isSupportedArchiveFileName(String f){return getSupportedArchiveType(f)!=null;}
 static boolean isFirstNumericSplitName(String n){return ArchiveTypeDetector.isFirstNumericSplitName(n);}
 public static boolean isForwardImageReadableType(File f){return false;}

 static SevenZFile openSevenZFile(File f,char[] p)throws IOException{throw unavailable();}
 static InputStream wrapTarPayloadInputStream(InputStream s,Type t)throws IOException{throw unavailable();}
 static final class SevenZFile implements Closeable {
  public SevenZArchiveEntry getNextEntry()throws IOException{throw unavailable();}
  public int read(byte[] b)throws IOException{throw unavailable();}public void close(){}
 }
 static final class SevenZArchiveEntry {
  String getName(){throw new AssertionError("Unavailable backend");}boolean isDirectory(){throw new AssertionError();}
  boolean hasStream(){throw new AssertionError();}Iterable<SevenZMethodConfiguration> getContentMethods(){throw new AssertionError();}
 }
 static final class SevenZMethodConfiguration{SevenZMethod getMethod(){throw new AssertionError();}}
 enum SevenZMethod{AES256SHA256}
 static final class TarArchiveInputStream extends InputStream {
  TarArchiveInputStream(InputStream in)throws IOException{throw unavailable();}
  public int read()throws IOException{throw unavailable();}
 }
 static final class TarForwardReader implements ForwardArchiveReader {
  TarForwardReader(PreparedArchive p,TarArchiveInputStream t,InputStream s)throws IOException{throw unavailable();}
  public ForwardEntry nextEntry()throws IOException{throw unavailable();}public int read(byte[] b)throws IOException{throw unavailable();}public void close(){}
 }
 // These methods are NOT the production fallback implementations.
 static IOException unavailable(){return new IOException("HOST_UNAVAILABLE_BACKEND");}
 static boolean extractSingleZipEntryWithFallback(File a,Type t,String e,File o,char[] p)throws IOException{if(p!=null&&p.length>0)throw unavailable();return LightweightZipArchiveReader.extractSingleEntry(a,e,o);}
 static boolean extractSingleSevenZEntryWithFallback(File a,Type t,String e,File o,char[] p)throws IOException{return SevenZBcj2ArchiveReader.extractSingleEntry(a,e,o,p);}
 static boolean extractSingleTarEntryWithFallback(File a,Type t,String e,File o)throws IOException{throw unavailable();}
 static boolean extractSingleCompressedEntry(File a,File original,String e,File o,Type t)throws IOException{throw unavailable();}
 static boolean extractSingleRarEntry(File a,String e,File o,char[] p)throws IOException{return RarArchiveReader.extractSingleEntry(a,e,o,p);}
 static ExtractionFailure classifyExtractionFailure(File a,char[] p,Exception e){return ArchiveFailureClassifier.classify(e);}
 public static java.util.List<EntryInfo> listEntries(File f,char[] p)throws IOException{
 try(PreparedArchive prepared=prepareArchiveForRead(f)) {
  switch(prepared.type){
   case ZIP:return LightweightZipArchiveReader.listEntries(prepared.file);
   case ALZ:return AlzipArchiveReader.listEntries(prepared.file,p);
   case EGG:return EggArchiveReader.listEntries(prepared.file,p);
   case RAR:return RarArchiveReader.listEntries(prepared.file,p);
   case SEVEN_Z:return SevenZBcj2ArchiveReader.listEntries(prepared.file,p);
   default:throw unavailable();
  }
 }
}
 static boolean extractZipIntoDirectoryWithFallback(File f,Type t,File o,char[] p,FileOperationProgress x,ArchiveExtractionProgressTracker y)throws IOException{if(x!=null)x.setDetail(f.getName());if(p!=null&&p.length>0)throw unavailable();return LightweightZipArchiveReader.extractArchiveIntoDirectory(f,o);}
 static boolean extractSevenZIntoDirectoryWithFallback(File f,Type t,File o,char[] p,FileOperationProgress x,ArchiveExtractionProgressTracker y)throws IOException{return SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(f,o,p,x,y);}
 static boolean extractRarIntoDirectory(File f,File o,char[] p,FileOperationProgress x,ArchiveExtractionProgressTracker y)throws IOException{return RarArchiveReader.extractArchiveIntoDirectory(f,o,p,x,y);}
 static boolean extractTarIntoDirectoryWithFallback(File f,Type t,File o,FileOperationProgress x,ArchiveExtractionProgressTracker y)throws IOException{throw unavailable();}
 static boolean extractSingleCompressedIntoDirectory(File f,File original,File o,Type t,FileOperationProgress x,ArchiveExtractionProgressTracker y)throws IOException{throw unavailable();}
'''
 p=out/'com/readwide/manager/archive/ArchiveSupport.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(head+'\n\n'.join(bodies)+'\n}\n')
 # Exact native comparison helper in isolation: no native backend is executed.
 ns=(root/'app/src/main/java/com/readwide/manager/archive/LibarchiveNativeBridge.java').read_text()
 native=member(ns,'private static String normalizeForCompare(')
 (p.parent/'NativeComparisonProbe.java').write_text('package com.readwide.manager.archive;import java.util.*;import androidx.annotation.*;public class NativeComparisonProbe{'+native+'\n public static boolean same(String a,String b){return java.util.Objects.equals(normalizeForCompare(a),normalizeForCompare(b));}}')
 seam=(Path(__file__).parent/'src/com/readwide/manager/archive/LibarchiveNativeBridge.java').read_text()
 seam=seam.replace('final class LibarchiveNativeBridge {','final class LibarchiveNativeBridge {\n'+native)
 seam=seam.replace('import java.io.*;','import java.io.*;import java.util.*;import androidx.annotation.*;')
 (p.parent/'LibarchiveNativeBridge.java').write_text(seam)
 (out/'extracted-methods.json').write_text(json.dumps({'source_sha256':hashlib.sha256(s.encode()).hexdigest(),'members':tokens,'boundary':'Exact selected members, explicit unavailable native/default native/Zip4j/TAR/codecs. ZIP test routing invokes real nondefault LightweightZipArchiveReader and 7z invokes real supplemental reader; these are NOT production default fallback decisions.'},indent=2)+'\n')
