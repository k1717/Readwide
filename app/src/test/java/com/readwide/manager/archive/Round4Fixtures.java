package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Deterministic synthetic containers. Scalar reference transforms never call production filters. */
final class Round4Fixtures {
    static final char[] PASSWORD = "round4-password".toCharArray();
    static final int EGG=0x41474745, END=0x08e28222, SPLIT=0x24f5a262;
    static byte[] data(int count) { byte[] b=new byte[count];new Random(7134+count).nextBytes(b);return b; }
    static void le(ByteArrayOutputStream out,long value,int width){for(int i=0;i<width;i++)out.write((int)(value >>> (8*i)) &255);}
    static void number(ByteArrayOutputStream out,long value){out.write(255);le(out,value,8);}
    static long crc(byte[] b){CRC32 crc=new CRC32();crc.update(b);return crc.getValue();}
    static File write(File dir,String name,byte[] b)throws IOException {File f=new File(dir,name);Files.write(f.toPath(),b);return f;}
    static byte[] deflate(byte[] raw)throws IOException{
        Deflater deflater=new Deflater(6,true);
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();try(DeflaterOutputStream stream=new DeflaterOutputStream(out,deflater)){stream.write(raw);}return out.toByteArray();}
        finally{deflater.end();}
    }
    static byte[] alz(byte[] plain,int method,boolean encrypted)throws Exception{
        return alzStored(plain,method,method==2?deflate(plain):plain,encrypted);
    }
    static byte[] alzStored(byte[] plain,int method,byte[] stored,boolean encrypted)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();le(out,0x015a4c41,4);le(out,10,2);le(out,0,2);
        byte[] name="data.bin".getBytes(StandardCharsets.UTF_8);
        le(out,0x015a4c42,4);le(out,name.length,2);out.write(0x20);le(out,0,4);
        out.write(0x40|(encrypted?1:0));out.write(0);out.write(method);out.write(0);
        le(out,crc(plain),4);le(out,stored.length,4);le(out,plain.length,4);out.write(name);
        if(encrypted){Crypto c=new Crypto(PASSWORD);byte[] verify=new byte[12];verify[11]=(byte)(crc(plain)>>>24);out.write(c.encrypt(verify));out.write(c.encrypt(stored));}
        else out.write(stored);
        le(out,0x025a4c43,4);return out.toByteArray();
    }
    static List<File> splitAlz(File dir,String base,byte[] archive,int count)throws IOException{
        byte[] body=Arrays.copyOf(archive,archive.length-4);List<File> result=new ArrayList<>();
        for(int i=0;i<count;i++){
            int from=i==0?0:8+(body.length-8)*i/count;
            int to=8+(body.length-8)*(i+1)/count;
            ByteArrayOutputStream part=new ByteArrayOutputStream();
            if(i!=0){le(part,0x015a4c41,4);le(part,10,2);le(part,i,2);}
            part.write(body,from,to-from);le(part,0x015a4c43,4);le(part,0,8);le(part,i+1==count?0x025a4c43:0x035a4c43,4);
            String name=base+(i==0?".alz":String.format(Locale.ROOT,".a%02d",i-1));result.add(write(dir,name,part.toByteArray()));
        }return result;
    }
    static byte[] prefix(long id,long prev,long next,boolean split)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();le(out,EGG,4);le(out,0x100,2);le(out,id,4);le(out,0,4);
        if(split){le(out,SPLIT,4);out.write(0);le(out,8,2);le(out,prev,4);le(out,next,4);}le(out,END,4);return out.toByteArray();
    }
    static byte[] egg(byte[] plain,int method,boolean encrypted)throws Exception{
        byte[] stored=method==1?deflate(plain):method==3?azoStored(plain):plain;
        return eggStored(plain,method,stored,encrypted);
    }
    static byte[] eggStored(byte[] plain,int method,byte[] stored,boolean encrypted)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(prefix(17,0,0,false));
        le(out,0x0a8590e3,4);le(out,0,4);le(out,plain.length,8);
        byte[] name="data.bin".getBytes(StandardCharsets.UTF_8);le(out,0x0a8591ac,4);out.write(0);le(out,name.length,2);out.write(name);
        Crypto c=null;
        if(encrypted){c=new Crypto(PASSWORD);byte[] verify=new byte[12];verify[11]=(byte)(crc(plain)>>>24);
            le(out,0x08d1470f,4);out.write(0);le(out,17,2);out.write(0);out.write(c.encrypt(verify));le(out,crc(plain),4);}
        le(out,END,4);le(out,0x02b50c13,4);out.write(method);out.write(0);le(out,plain.length,4);le(out,stored.length,4);le(out,crc(plain),4);le(out,END,4);
        out.write(c==null?stored:c.encrypt(stored));le(out,END,4);return out.toByteArray();
    }
    static List<File> splitEgg(File dir,String base,byte[] archive,int count)throws IOException{
        byte[] body=Arrays.copyOfRange(archive,18,archive.length);List<File> result=new ArrayList<>();
        for(int i=0;i<count;i++){
            ByteArrayOutputStream part=new ByteArrayOutputStream();part.write(prefix(1001L+i,i==0?0:1000L+i,i+1==count?0:1002L+i,true));
            int start=body.length*i/count,end=body.length*(i+1)/count;part.write(body,start,end-start);
            result.add(write(dir,base+".vol"+(i+1)+".egg",part.toByteArray()));
        }return result;
    }
    static byte[] azoStored(byte[] plain)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(out);data.writeByte('1');data.writeByte(0);
        data.writeInt(plain.length);data.writeInt(plain.length);data.writeInt(0);data.write(plain);data.writeInt(0);data.writeInt(0);data.writeInt(0);return out.toByteArray();
    }
    static byte[] swap(byte[] raw,int width){
        byte[] output=raw.clone();for(int i=0;i+width<=raw.length;i+=width)for(int j=0;j<width;j++)output[i+j]=raw[i+width-1-j];return output;
    }
    static File sevenZ(File dir,byte[] raw,int width,boolean aes,boolean encodedHeader,boolean badCrc,boolean twoFiles)throws Exception{
        return sevenZ(dir,raw,width,aes,encodedHeader,badCrc,twoFiles,false);
    }
    static File sevenZ(File dir,byte[] raw,int width,boolean aes,boolean encodedHeader,boolean badCrc,boolean twoFiles,boolean headerOnly)throws Exception{
        Graph data=graph(raw,headerOnly?0:width,aes);ByteArrayOutputStream header=new ByteArrayOutputStream();header.write(1);header.write(4);streams(header,data,0,twoFiles,badCrc);
        header.write(5);number(header,twoFiles?2:1);byte[] names=(twoFiles?"1.bin\0"+"2.bin\0":"data.bin\0").getBytes(StandardCharsets.UTF_16LE);
        header.write(17);number(header,names.length+1);header.write(0);header.write(names);header.write(0);header.write(0);
        byte[] next=header.toByteArray();ByteArrayOutputStream packed=new ByteArrayOutputStream();packed.write(data.packed);
        if(encodedHeader){Graph encoded=graph(next,width,aes);ByteArrayOutputStream descriptor=new ByteArrayOutputStream();descriptor.write(23);streams(descriptor,encoded,packed.size(),false,false);packed.write(encoded.packed);next=descriptor.toByteArray();}
        ByteArrayOutputStream start=new ByteArrayOutputStream();le(start,packed.size(),8);le(start,next.length,8);le(start,crc(next),4);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});le(out,crc(start.toByteArray()),4);out.write(start.toByteArray());out.write(packed.toByteArray());out.write(next);
        return write(dir,"fixture.7z",out.toByteArray());
    }
    static Graph graph(byte[] raw,int width,boolean aes)throws Exception{
        byte[] packed=width==0?raw.clone():swap(raw,width);Graph g=new Graph(raw,packed);
        if(aes){byte[] key=Arrays.copyOf(new String(PASSWORD).getBytes(StandardCharsets.UTF_16LE),32);Cipher c=Cipher.getInstance("AES/CBC/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(new byte[16]));g.packed=c.doFinal(Arrays.copyOf(packed,((packed.length+15)/16)*16));g.add(new byte[]{6,(byte)0xf1,7,1},new byte[]{0x3f},packed.length);}
        g.add(width==0?new byte[]{0}:new byte[]{2,3,(byte)width},new byte[0],raw.length);return g;
    }
    static void streams(ByteArrayOutputStream out,Graph graph,long pos,boolean twoFiles,boolean badCrc)throws IOException{
        out.write(6);number(out,pos);number(out,1);out.write(9);number(out,graph.packed.length);out.write(10);out.write(1);le(out,crc(graph.packed),4);out.write(0);
        out.write(7);out.write(11);number(out,1);out.write(0);number(out,graph.ids.size());
        for(int i=0;i<graph.ids.size();i++){byte[] id=graph.ids.get(i),props=graph.props.get(i);out.write(id.length|0x20);out.write(id);number(out,props.length);out.write(props);}
        for(int i=0;i+1<graph.ids.size();i++){number(out,i+1);number(out,i);}out.write(12);for(long size:graph.sizes)number(out,size);
        out.write(10);out.write(1);le(out,crc(graph.raw)^(badCrc?1:0),4);out.write(0);
        if(twoFiles){int first=graph.raw.length/2;out.write(8);out.write(13);number(out,2);out.write(9);number(out,first);out.write(10);out.write(1);le(out,crc(Arrays.copyOfRange(graph.raw,0,first)),4);le(out,crc(Arrays.copyOfRange(graph.raw,first,graph.raw.length)),4);out.write(0);}out.write(0);
    }
    static final class Graph {final byte[] raw;byte[] packed;final List<byte[]>ids=new ArrayList<>(),props=new ArrayList<>();final List<Long>sizes=new ArrayList<>();Graph(byte[]raw,byte[]packed){this.raw=raw;this.packed=packed;}void add(byte[]id,byte[]p,long size){ids.add(id);props.add(p);sizes.add(size);}}
    static final class Crypto {
        int a=0x12345678,b=0x23456789,c=0x34567890;
        Crypto(char[] password){for(byte ch:new String(password).getBytes(StandardCharsets.UTF_8))update(ch&255);}
        static int crcByte(int value,int next){value^=next;for(int i=0;i<8;i++)value=(value>>>1)^((value&1)==0?0:0xedb88320);return value;}
        void update(int v){a=crcByte(a,v);b=(b+(a&255))*134775813+1;c=crcByte(c,b>>>24);}
        byte[] encrypt(byte[] plain){byte[] out=new byte[plain.length];for(int i=0;i<plain.length;i++){int k=c|2;out[i]=(byte)((plain[i]&255)^((k*(k^1)>>>8)&255));update(plain[i]&255);}return out;}
    }

 // Reused byte-for-byte from the existing ALZ/EGG tests; original provenance remains in those files.
    static final String ALZ_BZ_SINGLE_BLOCK_B64 =
            "RExaAQABPz8AgAIgAEAIACB9W5BgQAEgUDTQyMmIFKpGhpk0PSaZCaidxNxOwmwmBPAmgnkTAmYmYToJsJ9EwJwJyJgTgTkT"
            + "8JyJoEyE2CaiZif0RMWgIA==";
    static final String AES128_STORE_B64 =
            "RUdHQQABAQAAAAAAAAAiguII45CFCgAAAAC+AAAAAAAAAKyRhQoACgBzZWNyZXQudHh0C5WGLAAJACPJo09j+8cBAA9H0QgA"
            + "FQABAQIDBAUGBwh2Eky8MBmYFnVCA9YiguIIEwy1AgAAvgAAAL4AAACZDU3EIoLiCImweUKQpSKfqEHDJKqaVSjdAJhya8m6"
            + "B2MyZuYurP5mYYPIE37f88VDiFXAyUv4LTWq+ZSQkuspVTYryCyherGWa+6lRlp2FVrC8Uqm2t3Kdxp/61AlpXN7nj9bnRJy"
            + "bSxIRkqNd/j5G93WzHN2nILS6Jum2eQhc85D/IVEyLG55mhpVQtcauOF+W77feQr8dyJ6ihyiQq4fB3r3LR4SufY4nsdGTkS"
            + "tNfRhw+MSq1wLbmt8xSlDfSYTMs29xoiguII";
    static final String AES256_STORE_B64 =
            "RUdHQQABAQAAAAAAAAAiguII45CFCgAAAAC+AAAAAAAAAKyRhQoACgBzZWNyZXQudHh0C5WGLAAJACPJo09j+8cBAA9H0QgA"
            + "HQACAQIDBAUGBwgJCgsMDQ4PEGaDGlyOzJ+HOQVcEiKC4ggTDLUCAAC+AAAAvgAAAJkNTcQiguIIkrfZyUdDmU0+pbwqtuix"
            + "bIK8F358044J7uhR7JcWGxHkmhdsGYg4WGBxgwAMA+4p/yGnzrJb5i2wcHGYYbB+M+c5ODefSuFgUazObgrlSvDZrn+p0GXU"
            + "dYxV9dUS6SjVbqvXOJb7NXTJimCK9YoPTPShTNYK0r+UyFpOayRBION4/S8E8yhtMX1C+mF7GvFMYOYg9LZgtOOx6D+63Qdb"
            + "4LZZSpD658u5BRVCiQ5gEyMD2anKhFPUatdPho4sbSKC4gg=";
    static final String AES256_DEFLATE_B64 =
            "RUdHQQABAQAAAAAAAAAiguII45CFCgAAAAAOAQAAAAAAAKyRhQoACgBzZWNyZXQudHh0C5WGLAAJACPJo09j+8cBAA9H0QgA"
            + "HQACAQIDBAUGBwgJCgsMDQ4PEGaDu1YpuUMoqScm2SKC4ggTDLUCAQAOAQAAIAAAAMnhYlAiguIIuJ6HvxxotxER/M0SntwW"
            + "Ss+RYjdRDGKzxMdywf9POXAiguII";
}
