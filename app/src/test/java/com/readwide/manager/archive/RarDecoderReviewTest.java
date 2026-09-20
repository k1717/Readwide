package com.readwide.manager.archive;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class RarDecoderReviewTest {
    @Test public void huffmanRandomTreesAgreeWithIndependentEncoderAtEveryAlignment() throws Exception {
        Random random=new Random(20260918);
        for(int trial=0;trial<120;trial++){
            List<Integer> depths=new ArrayList<>();depths.add(0);
            int target=2+random.nextInt(60);
            while(depths.size()<target){int index=random.nextInt(depths.size());int d=depths.get(index);if(d==15)continue;depths.set(index,d+1);depths.add(d+1);}
            Collections.shuffle(depths,random);
            int[] lengths=depths.stream().mapToInt(Integer::intValue).toArray();
            if(trial%3==0)lengths[random.nextInt(lengths.length)]=0; // valid incomplete tables too
            verifyCodes(lengths,random);
        }
    }
    @Test public void huffmanLongCodeFallbackAndShortTailsAgree() throws Exception {
        int[] lengths=new int[16];for(int i=0;i<14;i++)lengths[i]=i+1;lengths[14]=lengths[15]=15;
        verifyCodes(lengths,new Random(991));
        RarBitInput input=new RarBitInput(new byte[]{0});input.skipBits(7);
        assertEquals(0,RarCanonicalHuffman.fromCodeLengths(new int[]{1,15}).decode(input));
        assertEquals(8,input.bitsRead());
    }
    @Test public void lzRandomOverlapAndRingWrapMatchScalarReference() throws Exception {
        Random random=new Random(4421);
        for(int trial=0;trial<100;trial++){
            int size=1<<(2+random.nextInt(9));byte[] initial=new byte[size];random.nextBytes(initial);
            int position=random.nextInt(size),retained=random.nextInt(size+1);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            RarLzWindow actual=new RarLzWindow(initial.clone(),position,retained,new RarOutputStreamDecodedOutput(out));
            Scalar reference=new Scalar(initial.clone(),position,retained);
            for(int op=0;op<200;op++){
                if(random.nextInt(5)==0){int value=random.nextInt(256);actual.writeLiteral(value);reference.literal(value);}
                else{int distance=1+random.nextInt(actual.size());int length=random.nextInt(513);actual.copyMatch(distance,length);reference.match(distance,length);}
                assertEquals(reference.position,actual.position());assertEquals(reference.retained,actual.retained());assertEquals(reference.out.size(),actual.written());
                assertArrayEquals(reference.window,actual.bytes());
            }
            assertArrayEquals(reference.out.toByteArray(),out.toByteArray());
        }
    }
    @Test public void lzAutomaticGrowthPreservesHistoryAndOutput() throws Exception {
        Random random=new Random(413);
        for(int trial=0;trial<30;trial++){
            ByteArrayOutputStream out=new ByteArrayOutputStream();RarLzWindow actual=new RarLzWindow(16,out);actual.retainUpTo(1024);
            Scalar ref=new Scalar(new byte[16],0,16);ref.maximum=1024;
            for(int op=0;op<100;op++){
                int d=1+random.nextInt(actual.size()),n=1+random.nextInt(101);
                actual.copyMatch(d,n);ref.match(d,n);
                int value=random.nextInt(256);actual.writeLiteral(value);ref.literal(value);
                assertArrayEquals("trial="+trial+" op="+op+" distance="+d+" length="+n,ref.window,actual.bytes());assertEquals(ref.position,actual.position());
            }
            assertArrayEquals(ref.out.toByteArray(),out.toByteArray());
        }
    }
    @Test public void lzUsesBulkSinkForLongOverlappingMatches() throws Exception {
        final int[] calls={0};
        RarDecodedOutput sink=new RarDecodedOutput(){
            public void writeDecodedByte(int value){calls[0]++;}
            public void writeDecodedBytes(byte[] data,int offset,int length){calls[0]++;for(int i=0;i<length;i++)assertEquals('A',data[offset+i]);}
        };
        RarLzWindow window=new RarLzWindow(4096,sink);window.writeLiteral('A');window.copyMatch(1,200_000);
        assertTrue("Expected block writes rather than one call per byte",calls[0]<100);
        assertEquals(200_001,window.written());
    }
    @Test public void lzMatchCanBeCancelledBeforeOutput() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();RarLzWindow window=new RarLzWindow(16,out);
        Thread.currentThread().interrupt();
        try{window.copyMatch(1,100);fail("Expected cancellation");}
        catch(IOException expected){assertEquals(0,out.size());}
        finally{Thread.interrupted();}
    }
    @Test public void lzCounterOverflowFailsBeforePartialOutput() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();RarLzWindow window=new RarLzWindow(16,out);
        java.lang.reflect.Field field=RarLzWindow.class.getDeclaredField("written");field.setAccessible(true);field.setLong(window,Long.MAX_VALUE-2);
        try{window.copyMatch(1,3);fail("Expected overflow");}catch(IOException expected){assertEquals(0,out.size());}
    }

    private static void verifyCodes(int[] lengths,Random random)throws Exception {
        int[] counts=new int[16],next=new int[16],codes=new int[lengths.length];
        for(int length:lengths)if(length>0)counts[length]++;
        for(int i=1;i<16;i++)next[i]=(next[i-1]+counts[i-1])<<1;
        List<Integer> symbols=new ArrayList<>();
        for(int i=0;i<lengths.length;i++)if(lengths[i]>0){codes[i]=next[lengths[i]]++;symbols.add(i);}
        RarCanonicalHuffman decoder=RarCanonicalHuffman.fromCodeLengths(lengths);
        for(int alignment=0;alignment<8;alignment++){
            ByteArrayOutputStream packed=new ByteArrayOutputStream();int accumulator=0,bits=alignment,total=alignment;
            int[] expected=new int[100];
            for(int i=0;i<expected.length;i++){
                int symbol=symbols.get(random.nextInt(symbols.size()));expected[i]=symbol;total+=lengths[symbol];
                for(int b=lengths[symbol]-1;b>=0;b--){accumulator=(accumulator<<1)|((codes[symbol]>>>b)&1);if(++bits==8){packed.write(accumulator);bits=0;accumulator=0;}}
            }
            if(bits>0)packed.write(accumulator<<(8-bits));byte[] bytes=packed.toByteArray();
            for(RarBitInput input:new RarBitInput[]{new RarBitInput(bytes),new RarBitInput(new ByteArrayInputStream(bytes),bytes.length)}){
                input.skipBits(alignment);for(int symbol:expected)assertEquals(symbol,decoder.decode(input));assertEquals(total,input.bitsRead());
            }
        }
    }
    // Scalar reference preserves the original code's initialized-zero history and growth semantics.
    private static final class Scalar {
        byte[] window;int position,retained,maximum;final ByteArrayOutputStream out=new ByteArrayOutputStream();
        Scalar(byte[] bytes,int pos,int kept){window=bytes;position=pos;retained=kept;maximum=bytes.length;}
        void literal(int value){
            if(retained==window.length&&window.length<maximum){byte[] grown=new byte[Math.min(maximum,window.length*2)];int first=(position-retained)&(window.length-1);for(int i=0;i<retained;i++)grown[i]=window[(first+i)&(window.length-1)];window=grown;position=retained;}
            window[position]=(byte)value;position=(position+1)&(window.length-1);out.write(value);if(retained<window.length)retained++;
        }
        void match(int distance,int length){for(int i=0;i<length;i++)literal(window[(position-distance)&(window.length-1)]&255);}
    }
}
