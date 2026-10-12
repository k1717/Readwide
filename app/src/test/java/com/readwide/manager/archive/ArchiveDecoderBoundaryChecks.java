package com.readwide.manager.archive;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Tests the actual shared raw inflater and complete ALZ reader with real files. */
final class ArchiveDecoderBoundaryChecks {
    interface Check { void run() throws Exception; }
    static Map<String, Check> checks() {
        Map<String, Check> m=new LinkedHashMap<>();
        m.put("deflateNegativeSourceCount",()->invalidDeflateCount(-2));
        m.put("deflateOversizedSourceCount",()->invalidDeflateCount(65537));
        m.put("deflateMaximumSourceCount",()->invalidDeflateCount(Integer.MAX_VALUE));
        m.put("deflateZeroProgressSource",()->invalidDeflateCount(0));
        m.put("deflateTruncatedSourceSticky",ArchiveDecoderBoundaryChecks::deflateTruncatedSourceSticky);
        m.put("deflateSourceFailureIdentity",ArchiveDecoderBoundaryChecks::deflateSourceFailureIdentity);
        m.put("deflateLateInvalidCount",ArchiveDecoderBoundaryChecks::deflateLateInvalidCount);
        m.put("deflateKnownPayloads",ArchiveDecoderBoundaryChecks::deflateKnownPayloads);
        m.put("deflateShortCompressedReads",ArchiveDecoderBoundaryChecks::deflateShortCompressedReads);
        m.put("deflateMixedReadAndSkip",ArchiveDecoderBoundaryChecks::deflateMixedReadAndSkip);
        m.put("deflateCancellationSticky",ArchiveDecoderBoundaryChecks::deflateCancellationSticky);
        m.put("deflateCloseFailureClosesOnce",ArchiveDecoderBoundaryChecks::deflateCloseFailureClosesOnce);
        m.put("deflateInvalidArgumentsDoNotPoison",ArchiveDecoderBoundaryChecks::deflateInvalidArgumentsDoNotPoison);
        m.put("alzValidTrimmedBzip",()->alzGood(false,1));
        m.put("alzValidEncryptedTrimmedBzip",()->alzGood(true,1));
        m.put("alzValidManySplitTrimmedBzip",()->alzGood(false,137));
        m.put("alzValidEncryptedManySplitTrimmedBzip",()->alzGood(true,137));
        m.put("alzCodeLengthWrapPreservesExisting",()->alzBad(AlzHuffmanBoundaryChecks.oneTable("00001"+"10".repeat(65536)+"01000"),true,false));
        m.put("alzCodeLengthWrapRemovesNewPartial",()->alzBad(AlzHuffmanBoundaryChecks.oneTable("00001"+"10".repeat(65536)+"01000"),false,false));
        m.put("alzRleWrapWithValidOuterCrcRejected",()->alzBad(AlzHuffmanBoundaryChecks.single("0"+"10"+"0".repeat(30)+"11",1,0),true,false));
        m.put("alzEncryptedBadTablePreservesExisting",()->alzBad(AlzHuffmanBoundaryChecks.oneTable("00001"+"10".repeat(65536)+"01000"),true,true));
        m.put("alzMalformedSelectorClassifiesCorruption",ArchiveDecoderBoundaryChecks::alzMalformedSelectorClassifiesCorruption);
        m.put("alzUnknownMethodStillUnsupported",ArchiveDecoderBoundaryChecks::alzUnknownMethodStillUnsupported);
        m.put("alzWrongPasswordPreservesExisting",ArchiveDecoderBoundaryChecks::alzWrongPasswordPreservesExisting);
        m.put("alzBadTrimmedHeaderClassifiesCorruption",ArchiveDecoderBoundaryChecks::alzBadTrimmedHeaderClassifiesCorruption);
        m.put("alzProbeZeroProgress",()->badProbe(0));
        m.put("alzProbeNegativeCount",()->badProbe(-2));
        m.put("alzProbeOversizedCount",()->badProbe(4));
        m.put("alzProbeCancellation",ArchiveDecoderBoundaryChecks::alzProbeCancellation);
        m.put("alzProbeShortReads",ArchiveDecoderBoundaryChecks::alzProbeShortReads);
        m.put("alzSourceIoCausePreserved",ArchiveDecoderBoundaryChecks::alzSourceIoCausePreserved);
        return m;
    }
    static void invalidDeflateCount(int count)throws Exception {
        int[] calls={0};InputStream source=new InputStream(){public int read(){throw new AssertionError("scalar read");}public int read(byte[] b,int o,int n){calls[0]++;return count;}};
        try(InputStream in=new OwnedDeflateInputStream(source)){
            IOException e=io(()->in.read(new byte[8]),"progress");
            require(e==io(in::read,"progress")&&e==io(in::available,"progress"),"sticky invalid count");
            require(calls[0]==1,"source retried after failure");
        }
    }
    static void deflateTruncatedSourceSticky()throws Exception {
        try(InputStream in=new OwnedDeflateInputStream(new ByteArrayInputStream(new byte[0]))){IOException e=io(in::read,null);require(e instanceof EOFException,"EOF type");require(e==io(in::read,null),"sticky EOF");}
    }
    static void deflateSourceFailureIdentity()throws Exception {
        IOException expected=new IOException("injected source failure");
        try(InputStream in=new OwnedDeflateInputStream(new InputStream(){public int read()throws IOException{throw expected;}public int read(byte[] b,int o,int n)throws IOException{throw expected;}})){
            require(expected==io(in::read,null),"original source error");require(expected==io(in::read,null),"sticky error");
        }
    }
    static void deflateLateInvalidCount()throws Exception {
        byte[] packed=Round4Fixtures.deflate(Round4Fixtures.data(100000));int[] calls={0};
        InputStream source=new InputStream(){public int read(){throw new AssertionError();}public int read(byte[] b,int o,int n){if(calls[0]++>0)return -2;System.arraycopy(packed,0,b,o,500);return 500;}};
        try(InputStream in=new OwnedDeflateInputStream(source)){require(in.read(new byte[100])>0,"initial data");IOException e=io(in::readAllBytes,"progress");require(e==io(in::read,"progress"),"late sticky error");}
    }
    static void deflateKnownPayloads()throws Exception {
        for(int size:new int[]{0,1,255,65536,196609}){byte[] p=Round4Fixtures.data(size);try(InputStream in=new OwnedDeflateInputStream(new ByteArrayInputStream(Round4Fixtures.deflate(p)))){equal(p,in.readAllBytes(),"raw Deflate "+size);require(in.read()==-1,"EOF");}}
    }
    static void deflateShortCompressedReads()throws Exception {
        byte[] plain=Round4Fixtures.data(90003),packed=Round4Fixtures.deflate(plain);
        for(int chunk:new int[]{1,2,3,7,37,1023})try(InputStream in=new OwnedDeflateInputStream(new ByteArrayInputStream(packed){@Override public synchronized int read(byte[] b,int o,int n){return super.read(b,o,Math.min(n,chunk));}})){equal(plain,in.readAllBytes(),"short reads "+chunk);}
    }
    static void deflateMixedReadAndSkip()throws Exception {
        byte[] p=Round4Fixtures.data(20003);try(InputStream in=new OwnedDeflateInputStream(new ByteArrayInputStream(Round4Fixtures.deflate(p)))){
            int pos=0;Random random=new Random(57);while(pos<p.length){if(random.nextBoolean()){int n=Math.min(1+random.nextInt(300),p.length-pos);require(in.skip(n)==n,"skip count");pos+=n;}else{require(in.read()==(p[pos++]&255),"scalar value");}}require(in.read()==-1,"end after skip");
        }
    }
    static void deflateCancellationSticky()throws Exception {
        try(InputStream in=new OwnedDeflateInputStream(new ByteArrayInputStream(Round4Fixtures.deflate(new byte[]{1})))){
            Thread.currentThread().interrupt();IOException e;try{e=io(in::read,null);}finally{Thread.interrupted();}require(e instanceof InterruptedIOException,"cancellation type");require(e==io(in::read,null),"sticky cancellation");
        }
    }
    static void deflateCloseFailureClosesOnce()throws Exception {
        int[] closes={0};IOException expected=new IOException("injected close");
        InputStream in=new OwnedDeflateInputStream(new ByteArrayInputStream(Round4Fixtures.deflate(new byte[]{1})){@Override public void close()throws IOException{closes[0]++;throw expected;}});
        require(in.read()==1,"valid read");require(expected==io(in::close,null),"close error identity");in.close();require(closes[0]==1,"idempotent close");io(in::read,"closed");
    }
    static void deflateInvalidArgumentsDoNotPoison()throws Exception {
        try(InputStream in=new OwnedDeflateInputStream(new ByteArrayInputStream(Round4Fixtures.deflate(new byte[]{7})))){
            try{in.read(new byte[1],Integer.MAX_VALUE,1);throw new AssertionError("bounds");}catch(IndexOutOfBoundsException good){}
            require(in.read(new byte[0])==0&&in.read()==7,"bad caller arguments must not consume data");
        }
    }
    static void alzGood(boolean encrypted,int count)throws Exception {
        Path dir=Files.createTempDirectory("rw-alz-good-");try{
            byte[] packed=Base64.getDecoder().decode(Round6Fixtures.MULTI_BZIP),plain=AlzHuffmanBoundaryChecks.decode(packed,0);
            byte[] archive=Round4Fixtures.alzStored(plain,1,packed,encrypted);File selected;
            if(count==1)selected=Round4Fixtures.write(dir.toFile(),"book.alz",archive);
            else {List<File> parts=Round4Fixtures.splitAlz(dir.toFile(),"book",archive,count);selected=parts.get(parts.size()-1);}
            require(!AlzipArchiveReader.listEntries(selected,encrypted?Round4Fixtures.PASSWORD:null).isEmpty(),"list");
            File output=dir.resolve("result.bin").toFile();Files.write(output.toPath(),new byte[]{9,8,7});
            require(AlzipArchiveReader.extractSingleEntry(selected,"data.bin",output,encrypted?Round4Fixtures.PASSWORD:null),"extract");equal(plain,Files.readAllBytes(output.toPath()),"ALZ output");
        }finally{delete(dir);AlzipArchiveReader.clearIndexes();}
    }
    static void alzBad(byte[] packed,boolean existed,boolean encrypted)throws Exception {
        Path dir=Files.createTempDirectory("rw-alz-bad-");try{
            File archive=Round4Fixtures.write(dir.toFile(),"book.alz",Round4Fixtures.alzStored(new byte[]{65},1,packed,encrypted));
            File out=dir.resolve("result.bin").toFile();byte[] old="keep original".getBytes(StandardCharsets.UTF_8);if(existed)Files.write(out.toPath(),old);
            IOException error=io(()->AlzipArchiveReader.extractSingleEntry(archive,"data.bin",out,encrypted?Round4Fixtures.PASSWORD:null),null);
            require(!(error instanceof ArchiveSupport.UnsupportedArchiveFeatureException),"corruption must not be labelled unsupported: "+error);
            require(ArchiveFailureClassifier.classify(error)==ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,"corruption category "+error);
            if(existed)equal(old,Files.readAllBytes(out.toPath()),"rollback existing");else require(!out.exists(),"remove new partial");
            try(java.util.stream.Stream<Path> files=Files.list(dir)){require(files.noneMatch(p->Files.isDirectory(p)),"backup cleanup");}
        }finally{delete(dir);AlzipArchiveReader.clearIndexes();}
    }
    static void alzMalformedSelectorClassifiesCorruption()throws Exception {
        byte[] malformed=AlzHuffmanBoundaryChecks.bytes(AlzHuffmanBoundaryChecks.header(new int[]{65},2,1,0)+"11");alzBad(malformed,true,false);
    }
    static void alzBadTrimmedHeaderClassifiesCorruption()throws Exception {
        // Recognised trimmed framing but an invalid next marker; no external codec needed.
        alzBad(new byte[]{'D','L','Z',3},true,false);
    }
    static void alzUnknownMethodStillUnsupported()throws Exception {
        Path dir=Files.createTempDirectory("rw-alz-method-");try{File a=Round4Fixtures.write(dir.toFile(),"book.alz",Round4Fixtures.alzStored(new byte[]{65},99,new byte[]{65},false));IOException e=io(()->AlzipArchiveReader.extractSingleEntry(a,"data.bin",dir.resolve("out").toFile(),null),null);require(e instanceof ArchiveSupport.UnsupportedArchiveFeatureException,"actual unknown method remains unsupported");}finally{delete(dir);AlzipArchiveReader.clearIndexes();}
    }
    static void alzWrongPasswordPreservesExisting()throws Exception {
        Path dir=Files.createTempDirectory("rw-alz-password-");try{File a=Round4Fixtures.write(dir.toFile(),"book.alz",Round4Fixtures.alzStored(new byte[]{65},1,AlzHuffmanBoundaryChecks.single("011",1,0),true));Path out=dir.resolve("out");byte[] old={11,12};Files.write(out,old);IOException e=io(()->AlzipArchiveReader.extractSingleEntry(a,"data.bin",out.toFile(),"wrong".toCharArray()),null);require(ArchiveFailureClassifier.classify(e)==ArchiveSupport.ExtractionFailure.BAD_PASSWORD,"password classification");equal(old,Files.readAllBytes(out),"password rollback");}finally{delete(dir);AlzipArchiveReader.clearIndexes();}
    }
    static InputStream probe(InputStream source)throws Exception {
        Method m=AlzipArchiveReader.class.getDeclaredMethod("openAlzBzipStream",InputStream.class);m.setAccessible(true);
        try{return (InputStream)m.invoke(null,source);}catch(InvocationTargetException e){Throwable c=e.getCause();if(c instanceof Exception)throw (Exception)c;throw (Error)c;}
    }
    static void badProbe(int count)throws Exception {
        InputStream src=new InputStream(){public int read(){throw new AssertionError("scalar");}public int read(byte[] b,int o,int n){return count;}};
        io(()->probe(src),"progress");
    }
    static void alzProbeCancellation()throws Exception {Thread.currentThread().interrupt();try{IOException e=io(()->probe(new ByteArrayInputStream(AlzHuffmanBoundaryChecks.single("011",1,0))),null);require(e instanceof InterruptedIOException,"probe cancellation type");}finally{Thread.interrupted();}}
    static void alzProbeShortReads()throws Exception {
        byte[] packed=AlzHuffmanBoundaryChecks.single("011",1,0);
        try(InputStream in=probe(new ByteArrayInputStream(packed){@Override public synchronized int read(byte[] b,int o,int n){return super.read(b,o,Math.min(n,1));}})){equal(new byte[]{65},in.readAllBytes(),"probe short reads");}
    }
    static void alzSourceIoCausePreserved()throws Exception {
        IOException expected=new IOException("injected source failure");
        InputStream src=new InputStream(){public int read()throws IOException{throw expected;}public int read(byte[] b,int o,int n)throws IOException{throw expected;}};
        require(io(()->probe(src),null)==expected,"preserve original source exception");
    }
    static IOException io(Check check,String text)throws Exception{try{check.run();}catch(IOException e){if(text!=null)require(e.getMessage()!=null&&e.getMessage().contains(text),"wrong error "+e);return e;}throw new AssertionError("expected IOException");}
    static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
    static void equal(byte[] a,byte[] b,String why){require(Arrays.equals(a,b),why);}
    static void delete(Path root)throws IOException{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    public static void main(String[] args)throws Exception {
        int pass=0,fail=0;for(Map.Entry<String,Check> test:checks().entrySet())try{test.getValue().run();System.out.println("PASS "+test.getKey());pass++;}catch(Throwable t){System.out.println("FAIL "+test.getKey()+": "+t);t.printStackTrace(System.out);fail++;}finally{Thread.interrupted();}
        System.out.println("SUMMARY passed="+pass+" failed="+fail+" total="+(pass+fail));if(fail>0)System.exit(1);
    }
}
