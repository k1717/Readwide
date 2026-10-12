package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Complete bitstreams and independent generated fixtures; no alternative decoder. */
final class AlzHuffmanBoundaryChecks {
    static final String END = bits(0x444c5a02L, 32);
    interface Check { void run() throws Exception; }
    static Map<String, Check> checks() {
        Map<String, Check> m = new LinkedHashMap<>();
        m.put("positiveCodeLengthEdges", AlzHuffmanBoundaryChecks::positiveCodeLengthEdges);
        m.put("positiveCodeLengthDeltas", AlzHuffmanBoundaryChecks::positiveCodeLengthDeltas);
        m.put("positiveUnusedIncompleteTable", AlzHuffmanBoundaryChecks::positiveUnusedIncompleteTable);
        m.put("codeLengthCharWrapPositive", () -> reject(oneTable("00001"+"10".repeat(65536)+"01000"), "Huffman"));
        m.put("codeLengthCharWrapNegative", () -> reject(oneTable("00001"+"11".repeat(65536)+"01000"), "Huffman"));
        m.put("codeLengthZeroInitial", () -> reject(oneTable("00000"+"10"+"01000"), "Huffman"));
        m.put("codeLengthTooLargeInitial", () -> reject(oneTable(bits(31,5)+"11".repeat(30)+"01000"), "Huffman"));
        m.put("codeLengthDipsBelowOne", () -> reject(oneTable("00001"+"1110"+"01000"), "Huffman"));
        m.put("codeLengthRisesAboveTwenty", () -> reject(oneTable(bits(20,5)+"1011"+"11".repeat(19)+"01000"), "Huffman"));
        m.put("codeLengthInvalidUnusedTable", () -> reject(bytes(header(new int[]{65},2,1,0)+"0"+table(new int[]{1,2,2})+"00000"+"10"+"01000"+"011"+END), "Huffman"));
        m.put("selectorExhaustionLiteralPath", () -> reject(twoSymbols(1,"10".repeat(51)+"11",0), "selector count"));
        m.put("selectorExhaustionRunPath", () -> reject(twoSymbols(1,"10".repeat(49)+"00"+"11",0), "selector count"));
        m.put("selectorExhaustionAfterPriorBlock", AlzHuffmanBoundaryChecks::selectorExhaustionAfterPriorBlock);
        m.put("positiveSelectorBoundary", AlzHuffmanBoundaryChecks::positiveSelectorBoundary);
        m.put("rleIntegerWrap", AlzHuffmanBoundaryChecks::rleIntegerWrap);
        m.put("rleOverBlockSize", () -> reject(single("10".repeat(20)+"11",1,0), "RLE"));
        m.put("rleOverRemainingBlockSize", () -> reject(twoSymbols(1,"10".repeat(30)+"00".repeat(19)+"01"+"11",0), "RLE"));
        m.put("positiveShortRuns", AlzHuffmanBoundaryChecks::positiveShortRuns);
        m.put("missingRleCountByte", () -> reject(single(run(4)+"11",1,0), "RLE"));
        m.put("emptySymbolMap", () -> reject(emptyMapBlock()+END, "symbol map"));
        m.put("emptySymbolMapAfterPriorBlock", () -> reject(singleBlock("011",1,0)+emptyMapBlock()+END,"symbol map"));
        m.put("origPtrUsesCurrentBlock", AlzHuffmanBoundaryChecks::origPtrUsesCurrentBlock);
        m.put("positiveShrinkingBlocks", AlzHuffmanBoundaryChecks::positiveShrinkingBlocks);
        m.put("laterHuffmanFailureIsSticky", AlzHuffmanBoundaryChecks::laterHuffmanFailureIsSticky);
        m.put("laterSelectorFailureIsSticky", () -> sticky(singleBlock("011",1,0)+twoSymbolsBlock(1,"10".repeat(51)+"11",0)+END,"selector count"));
        m.put("laterRleFailureIsSticky", () -> sticky(singleBlock("011",1,0)+singleBlock("0"+"10"+"0".repeat(30)+"11",1,0)+END,"RLE"));
        m.put("cancellationIsSticky", AlzHuffmanBoundaryChecks::cancellationIsSticky);
        m.put("invalidBitSourceCounts", AlzHuffmanBoundaryChecks::invalidBitSourceCounts);
        m.put("closedStreamAndInvalidArguments", AlzHuffmanBoundaryChecks::closedStreamAndInvalidArguments);
        m.put("mixedScalarAndBulkExistingFixture", AlzHuffmanBoundaryChecks::mixedScalarAndBulkExistingFixture);
        return m;
    }
    static void positiveCodeLengthEdges() throws Exception {
        // Incomplete, but unambiguous code spaces are allowed, including length 20.
        for(int n=2;n<=20;n++) {
            String body=header(new int[]{65},2,1,0)+"0"+table(new int[]{n,n,n}).repeat(2)+bits(0,n)+bits(2,n)+END;
            equal(new byte[]{65},decode(bytes(body),0),"length "+n);
        }
        equal(new byte[]{65},decode(single("011",1,0),0),"min length 1");
    }
    static void positiveCodeLengthDeltas() throws Exception {
        // Unused groups still have legal, prefix-free incomplete code spaces.
        for (int[] lengths : new int[][]{{1,20,20}, {20,20,1}, {20,1,20}}) {
            String body=header(new int[]{65},2,1,0)+"0"+table(new int[]{1,2,2})+table(lengths)+"011"+END;
            equal(new byte[]{65},decode(bytes(body),0),"valid deltas");
        }
    }
    static void positiveUnusedIncompleteTable() throws Exception {
        String body=header(new int[]{65},2,1,0)+"0"+table(new int[]{1,2,2})+table(new int[]{20,20,20})+"011"+END;
        equal(new byte[]{65},decode(bytes(body),0),"unused incomplete table");
    }
    static void positiveSelectorBoundary() throws Exception {
        for(int symbols:new int[]{1,49,50,51,99,100,101,199}) {
            byte[] b=twoSymbols((symbols+1+49)/50,"10".repeat(symbols)+"11",0);
            require(decode(b,0).length > 0,"selector boundary "+symbols);
        }
    }
    static void selectorExhaustionAfterPriorBlock() throws Exception {
        String body=twoSymbolsBlock(3,"10".repeat(100)+"11",0)+twoSymbolsBlock(1,"10".repeat(51)+"11",0)+END;
        reject(body,"selector count");
    }
    static void rleIntegerWrap() throws Exception {
        for(int count:new int[]{32,33,64,65,100}) {
            String payload="0"+"10"+"0".repeat(count-2)+"11";
            reject(single(payload,(count+1+49)/50,0),"RLE");
        }
    }
    static void positiveShortRuns() throws Exception {
        for(int n=1;n<=3;n++) {
            byte[] expected=new byte[n];Arrays.fill(expected,(byte)65);
            equal(expected,decode(single(run(n)+"11",1,0),0),"run "+n);
        }
    }
    static void origPtrUsesCurrentBlock() throws Exception {
        for(int ptr:new int[]{1,2,10,49,50}) {
            String body=twoSymbolsBlock(2,"10".repeat(51)+"11",0)+singleBlock("011",1,ptr)+END;
            reject(body,"origPtr");
        }
    }
    static void positiveShrinkingBlocks() throws Exception {
        String first=twoSymbolsBlock(2,"10".repeat(51)+"11",0);
        byte[] expected=decode(bytes(first+END),0);expected=Arrays.copyOf(expected,expected.length+1);expected[expected.length-1]=65;
        equal(expected,decode(bytes(first+singleBlock("011",1,0)+END),0),"shrinking blocks");
    }
    static void laterHuffmanFailureIsSticky() throws Exception {
        String bad=header(new int[]{65},2,1,0)+"0"+"00001"+"1110"+"01000"+table(new int[]{1,2,2})+"011";
        sticky(singleBlock("011",1,0)+bad+END,"Huffman");
    }
    static void sticky(String bits,String message) throws Exception {
        try(InputStream in=new AlzBzip2InputStream(new ByteArrayInputStream(bytes(bits)))) {
            require(in.read()==65,"good first block");
            IOException first=io(in::read,message), second=io(in::read,message),third=io(()->in.read(new byte[31]),message);
            require(first==second&&second==third,"first error must persist");
        }
    }
    static void cancellationIsSticky() throws Exception {
        try(InputStream in=new AlzBzip2InputStream(new ByteArrayInputStream(single("011",1,0)))) {
            Thread.currentThread().interrupt();
            IOException first;
            try {first=io(in::read,"cancelled");} finally{Thread.interrupted();}
            require(first instanceof InterruptedIOException,"typed cancellation");
            require(first==io(in::read,"cancelled"),"sticky cancellation");
        }
    }
    static void invalidBitSourceCounts() throws Exception {
        for(int count:new int[]{0,-2,Integer.MAX_VALUE,65537}) {
            InputStream src=new InputStream(){public int read(){throw new AssertionError("scalar source read");}public int read(byte[] b,int o,int n){return count;}};
            io(()->new AlzBzip2InputStream(src),"progress");
        }
    }
    static void closedStreamAndInvalidArguments() throws Exception {
        InputStream in=new AlzBzip2InputStream(new ByteArrayInputStream(single("011",1,0)));
        try {in.read(new byte[8],Integer.MAX_VALUE,1);throw new AssertionError("argument overflow accepted");}catch(IndexOutOfBoundsException good){}
        require(in.read(new byte[0])==0,"zero length read");in.close();in.close();io(in::read,"closed");
    }
    static void mixedScalarAndBulkExistingFixture() throws Exception {
        byte[] packed=Base64.getDecoder().decode(Round6Fixtures.MULTI_BZIP);
        byte[] expected=decode(packed,0);
        for(int seed=0;seed<8;seed++) equal(expected,decode(packed,seed+1),"mixed reads "+seed);
    }
    static String header(int[] symbols,int groups,int selectors,int orig) {
        boolean[] used=new boolean[256];for(int v:symbols)used[v]=true;
        StringBuilder map=new StringBuilder(),body=new StringBuilder();
        for(int g=0;g<16;g++){boolean any=false;for(int j=0;j<16;j++)any|=used[16*g+j];map.append(any?'1':'0');if(any)for(int j=0;j<16;j++)body.append(used[16*g+j]?'1':'0');}
        return bits(0x444c5a01L,32)+bits(orig,24)+map+body+bits(groups,3)+bits(selectors,15);
    }
    static String table(int[] lengths) {
        StringBuilder b=new StringBuilder(bits(lengths[0],5));int current=lengths[0];
        for(int length:lengths){while(current<length){b.append("10");current++;}while(current>length){b.append("11");current--;}b.append('0');}return b.toString();
    }
    static String run(int length){StringBuilder b=new StringBuilder();while(length>0){if((length&1)==1){b.append('0');length=(length-1)/2;}else{b.append("10");length=(length-2)/2;}}return b.toString();}
    static byte[] oneTable(String first) {return bytes(header(new int[]{65},2,1,0)+"0"+first+table(new int[]{1,2,2})+"011"+END);}
    static String singleBlock(String payload,int selectors,int orig){return header(new int[]{65},2,selectors,orig)+"0".repeat(selectors)+table(new int[]{1,2,2}).repeat(2)+payload;}
    static byte[] single(String payload,int selectors,int orig){return bytes(singleBlock(payload,selectors,orig)+END);}
    static String twoSymbolsBlock(int selectors,String payload,int orig){return header(new int[]{65,66},2,selectors,orig)+"0".repeat(selectors)+table(new int[]{2,2,2,2}).repeat(2)+payload;}
    static byte[] twoSymbols(int selectors,String payload,int orig){return bytes(twoSymbolsBlock(selectors,payload,orig)+END);}
    static String emptyMapBlock(){return header(new int[0],2,1,0)+"0"+table(new int[]{1,1}).repeat(2)+"01";}
    static String bits(long v,int n){String s=Long.toBinaryString(v);return "0".repeat(n-s.length())+s;}
    static byte[] bytes(String s){byte[] b=new byte[(s.length()+7)/8];for(int i=0;i<s.length();i++)if(s.charAt(i)=='1')b[i/8]|=1<<(7-i%8);return b;}
    static byte[] decode(byte[] b,int seed) throws Exception {
        try(InputStream in=new AlzBzip2InputStream(new ByteArrayInputStream(b));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Random random=new Random(seed);byte[] buf=new byte[8197];while(true){if(seed!=0&&random.nextInt(5)==0){int v=in.read();if(v<0)break;out.write(v);}else{int n=in.read(buf,3,seed==0?8192:1+random.nextInt(8192));if(n<0)break;require(n>0,"zero-progress decode");out.write(buf,3,n);}}
            return out.toByteArray();
        }
    }
    static IOException io(Check r,String text) throws Exception {try{r.run();}catch(IOException e){if(text!=null)require(e.getMessage()!=null&&e.getMessage().contains(text),"wrong error: "+e);return e;}throw new AssertionError("corrupt input accepted");}
    static void reject(byte[] b,String text) throws Exception{io(()->decode(b,0),text);}
    static void reject(String b,String text) throws Exception{reject(bytes(b),text);}
    static void equal(byte[] expected,byte[] actual,String what){require(Arrays.equals(expected,actual),"mismatch "+what+" sizes "+expected.length+"/"+actual.length);}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void fixtures(Path dir) throws Exception {
        int count=0;for(String line:Files.readAllLines(dir.resolve("manifest.tsv"),StandardCharsets.UTF_8)){
            String[] f=line.split("\t");byte[] packed=Files.readAllBytes(dir.resolve(f[0]+".dlz"));byte[] output=decode(packed,41+count);
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output));
            require(output.length==Long.parseLong(f[1])&&actual.equals(f[2]),"fixture "+f[0]);
            System.out.println("FIXTURE_PASS "+f[0]+" bytes="+output.length+" sha256="+actual);count++;
        }System.out.println("FIXTURE_SUMMARY passed="+count);
    }
    public static void main(String[] args) throws Exception {
        if(args.length==2&&args[0].equals("--fixtures")){fixtures(java.nio.file.Paths.get(args[1]));return;}
        int passed=0,failed=0;for(Map.Entry<String,Check> test:checks().entrySet()){try{test.getValue().run();System.out.println("PASS "+test.getKey());passed++;}catch(Throwable t){System.out.println("FAIL "+test.getKey()+": "+t);t.printStackTrace(System.out);failed++;}finally{Thread.interrupted();}}
        System.out.println("SUMMARY passed="+passed+" failed="+failed+" total="+(passed+failed));if(failed!=0)System.exit(1);
    }
}
