package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Independent fixture writer: JCE PBKDF2 (not production's manual derivation). */
final class Round6Fixtures {
    // Copied from the pre-existing AlzipArchiveReaderTest fixture; no new vendor provenance claimed.
    static final String MULTI_BZIP = "RExaAQAEfT8Af/////////////8AgAUwAAA5hNAaA0aMI0GI0xMmJoMI0DIBkwOYTQGgNGjCNBiNMTJiaDCNAyAZMBNVVRiZ"
            + "NMjEMmI0aMQDE0aGBAyDRpkZNDmE0BoDRowjQYjTEyYmgwjQMgGTDoQKsrpwFWBAVYMBVhQFXUgKsOAqxICrqwFWLAVdaAqx"
            + "oCrHgKuvAVdiAqyICrJgKsqAqyUCrsoFXaQKu2gVZaBVmIFXcQKs1AqzkCrPQKu6gVd5Aq0ECrvoFXgQKtFAq8KBV4kCrSQK"
            + "tNAq1ECrxoFXkQKvKgVeZAq86BV6ECr0oFWqgVayBVroFXqQKthAq9aBV7ECrZQKvagVe5Aq96BVtIFW2gVbiBVuoFW8gVb6"
            + "BV8ECr4oFXyQKvmgVfRAq4ECrhQKvqgVfZAq+6BV+ECriQKvygVfpAq40Cr9oFXIgVcqBV/ECr+oFXMgVc6BV/kCroQKv+iJ"
            + "i0AgCqjn4A//////////////4BAApgAABzCaA0Bo0YRoMRpiZMTQYRoGQDJgcwmgNAaNGEaDEaYmTE0GEaBkAyYCaqqjEyaZ"
            + "GIZMRo0ZAGJo0MCBkGmmIyaHMJoDQGjRhGgxGmJkxNBhGgZAMmHQgVdJAq6aBVgIFWCgVYSBV1ECrDQKsRAq6qBVioFXWQKs"
            + "ZAqx0CrroFXYQKshAqyUCrKQKtLtQFXbgKsuAqzICruQFWbAVZ0BVnwFXdgKu9AVaEBV34CrwQFWjAVeGAq8UBVpQFWSgVaa"
            + "BV40CryIFXlQKvMgVedAq9CBV6UCr1IFWogVaqBVrIFXrQKtdAq9iBV7UCrYQKvcgVe9Aq+CBVsoFW0gVbaBVuIFW6gVbyBV"
            + "8UCr5IFXzQKvogVfVAq30CrgQKvsgVfdAq/CBV+UCrhQKv0gVcSBV+0CrjQKuRAq5UCr+IFX9QKuZAq50Cr/IFXQgVf9ETFo"
            + "BAGO6PwB//////////////wCABLgAAHMJoDQGjRhGgxGmJkxNBhGgZAMmBzCaA0Bo0YRoMRpiZMTQYRoGQDJgcwmgNAaNGEa"
            + "DEaYmTE0GEaBkAyYBSqqGTRpkMhkxGjTCZGmJo0MCBpkGQMmnQik6SKTpopOoikwEUmCik6qKTCRSdZFJ10UmGik7CKTERSY"
            + "qKTsopO0ik7aKTGRSY6KTuIpO6ik7yKTIRSZKKTvopMpFJlopMxFJ4EUnhRSZqKTxIpPGikzkUnkRSZ6KTQRSaulRSeWik06"
            + "KTUopPNRSeeik1aKTCRSehFJrIpNdFJsIpPSik2UUnqRSetFJtIpPYik9qKT3IpNtFJuIpN1FJvIpN9FJwIpPeik+CKT4opP"
            + "kik+aKThRSfRFJxIpONFJ9UUn2RSfdFJ+EUnIik5UUn5RSfpFJ+0Un8RSf1FJzIpOdFJ/kUnQik/6ImLQEA=";
    static final char[] PASSWORD = "round6-test-password".toCharArray();
    static final byte[] PLAIN = "checked output\n".getBytes(StandardCharsets.US_ASCII);

    static File aesEgg(File directory, int bits, int blocks, int paddedFirst, boolean prefixMac, boolean tamperTail) throws Exception {
        byte[][] packed = new byte[blocks][];
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int i=0; i<blocks; i++) {
            byte[] deflated = Round4Fixtures.deflate(PLAIN);
            packed[i] = Arrays.copyOf(deflated, i==0 ? Math.max(deflated.length,paddedFirst) : deflated.length);
            all.write(packed[i]);
        }
        byte[] salt = new byte[bits==128?8:16];
        for(int i=0;i<salt.length;i++)salt[i]=(byte)(i+1);
        int keyLen=bits/8;
        PBEKeySpec spec = new PBEKeySpec(PASSWORD,salt,1000,(keyLen*2+2)*8);
        byte[] derived;
        try { derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        Cipher cipher=Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(Arrays.copyOfRange(derived,0,keyLen),"AES"));
        byte[] ciphertext=all.toByteArray();
        byte[] counter=new byte[16];
        for(int start=0,ordinal=1;start<ciphertext.length;start+=16,ordinal++){
            Arrays.fill(counter,(byte)0);for(int i=0;i<4;i++)counter[i]=(byte)(ordinal>>>(8*i));
            byte[] stream=cipher.doFinal(counter);
            for(int i=0;i<16&&start+i<ciphertext.length;i++)ciphertext[start+i]^=stream[i];
        }
        Mac mac=Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(derived,keyLen,keyLen*2),"HmacSHA1"));
        mac.update(ciphertext,0,prefixMac?65536:ciphertext.length);
        byte[] footer=Arrays.copyOf(mac.doFinal(),10);
        if(tamperTail)ciphertext[ciphertext.length-1]^=1;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(Round4Fixtures.prefix(41,0,0,false));
        le(out,0x0a8590e3,4);le(out,0,4);le(out,PLAIN.length*blocks,8);
        byte[] name="data.bin".getBytes(StandardCharsets.US_ASCII);
        le(out,0x0a8591ac,4);out.write(0);le(out,name.length,2);out.write(name);
        le(out,0x08d1470f,4);out.write(0);le(out,1+salt.length+2+10,2);out.write(bits==128?1:2);
        out.write(salt);out.write(derived,keyLen*2,2);out.write(footer);le(out,Round4Fixtures.END,4);
        int cursor=0;
        for(byte[] block:packed){
            le(out,0x02b50c13,4);out.write(1);out.write(0);le(out,PLAIN.length,4);le(out,block.length,4);le(out,Round4Fixtures.crc(PLAIN),4);le(out,Round4Fixtures.END,4);
            out.write(ciphertext,cursor,block.length);cursor+=block.length;
        }
        le(out,Round4Fixtures.END,4);
        return Round4Fixtures.write(directory,"fixture.egg",out.toByteArray());
    }

    static byte[] endRecord(long disk,long centralDisk,byte[] comment) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();le(out,0x06054b50,4);le(out,disk,2);le(out,centralDisk,2);
        le(out,0,2);le(out,0,2);le(out,0,4);le(out,0,4);le(out,comment.length,2);out.write(comment,0,comment.length);return out.toByteArray();
    }
    static List<File> zipSet(File directory,int parts,String extension,boolean zip64)throws Exception{
        List<File> result=new ArrayList<>();
        for(int i=1;i<=parts;i++)result.add(Round4Fixtures.write(directory,String.format(Locale.ROOT,"book.z%02d",i),new byte[]{(byte)i}));
        ByteArrayOutputStream last=new ByteArrayOutputStream();
        if(zip64){le(last,0x07064b50,4);le(last,parts,4);le(last,0,8);le(last,(long)parts+1,4);}
        last.write(endRecord(zip64?65535:parts,zip64?65535:parts,new byte[0]));
        result.add(Round4Fixtures.write(directory,"book"+extension,last.toByteArray()));return result;
    }
    static void le(ByteArrayOutputStream out,long value,int width){Round4Fixtures.le(out,value,width);}
}
