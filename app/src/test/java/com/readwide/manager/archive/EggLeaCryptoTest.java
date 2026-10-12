package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Source-only regression additions; CC0 producer samples are not runtime dependencies. */
public class EggLeaCryptoTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final char[] PASSWORD = "test1234".toCharArray();

    @Test public void published128BitBlockVector() {
        vector("0f1e2d3c4b5a69788796a5b4c3d2e1f0", "101112131415161718191a1b1c1d1e1f",
                "9fc84e3528c6c6185532c7a704648bfd");
    }
    @Test public void published256BitBlockVector() {
        vector("0f1e2d3c4b5a69788796a5b4c3d2e1f0f0e1d2c3b4a5968778695a4b3c2d1e0f",
                "303132333435363738393a3b3c3d3e3f", "d651aff647b189c13a8900ca27f9e197");
    }
    @Test public void realLea128SelectedAndBulk() throws Exception { producer("lea128.egg"); }
    @Test public void realLea256SelectedAndBulk() throws Exception { producer("lea256.egg"); }
    @Test public void realLeaBzip2() throws Exception {
        File archive = fixture("enc_lea_bzip2.egg");
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "text.txt", output, PASSWORD));
        check(output, 3600, 0x2c19aec0L);
    }
    @Test public void wrongPasswordPreservesExistingOutput() throws Exception {
        File archive = fixture("enc_lea_bzip2.egg"), output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> EggArchiveReader.extractSingleEntry(archive, "text.txt", output, "wrong".toCharArray()));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void tamperedAuthenticationFooterPreservesExistingOutput() throws Exception {
        File archive = fixture("enc_lea_bzip2.egg");
        byte[] data = Files.readAllBytes(archive.toPath());
        int field = find(data, new byte[]{0x0f,0x47,(byte)0xd1,0x08});
        assertTrue(field >= 0);
        int payload = field + ((data[field + 4] & 1) == 0 ? 7 : 9);
        int saltLength = data[payload] == 5 ? 8 : 16;
        data[payload + 1 + saltLength + 2] ^= 1;
        Files.write(archive.toPath(), data);
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> EggArchiveReader.extractSingleEntry(archive, "text.txt", output, PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void rejectsInvalidCipherParameters() {
        assertThrows(IOException.class, () -> new EggAuthenticatedCrypto(new byte[0], new byte[8], new byte[2], 192, true));
        assertThrows(IOException.class, () -> new EggAuthenticatedCrypto(new byte[0], new byte[16], new byte[2], 128, true));
    }
    @Test public void cancellationBeforeKeyDerivation() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> new EggAuthenticatedCrypto(new byte[]{1}, new byte[8], new byte[2], 128, true));
        } finally { Thread.interrupted(); }
    }

    private void producer(String name) throws Exception {
        File archive = fixture(name), root = temp.newFolder();
        List<ArchiveSupport.EntryInfo> entries = EggArchiveReader.listEntries(archive, PASSWORD);
        assertTrue(entries.stream().anyMatch(e -> e.path.equals("hello.txt")));
        File selected = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "hello.txt", selected, PASSWORD));
        check(selected, 131, 0x1b239eb9L);
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, root, PASSWORD, null, null));
        check(new File(root,"hello.txt"),131,0x1b239eb9L);
        check(new File(root,"repeated.txt"),720,0x33ab076aL);
        check(new File(root,"binary.bin"),1024,0xb70b4c26L);
        check(new File(root,"empty.txt"),0,0);
        check(new File(root,"large.txt"),131364,0xfe5598ecL);
        check(new File(root,"subdir/nested/deep.txt"),20,0xaab59c3dL);
        check(new File(root,"뷁테스트.txt"),72,0x64205eb3L);
    }
    private File fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/archives/egg-lea/" + name + ".base64")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer,0,count);
            File archive = new File(temp.newFolder(),name);
            Files.write(archive.toPath(), Base64.getMimeDecoder().decode(new String(bytes.toByteArray(), StandardCharsets.US_ASCII)));
            return archive;
        }
    }
    private static void check(File file, long size, long expectedCrc) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath()); assertEquals(size, bytes.length);
        CRC32 crc = new CRC32(); crc.update(bytes); assertEquals(expectedCrc, crc.getValue());
    }
    private static void vector(String key, String plain, String encrypted) {
        byte[] block = hex(plain); new EggLeaCipher(hex(key)).encryptBlock(block,block);
        assertArrayEquals(hex(encrypted),block);
    }
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length()/2];
        for (int i=0;i<bytes.length;i++) bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return bytes;
    }
    private static int find(byte[] data, byte[] pattern) {
        outer: for(int i=0;i<=data.length-pattern.length;i++) {
            for(int j=0;j<pattern.length;j++) if(data[i+j]!=pattern[j]) continue outer;
            return i;
        }
        return -1;
    }
}
