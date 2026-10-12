package com.readwide.manager.archive;

import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.codecs.PpmdZipInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Upstream plain ZIPs plus synthetic AES containers around their exact PPMd bytes. */
@RunWith(AndroidJUnit4.class)
public class ZipPpmdRoutingInstrumentedTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
    private static final char[] PASSWORD="readwide-test".toCharArray();
    private static final String[] NAMES={"smartd.conf","ts.conf","vimrc"};
    private static final int[] SIZES={6699,852,912};
    private static final long[] CRCS={0x8dd7379eL,0x7ae59b31L,0xba8e3baaL};
    @Before public void requireNative(){assertTrue(PpmdZipInputStream.isAvailable());}

    @Test public void selectedPlainPayloadUsesSupplementalReaderAndMatchesUpstreamCrc()throws Exception {
        File archive=archive(fixture(false));
        assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
        File output=temporary.newFile();
        assertEquals(Boolean.TRUE,ZipxAesArchiveReader.tryExtractSingleEntry(archive,"vimrc",output,null));
        check(output,912,CRCS[2]);
        assertTrue(ArchiveSupport.extractSingleEntry(archive,"vimrc",output,null));check(output,912,CRCS[2]);
    }

    @Test public void publicBulkRouteExtractsEveryPpmdMember()throws Exception {
        File archive=archive(fixture(true)),target=new File(temporary.newFolder(),"out");
        assertTrue(ArchiveSupport.extractArchive(archive,target,false,null));
        for(int i=0;i<NAMES.length;i++)check(new File(target,NAMES[i]),SIZES[i],CRCS[i]);
    }

    @Test public void badPlainCrcAndTrailingPackedDataRestoreSelectedTarget()throws Exception {
        byte[] packed=packed();
        for(byte[] bytes:new byte[][]{
                container(packed,98,0,CRCS[2]^1,new byte[0]),
                container(Arrays.copyOf(packed,packed.length+1),98,0,CRCS[2],new byte[0])}) {
            File output=temporary.newFile();write(output,new byte[]{9});
            assertFalse(ArchiveSupport.extractSingleEntry(archive(bytes),"vimrc",output,null));
            assertArrayEquals(new byte[]{9},read(output));
        }
    }

    @Test public void syntheticAe1AndAe2AllKeyStrengthsRetainAuthentication()throws Exception {
        for(int version:new int[]{1,2})for(int strength:new int[]{1,2,3}) {
            File archive=archive(aes(version,strength,false)),output=temporary.newFile();
            assertTrue(ZipxAesArchiveReader.canExtractArchive(archive));
            assertTrue(ArchiveSupport.extractSingleEntry(archive,"vimrc",output,PASSWORD.clone()));
            check(output,912,CRCS[2]);
        }
    }

    @Test public void wrongPasswordAndDamagedMacCannotCommitOutput()throws Exception {
        for(boolean damaged:new boolean[]{false,true}) {
            File output=temporary.newFile();write(output,new byte[]{9});
            char[] password=damaged ? PASSWORD.clone() : "incorrect".toCharArray();
            assertFalse(ArchiveSupport.extractSingleEntry(archive(aes(2,3,damaged)),"vimrc",output,password));
            assertArrayEquals(new byte[]{9},read(output));
        }
    }

    private byte[] aes(int version,int strength,boolean damage)throws Exception {
        return aes(version,strength,damage,packed());
    }

    @Test public void freezePlainSelectedAndBulkUseVerifiedRevisionSelection()throws Exception {
        byte[] packed=packed();packed[1]|=0x20;
        File file=archive(container(packed,98,0,CRCS[2],new byte[0])),out=temporary.newFile();
        ArchiveSupport.releaseViewerArchiveIndex(file); // also cover uncached public selected routing
        assertTrue(ArchiveSupport.extractSingleEntry(file,"vimrc",out,null));check(out,912,CRCS[2]);
        File folder=new File(temporary.newFolder(),"out");assertTrue(ArchiveSupport.extractArchive(file,folder,false,null));
        check(new File(folder,"vimrc"),912,CRCS[2]);
        // Small property-mutated payload: no model-exhaustion/real-producer claim.
    }

    @Test public void freezeAe1AndAe2AllStrengthsRetainSelectedAndBulkAuthentication()throws Exception {
        for(int version:new int[]{1,2})for(int strength:new int[]{1,2,3}) {
            byte[] packed=packed();packed[1]|=0x20;
            File file=archive(aes(version,strength,false,packed)),out=temporary.newFile();
            assertTrue(ArchiveSupport.extractSingleEntry(file,"vimrc",out,PASSWORD.clone()));check(out,912,CRCS[2]);
            File folder=new File(temporary.newFolder(),"out");assertTrue(ArchiveSupport.extractArchive(file,folder,false,PASSWORD.clone()));
            check(new File(folder,"vimrc"),912,CRCS[2]);
        }
    }

    @Test public void freezeCrcTailPasswordAndMacFailuresPreserveExistingOutputs()throws Exception {
        byte[] packed=packed();packed[1]|=0x20;
        byte[][] cases={container(packed,98,0,CRCS[2]^1,new byte[0]),
                container(Arrays.copyOf(packed,packed.length+1),98,0,CRCS[2],new byte[0]),
                aes(2,3,true,packed),aes(2,3,false,packed),
                aes(2,3,true,Arrays.copyOf(packed,packed.length-1))};
        for(int i=0;i<cases.length;i++) {
            File file=archive(cases[i]),out=temporary.newFile();write(out,new byte[]{9});
            char[] password=i<2?null:i==3?"incorrect".toCharArray():PASSWORD.clone();
            ArchiveSupport.releaseViewerArchiveIndex(file);
            assertFalse(ArchiveSupport.extractSingleEntry(file,"vimrc",out,password));assertArrayEquals(new byte[]{9},read(out));
            File folder=temporary.newFolder(),previous=new File(folder,"vimrc");write(previous,new byte[]{9});
            assertFalse(ArchiveSupport.extractArchive(file,folder,true,password));assertArrayEquals(new byte[]{9},read(previous));
        }
    }

    @Test public void freezeTraditionalEncryptionRetainsCrcAndPasswordChecks()throws Exception {
        byte[] packed=packed();packed[1]|=0x20;
        byte[] plain=new byte[12+packed.length];plain[11]=(byte)(CRCS[2]>>>24);
        System.arraycopy(packed,0,plain,12,packed.length);
        int[] keys={0x12345678,0x23456789,0x34567890};
        for(byte b:new String(PASSWORD).getBytes(StandardCharsets.UTF_8))updateZipKeys(keys,b&255);
        byte[] encrypted=new byte[plain.length];
        for(int i=0;i<plain.length;i++) {
            int key=keys[2]|2;encrypted[i]=(byte)((plain[i]&255)^((key*(key^1)>>>8)&255));
            updateZipKeys(keys,plain[i]&255);
        }
        File file=archive(container(encrypted,98,1,CRCS[2],new byte[0])),out=temporary.newFile();
        assertTrue(ArchiveSupport.extractSingleEntry(file,"vimrc",out,PASSWORD.clone()));check(out,912,CRCS[2]);
        File folder=new File(temporary.newFolder(),"out");assertTrue(ArchiveSupport.extractArchive(file,folder,false,PASSWORD.clone()));
        check(new File(folder,"vimrc"),912,CRCS[2]);
        write(out,new byte[]{9});assertFalse(ArchiveSupport.extractSingleEntry(file,"vimrc",out,"incorrect".toCharArray()));
        assertArrayEquals(new byte[]{9},read(out));
    }

    // Same test-only ZipCrypto construction used by Round4Fixtures.Crypto.
    private static void updateZipKeys(int[] keys,int value) {
        keys[0]=zipCrcByte(keys[0],value);keys[1]=(keys[1]+(keys[0]&255))*134775813+1;
        keys[2]=zipCrcByte(keys[2],keys[1]>>>24);
    }
    private static int zipCrcByte(int value,int next) {
        value^=next;for(int i=0;i<8;i++)value=(value>>>1)^((value&1)==0?0:0xedb88320);return value;
    }

    private byte[] aes(int version,int strength,boolean damage,byte[] compressed)throws Exception {
        int keyLength=8+strength*8,saltLength=4+strength*4;
        byte[] salt=new byte[saltLength];for(int i=0;i<salt.length;i++)salt[i]=(byte)(i+1);
        PBEKeySpec spec=new PBEKeySpec(PASSWORD,salt,1000,(keyLength*2+2)*8);
        byte[] derived;
        try{derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
        byte[] encrypted=compressed.clone();
        Cipher cipher=Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(Arrays.copyOf(derived,keyLength),"AES"));
        for(int offset=0,counter=1;offset<encrypted.length;offset+=16,counter++) {
            byte[] block=new byte[16];for(int i=0;i<4;i++)block[i]=(byte)(counter>>>(8*i));
            byte[] mask=cipher.doFinal(block);
            for(int i=0;i<16 && offset+i<encrypted.length;i++)encrypted[offset+i]^=mask[i];
        }
        Mac mac=Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(derived,keyLength,keyLength*2),"HmacSHA1"));
        byte[] tag=Arrays.copyOf(mac.doFinal(encrypted),10);if(damage)tag[9]^=1;
        ByteArrayOutputStream payload=new ByteArrayOutputStream();payload.write(salt);
        payload.write(derived,keyLength*2,2);payload.write(encrypted);payload.write(tag);Arrays.fill(derived,(byte)0);
        ByteArrayOutputStream extra=new ByteArrayOutputStream();u16(extra,0x9901);u16(extra,7);u16(extra,version);
        extra.write('A');extra.write('E');extra.write(strength);u16(extra,98);
        return container(payload.toByteArray(),99,1,version==1?CRCS[2]:0,extra.toByteArray());
    }

    private static byte[] container(byte[] payload,int method,int flags,long crc,byte[] extra)throws IOException {
        byte[] name="vimrc".getBytes(StandardCharsets.US_ASCII);ByteArrayOutputStream out=new ByteArrayOutputStream();
        u32(out,0x04034b50);u16(out,63);u16(out,flags);u16(out,method);u16(out,0);u16(out,0);
        u32(out,crc);u32(out,payload.length);u32(out,912);u16(out,name.length);u16(out,extra.length);
        out.write(name);out.write(extra);out.write(payload);int central=out.size();
        u32(out,0x02014b50);u16(out,63);u16(out,63);u16(out,flags);u16(out,method);u16(out,0);u16(out,0);
        u32(out,crc);u32(out,payload.length);u32(out,912);u16(out,name.length);u16(out,extra.length);
        u16(out,0);u16(out,0);u16(out,0);u32(out,0);u32(out,0);out.write(name);out.write(extra);
        int centralSize=out.size()-central;u32(out,0x06054b50);u16(out,0);u16(out,0);u16(out,1);u16(out,1);
        u32(out,centralSize);u32(out,central);u16(out,0);return out.toByteArray();
    }
    private static void u16(ByteArrayOutputStream out,int value){out.write(value&255);out.write((value>>>8)&255);}
    private static void u32(ByteArrayOutputStream out,long value){for(int i=0;i<4;i++)out.write((int)(value>>>(8*i))&255);}
    private static byte[] packed()throws IOException{return Arrays.copyOfRange(fixture(false),35,35+472);}
    private File archive(byte[] bytes)throws IOException{File file=temporary.newFile("ppmd-"+java.util.UUID.randomUUID()+".zipx");write(file,bytes);return file;}
    private static byte[] fixture(boolean multi)throws IOException {
        String name="test_read_format_zip_ppmd8"+(multi?"_multi":"")+".zipx.base64";
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            return Base64.decode(read(input),Base64.DEFAULT);
        }
    }
    private static void check(File file,int size,long expected)throws IOException {
        byte[] bytes=read(file);assertEquals(size,bytes.length);CRC32 crc=new CRC32();crc.update(bytes);assertEquals(expected,crc.getValue());
    }
    private static byte[] read(File file)throws IOException{try(InputStream input=new FileInputStream(file)){return read(input);}}
    private static byte[] read(InputStream input)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[1024];int n;
        while((n=input.read(bytes))!=-1)out.write(bytes,0,n);return out.toByteArray();
    }
    private static void write(File file,byte[] bytes)throws IOException{try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}}
}
