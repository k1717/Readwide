package com.readwide.manager.archive;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** CBC blocks, per-part packed CRCs and the final RAR5 hash-MAC have different boundaries. */
@RunWith(Parameterized.class)
public final class RarStoredAesBoundaryMatrixTest {
    @Parameterized.Parameters(name="plainBytes={0}, unknownSize={1}") public static Collection<Object[]> variants(){
        List<Object[]> variants=new ArrayList<>();
        for(int size:new int[]{1,15,16,17,31,32,33,65537}) for(boolean unknown:new boolean[]{false,true}) variants.add(new Object[]{size,unknown});
        return variants;
    }
    @Rule public final TemporaryFolder temp=new TemporaryFolder();private final int size;private final boolean unknown;
    private static final char[] PASSWORD="boundary-password".toCharArray();
    private byte[] plain;
    public RarStoredAesBoundaryMatrixTest(int size,boolean unknown){this.size=size;this.unknown=unknown;}
    private List<RarArchiveReader.RarEntry> fixture(boolean badPackedCrc,boolean badFinal)throws Exception{
        plain=new byte[size];new Random(54321).nextBytes(plain);Rar5Crypto.Secrets secrets=Rar5Crypto.deriveSecrets(PASSWORD,1,new byte[16]);Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secrets.key,"AES"),new IvParameterSpec(new byte[16]));byte[] packed=cipher.doFinal(Arrays.copyOf(plain,(size+15)/16*16));
        TreeSet<Integer> cuts=new TreeSet<>();cuts.add(0);for(int cut:new int[]{1,15,16,17,31,32,65535,65536})if(cut<packed.length)cuts.add(cut);cuts.add(packed.length);Integer[] points=cuts.toArray(new Integer[0]);List<RarArchiveReader.RarEntry> entries=new ArrayList<>();
        for(int i=0;i<points.length-1;i++){
            byte[] data=Arrays.copyOfRange(packed,points[i],points[i+1]);boolean last=i==points.length-2;File file=temp.newFile("data.part"+(i+1)+".rar");Files.write(file.toPath(),data);
            RarArchiveReader.EncryptionInfo enc=new RarArchiveReader.EncryptionInfo(0,last?2:0,1,new byte[16],new byte[16],new byte[0]);long expected=last?Rar5Crypto.tweakCrc32(crc(plain),secrets):crc(data);if((i==0&&badPackedCrc)||(last&&badFinal))expected^=1;
            RarArchiveReader.RarEntry entry=new RarArchiveReader.RarEntry("data.bin",false,unknown?-1:size,data.length,0,5,0,false,i>0,!last,enc,expected,0);entry.sourceArchive=file;entries.add(entry);
        }return entries;
    }
    private File target()throws Exception{File out=temp.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"keep");return out;}
    @Test public void cbcAndHashMacRemainCorrectAcrossByteAndBlockCuts()throws Exception{
        List<RarArchiveReader.RarEntry> entries=fixture(false,false);File out=target();RarSplitStoredExtractor.extract(entries.get(0),out,PASSWORD,entries,null);assertArrayEquals(plain,Files.readAllBytes(out.toPath()));
    }
    @Test public void badPackedPartCrcPreservesDestination()throws Exception{reject(fixture(true,false),PASSWORD);}
    @Test public void badFinalHashMacPreservesDestination()throws Exception{reject(fixture(false,true),PASSWORD);}
    @Test public void wrongPasswordPreservesDestination()throws Exception{reject(fixture(false,false),"wrong".toCharArray());}
    @Test public void physicallyTruncatedLastPartPreservesDestination()throws Exception{
        List<RarArchiveReader.RarEntry> entries=fixture(false,false);try(RandomAccessFile raw=new RandomAccessFile(entries.get(entries.size()-1).sourceArchive,"rw")){raw.setLength(raw.length()-1);}reject(entries,PASSWORD);
    }
    private void reject(List<RarArchiveReader.RarEntry> entries,char[] password)throws Exception{File out=target();assertThrows(IOException.class,()->RarSplitStoredExtractor.extract(entries.get(0),out,password,entries,null));assertEquals("keep",ArchiveTestTextFiles.readString(out.toPath()));}
    private static long crc(byte[] data){CRC32 crc=new CRC32();crc.update(data);return crc.getValue();}
}
