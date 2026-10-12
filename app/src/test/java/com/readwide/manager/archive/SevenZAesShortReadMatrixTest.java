package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Input fragmentation must not change CBC output length or turn a failed finalization into EOF. */
@RunWith(Parameterized.class)
public final class SevenZAesShortReadMatrixTest {
    @Parameterized.Parameters(name="size={0},packedRead={1}") public static Collection<Object[]> variants(){List<Object[]> values=new ArrayList<>();for(int size:new int[]{0,1,15,16,17,31,32,33,1025})for(int chunk:new int[]{1,7,17})values.add(new Object[]{size,chunk});return values;}
    private final int size,chunk;private final byte[] plain;
    public SevenZAesShortReadMatrixTest(int size,int chunk){this.size=size;this.chunk=chunk;plain=new byte[size];new Random(913).nextBytes(plain);}
    private byte[] encrypted()throws Exception{Method method=SevenZAesStreamingTest.class.getDeclaredMethod("encrypt",byte[].class);method.setAccessible(true);return (byte[])method.invoke(null,(Object)plain);}
    private static final class Fragmented extends ByteArrayInputStream {
        private final int chunk;int closes;
        Fragmented(byte[] data,int chunk){super(data);this.chunk=chunk;}
        @Override public synchronized int read(byte[] b,int o,int n){return super.read(b,o,Math.min(n,chunk));}
        @Override public void close()throws IOException{closes++;super.close();}
    }
    private InputStream open(Fragmented packed,long length)throws Exception{return SevenZAesDecoder.decodeStream(packed,new byte[]{0x3f},"stream-test".toCharArray(),length);}
    @Test public void fragmentedCiphertextYieldsExactDeclaredPlaintext()throws Exception{
        Fragmented packed=new Fragmented(encrypted(),chunk);InputStream decoded=open(packed,size);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[19];try{assertEquals(0,decoded.read(buffer,5,0));int n;while((n=decoded.read(buffer,2,13))!=-1){assertTrue(n>0);out.write(buffer,2,n);}assertArrayEquals(plain,out.toByteArray());assertEquals(-1,decoded.read());}finally{decoded.close();decoded.close();}assertEquals(1,packed.closes);
    }
    @Test public void overstatedLengthFailureRemainsSticky()throws Exception{fails(encrypted(),size+16L);}
    @Test public void partialFinalCipherBlockNeverBecomesCleanEof()throws Exception{
        byte[] bytes=encrypted();fails(bytes.length==0?new byte[1]:Arrays.copyOf(bytes,bytes.length-1),size);
    }
    private void fails(byte[] bytes,long length)throws Exception{
        Fragmented packed=new Fragmented(bytes,chunk);InputStream decoded=open(packed,length);IOException failure;
        try{failure=assertThrows(IOException.class,()->{byte[] buffer=new byte[19];while(decoded.read(buffer)!=-1){}});assertSame(failure,assertThrows(IOException.class,()->decoded.read()));}finally{decoded.close();}assertEquals(1,packed.closes);
    }
}
