package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Synthetic containers; expected bytes and transforms do not use production decoders. */
final class Round5Fixtures {
    private Round5Fixtures() {}
    static void le(ByteArrayOutputStream out, long value, int width) { Round4Fixtures.le(out, value, width); }
    static void number(ByteArrayOutputStream out, long value) { Round4Fixtures.number(out, value); }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    static File egg(File directory, String[] names, byte[][] payloads, boolean solid,
                    int[] ends, int badBlock, int method) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        le(out, Round4Fixtures.EGG, 4); le(out, 0x100, 2); le(out, 151, 4); le(out, 0, 4);
        if (solid) { le(out, 0x24e5a060, 4); out.write(0); le(out, 0, 2); }
        le(out, Round4Fixtures.END, 4);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        for (int i = 0; i < names.length; i++) {
            byte[] name = bytes(names[i]);
            le(out, 0x0a8590e3, 4); le(out, i, 4); le(out, payloads[i].length, 8);
            le(out, 0x0a8591ac, 4); out.write(0); le(out, name.length, 2); out.write(name);
            le(out, Round4Fixtures.END, 4);
            if (solid) plain.write(payloads[i]);
            else if (!names[i].endsWith("/")) block(out, payloads[i], method, i == badBlock);
        }
        if (solid) {
            byte[] all = plain.toByteArray(); int start = 0;
            for (int i = 0; i < ends.length; i++) {
                block(out, Arrays.copyOfRange(all, start, ends[i]), method, i == badBlock); start = ends[i];
            }
        }
        le(out, Round4Fixtures.END, 4);
        return Round4Fixtures.write(directory, "fixture.egg", out.toByteArray());
    }
    private static void block(ByteArrayOutputStream out, byte[] plain, int method, boolean bad) throws Exception {
        byte[] stored = method == 1 ? Round4Fixtures.deflate(plain) : plain;
        le(out, 0x02b50c13, 4); out.write(method); out.write(0);
        le(out, plain.length, 4); le(out, stored.length, 4);
        le(out, Round4Fixtures.crc(plain) ^ (bad ? 1 : 0), 4);
        le(out, Round4Fixtures.END, 4); out.write(stored);
    }
    static File wrap(File dir, byte[] packed, byte[] header) throws IOException {
        ByteArrayOutputStream start = new ByteArrayOutputStream();
        le(start, packed.length, 8); le(start, header.length, 8); le(start, Round4Fixtures.crc(header), 4);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});
        le(out, Round4Fixtures.crc(start.toByteArray()), 4);
        out.write(start.toByteArray()); out.write(packed); out.write(header);
        return Round4Fixtures.write(dir, "fixture.7z", out.toByteArray());
    }
    static byte[] emptyProperties(int count, boolean named) throws IOException {
        ByteArrayOutputStream props = new ByteArrayOutputStream();
        byte[] bits = new byte[(count + 7) / 8]; Arrays.fill(bits, (byte)0xff);
        for (int id : new int[]{14,15}) { props.write(id); number(props, bits.length); props.write(bits); }
        if (named) {
            ByteArrayOutputStream names = new ByteArrayOutputStream(); names.write(0);
            for (int i=0;i<count;i++) names.write(("file"+i+"\0").getBytes(StandardCharsets.UTF_16LE));
            props.write(17); number(props,names.size()); props.write(names.toByteArray());
        }
        return props.toByteArray();
    }
    static File emptySevenZ(File dir, long count, byte[] properties) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(5); number(header,count); header.write(properties); header.write(0); header.write(0);
        return wrap(dir,new byte[0],header.toByteArray());
    }
    static Round4Fixtures.Graph deflateGraph(byte[] raw, int width, boolean aes) throws Exception {
        byte[] filtered = width == 0 ? raw : Round4Fixtures.swap(raw,width);
        byte[] compressed = Round4Fixtures.deflate(filtered);
        Round4Fixtures.Graph graph = new Round4Fixtures.Graph(raw,compressed);
        if (aes) {
            byte[] key=Arrays.copyOf(new String(Round4Fixtures.PASSWORD).getBytes(StandardCharsets.UTF_16LE),32);
            Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(new byte[16]));
            graph.packed=cipher.doFinal(Arrays.copyOf(compressed,(compressed.length+15)/16*16));
            graph.add(new byte[]{6,(byte)0xf1,7,1},new byte[]{0x3f},compressed.length);
        }
        graph.add(new byte[]{4,1,8},new byte[0],raw.length);
        if(width!=0)graph.add(new byte[]{2,3,(byte)width},new byte[0],raw.length);
        return graph;
    }
    static File compressedSevenZ(File dir, byte[] raw, int width, boolean aes,
                                  boolean encodedHeader, boolean twoFiles, boolean badCrc) throws Exception {
        Round4Fixtures.Graph graph=deflateGraph(raw,width,aes);
        ByteArrayOutputStream header=new ByteArrayOutputStream();header.write(1);header.write(4);
        Round4Fixtures.streams(header,graph,0,twoFiles,badCrc);
        header.write(5); number(header,twoFiles?2:1);
        byte[] names=(twoFiles?"1.bin\0"+"2.bin\0":"data.bin\0").getBytes(StandardCharsets.UTF_16LE);
        header.write(17);number(header,names.length+1);header.write(0);header.write(names);header.write(0);header.write(0);
        ByteArrayOutputStream packed=new ByteArrayOutputStream();packed.write(graph.packed);
        byte[] next=header.toByteArray();
        if(encodedHeader){
            Round4Fixtures.Graph encoded=deflateGraph(next,width,aes);
            ByteArrayOutputStream descriptor=new ByteArrayOutputStream();descriptor.write(23);
            Round4Fixtures.streams(descriptor,encoded,packed.size(),false,false);
            packed.write(encoded.packed);next=descriptor.toByteArray();
        }
        return wrap(dir,packed.toByteArray(),next);
    }
}
