package com.readwide.manager.archive;

import org.junit.Test;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Synthetic coder-level fixtures; not evidence of complete 7z-container compatibility. */
public class SevenZAesReviewTest {
    private interface Action { void run() throws Exception; }
    private static IOException rejected(Action action) throws Exception {
        try { action.run(); fail("Expected IOException"); return null; }
        catch (IOException expected) { return expected; }
    }

    @Test public void canonicalOneByteDirectKeyPropertiesDecode() throws Exception {
        roundTrip(new byte[]{63}, "secret".toCharArray(), new byte[0], new byte[16], 63);
    }
    @Test public void canonicalOneByteSha256PropertiesDecode() throws Exception {
        roundTrip(new byte[]{0}, "secret".toCharArray(), new byte[0], new byte[16], 0);
        roundTrip(new byte[]{5}, "secret".toCharArray(), new byte[0], new byte[16], 5);
    }
    @Test public void saltedIvPropertiesDecode() throws Exception {
        byte[] salt={1,2,3,4}, iv=new byte[16];
        for(int i=0;i<iv.length;i++)iv[i]=(byte)(i*7);
        byte[] props=new byte[22];props[0]=(byte)(0xc0|3);props[1]=0x3f;
        System.arraycopy(salt,0,props,2,4);System.arraycopy(iv,0,props,6,16);
        roundTrip(props,"salted".toCharArray(),salt,iv,3);
    }
    @Test public void legacyTwoByteNoSaltFormRemainsSupported() throws Exception {
        roundTrip(new byte[]{63,0},"legacy".toCharArray(),new byte[0],new byte[16],63);
    }
    @Test public void unreasonableWorkFactorsAreRejectedWithoutDerivation() throws Exception {
        for(int power=25;power<63;power++){
            final int p=power;
            for (byte[] properties : new byte[][] {
                    {(byte) p}, {(byte) p, 0}, {(byte) (0x40 | p), 0, 0}}) {
                IOException error=rejected(()->SevenZAesDecoder.validateProperties(properties));
                assertTrue(error instanceof ArchiveSupport.UnsupportedArchiveFeatureException);
            }
        }
        SevenZAesDecoder.validateProperties(new byte[]{24});
        SevenZAesDecoder.validateProperties(new byte[]{63});
    }
    @Test public void malformedPropertyLengthsAreRejected() throws Exception {
        for(byte[] p:new byte[][]{ {}, {0,1}, {0,0,0}, {(byte)0x80}, {(byte)0x80,0}, {(byte)0x80,0,1,2}})
            rejected(()->SevenZAesDecoder.validateProperties(p));
    }
    @Test public void cancellationBeforeKdfPreservesInterruptFlag() throws Exception {
        Thread.currentThread().interrupt();
        try {
            IOException error=rejected(()->SevenZAesDecoder.decode(new byte[0],new byte[]{0},new char[]{'x'},0));
            assertTrue(error instanceof InterruptedIOException);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
    @Test public void inFlightKdfCanBeInterrupted() throws Exception {
        CountDownLatch entered=new CountDownLatch(1);
        AtomicReference<Throwable> outcome=new AtomicReference<>();
        Thread worker=new Thread(()->{
            entered.countDown();
            try {SevenZAesDecoder.decode(new byte[0],new byte[]{24,0},new char[]{'x'},0);}
            catch(Throwable error){outcome.set(error);}
        },"aes-review-cancellation");
        worker.setDaemon(true);worker.start();entered.await();Thread.sleep(20);worker.interrupt();worker.join(2000);
        assertFalse("KDF did not retire after cancellation",worker.isAlive());
        assertTrue("KDF should report interrupted I/O",outcome.get() instanceof InterruptedIOException);
    }
    @Test public void byteArrayDecoderRejectsDeclaredSizeMismatch() throws Exception {
        byte[] encrypted=encrypt(new byte[32],new char[]{'x'},new byte[0],new byte[16],63);
        rejected(()->SevenZAesDecoder.decode(encrypted,new byte[]{63},new char[]{'x'},1));
        rejected(()->SevenZAesDecoder.decode(encrypted,new byte[]{63},new char[]{'x'},33));
    }
    @Test public void streamFailureCannotTurnIntoSuccessfulEof() throws Exception {
        byte[] encrypted=encrypt(new byte[32],new char[]{'x'},new byte[0],new byte[16],63);
        try(InputStream stream=SevenZAesDecoder.decodeStream(new ByteArrayInputStream(encrypted),new byte[]{63},new char[]{'x'},1)){
            IOException first=rejected(stream::read);
            IOException next=rejected(stream::read);
            assertSame(first,next);
        }
    }
    @Test public void utf16CodeUnitsAndCallerPasswordArePreserved() throws Exception {
        char[] password={'a','\uD801','\uDC00','\uD800','z'};
        char[] original=password.clone();
        roundTrip(new byte[]{63},password,new byte[0],new byte[16],63);
        assertArrayEquals(original,password);
    }
    @Test public void streamRejectsReadsAfterClose() throws Exception {
        InputStream stream=SevenZAesDecoder.decodeStream(new ByteArrayInputStream(new byte[0]),new byte[]{63},new char[]{'x'},0);
        stream.close();stream.close();rejected(stream::read);
    }

    private static void roundTrip(byte[] props,char[] password,byte[] salt,byte[] iv,int power)throws Exception {
        for(int length:new int[]{0,1,15,16,17,19,32,4097}){
            byte[] plain=new byte[length];for(int i=0;i<length;i++)plain[i]=(byte)(i*31+9);
            byte[] encrypted=encrypt(plain,password,salt,iv,power);
            assertArrayEquals(plain,SevenZAesDecoder.decode(encrypted,props,password,length));
            try(InputStream stream=SevenZAesDecoder.decodeStream(new ByteArrayInputStream(encrypted),props,password,length)){
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[13];int n;
                while((n=stream.read(buffer))!=-1)out.write(buffer,0,n);
                assertArrayEquals(plain,out.toByteArray());
            }
        }
    }
    private static byte[] encrypt(byte[] plain,char[] password,byte[] salt,byte[] iv,int power)throws Exception {
        byte[] pass=new byte[password.length*2];
        for(int i=0;i<password.length;i++){pass[2*i]=(byte)password[i];pass[2*i+1]=(byte)(password[i]>>>8);}
        byte[] key;
        if(power==63){key=new byte[32];System.arraycopy(salt,0,key,0,salt.length);System.arraycopy(pass,0,key,salt.length,Math.min(pass.length,32-salt.length));}
        else {
            // Independent fixture construction using an explicit little-endian counter.
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            for(long round=0;round<(1L<<power);round++){
                hash.update(salt);hash.update(pass);for(int b=0;b<8;b++)hash.update((byte)(round>>>(8*b)));
            }
            key=hash.digest();
        }
        Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
        return cipher.doFinal(Arrays.copyOf(plain,((plain.length+15)/16)*16));
    }
}
