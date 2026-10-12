package com.readwide.manager.archive;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic 7z/AES routing around raw LZHAM blocks; no producer claim. */
@RunWith(AndroidJUnit4.class)
public class SevenZLzhamInstrumentedTest {
    private static final byte[] PAYLOAD={1,2,3,4,5,6,7,8};
    private static final byte[] ORIGINAL={77,78};
    private static final char[] PASSWORD={'p'};

    @Test public void plainAndEncryptedPublicSelectedAndForwardRoutes() throws Exception {
        for(int version:new int[]{0x10,0x11}) for(boolean encrypted:new boolean[]{false,true}) {
            File archive=fixture(encrypted,false,false,0,version),out=temporary(".out");
            try {
                assertEquals(1,ArchiveSupport.listEntries(archive,PASSWORD).size());
                assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive,"a.bin",out,PASSWORD).success);
                assertArrayEquals(PAYLOAD,read(out));
                try(ArchiveSupport.ForwardArchiveReader reader=SevenZBcj2ArchiveReader
                        .openSpecialForwardReader(archive,PASSWORD,archive.getParentFile())) {
                    assertNotNull(reader);assertNotNull(reader.nextEntry());
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[3];int n;
                    while((n=reader.read(buffer))!=-1)bytes.write(buffer,0,n);
                    assertArrayEquals(PAYLOAD,bytes.toByteArray());assertNull(reader.nextEntry());
                }
            } finally {archive.delete();out.delete();}
        }
    }

    @Test public void publicBulkUsesRegisteredDecoder() throws Exception {
        for(int version:new int[]{0x10,0x11}) for(boolean encrypted:new boolean[]{false,true}) {
            File archive=fixture(encrypted,false,false,0,version),directory=temporary(".directory");
            assertTrue(directory.delete());assertTrue(directory.mkdir());
            File out=new File(directory,"a.bin");
            try {
                assertTrue(ArchiveSupport.extractArchive(archive,directory,true,PASSWORD));
                assertArrayEquals(PAYLOAD,read(out));
            } finally {archive.delete();out.delete();directory.delete();}
        }
    }

    @Test public void adlerAndFolderCrcFailuresRestoreExistingTarget() throws Exception {
        for(int version:new int[]{0x10,0x11}) for(boolean adler:new boolean[]{false,true}) {
            File archive=fixture(true,!adler,adler,0,version),out=temporary(".out");
            try {
                write(out,ORIGINAL);
                assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"a.bin",out,PASSWORD).success);
                assertArrayEquals(ORIGINAL,read(out));
            } finally {archive.delete();out.delete();}
        }
    }

    @Test public void wrongPasswordAndOutputSizeCannotCommit() throws Exception {
        for(int version:new int[]{0x10,0x11}) for(int delta:new int[]{-1,0,1}) {
            File archive=fixture(true,false,false,delta,version),out=temporary(".out");
            try {
                write(out,ORIGINAL);
                assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"a.bin",out,
                        delta==0?new char[]{'x'}:PASSWORD).success);
                assertArrayEquals(ORIGINAL,read(out));
            } finally {archive.delete();out.delete();}
        }
    }

    @Test public void unverifiedPropertyVersionIsNotDecodedAsCurrent() throws Exception {
        for(int version:new int[]{0x0f,0x12,255}) {
            File archive=fixture(false,false,false,0,version),out=temporary(".out");
            try {
                write(out,ORIGINAL);
                assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"a.bin",out,PASSWORD).success);
                assertArrayEquals(ORIGINAL,read(out));
            } finally {archive.delete();out.delete();}
        }
    }

    private static File fixture(boolean encrypted,boolean badCrc,boolean badAdler,int delta,int version) throws Exception {
        ByteArrayOutputStream stream=new ByteArrayOutputStream();
        int n=PAYLOAD.length-1;long bits=((2L<<32)|((long)n<<8)|n)<<6;
        for(int shift=32;shift>=0;shift-=8)stream.write((int)(bits>>>shift)&255);
        stream.write(PAYLOAD);stream.write(0xc0);
        Adler32 adler=new Adler32();adler.update(PAYLOAD);
        long checksum=adler.getValue()^(badAdler?1:0);
        for(int shift=24;shift>=0;shift-=8)stream.write((int)(checksum>>>shift)&255);
        byte[] frame=stream.toByteArray(),packed=frame;
        if(encrypted) {
            byte[] key=new byte[32];key[0]='p';
            Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(new byte[16]));
            packed=cipher.doFinal(Arrays.copyOf(frame,(frame.length+15)/16*16));
        }
        ByteArrayOutputStream header=new ByteArrayOutputStream();
        header.write(new byte[]{1,4,6,0,1,9,(byte)packed.length,10,1});little(header,crc(packed),4);
        header.write(new byte[]{0,7,11,1,0,(byte)(encrypted?2:1)});
        if(encrypted)header.write(new byte[]{0x24,6,(byte)0xf1,7,1,1,0x3f});
        header.write(new byte[]{0x24,4,(byte)0xf7,0x10,1,5,(byte)version,15,4,0,0});
        if(encrypted)header.write(new byte[]{1,0});
        header.write(12);if(encrypted)header.write(frame.length);header.write(PAYLOAD.length+delta);
        header.write(new byte[]{10,1});little(header,crc(PAYLOAD)^(badCrc?1:0),4);
        header.write(new byte[]{0,0,5,1,17,13,0});
        header.write("a.bin\0".getBytes(StandardCharsets.UTF_16LE));header.write(new byte[]{0,0});
        ByteArrayOutputStream start=new ByteArrayOutputStream();
        little(start,packed.length,8);little(start,header.size(),8);little(start,crc(header.toByteArray()),4);
        ByteArrayOutputStream archive=new ByteArrayOutputStream();
        archive.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});
        little(archive,crc(start.toByteArray()),4);archive.write(start.toByteArray());archive.write(packed);archive.write(header.toByteArray());
        File file=temporary(".7z");write(file,archive.toByteArray());return file;
    }
    private static File temporary(String suffix) throws Exception {
        return File.createTempFile("seven-lzham-",suffix,InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
    }
    private static void write(File file,byte[] bytes) throws Exception {try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}}
    private static byte[] read(File file) throws Exception {
        try(InputStream in=new FileInputStream(file)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[37];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();
        }
    }
    private static long crc(byte[] bytes){CRC32 crc=new CRC32();crc.update(bytes);return crc.getValue();}
    private static void little(ByteArrayOutputStream out,long value,int count){for(int i=0;i<count;i++)out.write((int)(value>>>(8*i))&255);}
}
