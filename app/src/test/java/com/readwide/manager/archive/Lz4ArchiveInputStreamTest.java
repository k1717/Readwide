package com.readwide.manager.archive;

import java.io.*;
import java.util.Arrays;
import org.apache.commons.codec.digest.XXHash32;
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic frame/grammar tests; not real-producer or device evidence. */
public class Lz4ArchiveInputStreamTest {
    @Test public void legacyLiteralBlocksAndKernelEndMarkerDecode() throws Exception {
        byte[] data = Round4Fixtures.data(70000);
        assertArrayEquals(data, decode(legacy(data)));
        assertArrayEquals(data, decode(join(legacy(data), word(0))));
    }

    @Test public void fullLegacyBlocksResetTheirDictionary() throws Exception {
        byte[] first = new byte[8 * 1024 * 1024]; Arrays.fill(first, (byte) 65);
        byte[] second = Round4Fixtures.data(8192);
        byte[] packed = join(word(0x184c2102L), block(first), block(second));
        assertArrayEquals(join(first, second), decode(packed));
    }

    @Test public void mixedModernLegacyAndEmptyFramesConcatenate() throws Exception {
        byte[] a = {1,2,3}, b = {4,5,6}, c = {7,8,9};
        assertArrayEquals(join(a,b,c), decode(join(frame(a), frame(new byte[0]),
                legacy(b), word(0), legacy(new byte[0]), frame(c))));
    }

    @Test public void knownMagicEndsLegacyWithoutAnExplicitMarker() throws Exception {
        assertArrayEquals(new byte[]{1,2,3}, decode(join(legacy(new byte[]{1}),
                legacy(new byte[]{2}), frame(new byte[]{3}))));
    }

    @Test public void metadataBeforeBetweenAndAfterFramesIsSkipped() throws Exception {
        byte[] metadata = join(word(0x184d2a5fL), word(3), new byte[]{9,8,7});
        assertArrayEquals(new byte[]{1,2}, decode(join(metadata, legacy(new byte[]{1}),
                metadata, frame(new byte[]{2}), metadata)));
    }

    @Test public void workerHintsStillPrecedeModernFrames() throws Exception {
        byte[] data = Round4Fixtures.data(5000), modern = frame(data);
        assertArrayEquals(data, decode(join(word(0x184d2a50L), word(4), word(modern.length), modern)));
    }

    @Test public void oneByteFragmentationAndReadsKeepFrameBoundaries() throws Exception {
        byte[] data = join(legacy(new byte[]{1,2}), frame(new byte[]{3,4}));
        InputStream fragmented = new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] b,int o,int n) { return super.read(b,o,Math.min(n,1)); }
        };
        try (InputStream in = new Lz4ArchiveInputStream(fragmented)) {
            for (int value=1;value<=4;value++) assertEquals(value,in.read());
            assertEquals(-1,in.read()); assertEquals(-1,in.read());
        }
    }

    @Test public void truncatedLegacyPayloadFailsBeforeReturningAnyBlockBytes() throws Exception {
        byte[] full = legacy(new byte[]{1,2,3});
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(Arrays.copyOf(full,full.length-1)))) {
            assertThrows(IOException.class,in::read);
        }
    }

    @Test public void invalidOffsetsAndTruncatedLengthFieldsFailBeforeOutput() throws Exception {
        for (byte[] bad : new byte[][]{
                {0x10,65,0}, // incomplete two-byte offset, not a final literal-only sequence
                {0x10,65,0,0,0}, // zero offset
                {0,1,0,0}, // history before the beginning of an independent block
                {(byte)0xf0,(byte)255}, // incomplete literal extension
                {0x1f,65,1,0,(byte)255} // incomplete match extension
        }) {
            try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(
                    join(word(0x184c2102L),word(bad.length),bad)))) {
                assertThrows(IOException.class,in::read);
            }
        }
    }

    @Test public void noMatchIsReadFromTheFinalLiteralTokensUnusedNibble() throws Exception {
        byte[] block = {0x3f,1,2,3};
        assertArrayEquals(new byte[]{1,2,3},decode(join(word(0x184c2102L),word(block.length),block)));
    }

    @Test public void oversizedPackedDeclarationFailsWithoutReadingItsPayload() throws Exception {
        assertThrows(IOException.class,()->decode(join(word(0x184c2102L),word(0x7fffffffL))));
    }

    @Test public void matchCannotExpandBeyondTheLegacyFormatBlockSize() throws Exception {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(new byte[]{0x1f,65,1,0});
        int extension = 8 * 1024 * 1024 - 19;
        while (extension >= 255) { block.write(255); extension -= 255; }
        block.write(extension); block.write(new byte[]{0x50,65,65,65,65,65});
        byte[] packed = block.toByteArray();
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(
                join(word(0x184c2102L),word(packed.length),packed)))) {
            assertThrows(IOException.class,in::read);
        }
    }

    @Test public void emptySourceMetadataOnlyAndTrailingJunkAreNotValidFiles() throws Exception {
        for (byte[] bad : new byte[][]{new byte[0], new byte[]{2},
                join(word(0x184d2a50L),word(0)), join(legacy(new byte[]{1}),new byte[]{42}),
                join(frame(new byte[]{1}),word(0xdeadbeefL)),
                join(word(0x184d2a50L),word(4),new byte[]{1,2})}) {
            assertThrows(IOException.class,()->decode(bad));
        }
    }

    @Test public void modernChecksumsRemainEnforced() throws Exception {
        byte[] packed = frame(Round4Fixtures.data(200));
        packed[packed.length-1] ^= 1;
        assertThrows(IOException.class,()->decode(packed));
    }

    @Test public void sourceErrorsAndZeroProgressRemainTerminal() throws Exception {
        for (Throwable error : new Throwable[]{new IOException("source"),new IllegalStateException("source"),new AssertionError("source")}) {
            int[] calls = {0};
            InputStream source = new InputStream() {
                @Override public int read() { fail("bulk source reads expected"); return -1; }
                @Override public int read(byte[] b,int o,int n) throws IOException {
                    calls[0]++;
                    if (error instanceof IOException) throw (IOException)error;
                    if (error instanceof RuntimeException) throw (RuntimeException)error;
                    throw (Error)error;
                }
            };
            try (InputStream in = new Lz4ArchiveInputStream(source)) {
                assertSame(error,assertThrows(Throwable.class,in::read));
                assertSame(error,assertThrows(Throwable.class,in::read)); assertEquals(1,calls[0]);
            }
        }
        try (InputStream in = new Lz4ArchiveInputStream(new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] b,int o,int n) { return 0; }
        })) { assertThrows(IOException.class,in::read); }
    }

    @Test public void cancellationIsStickyButInvalidCallerRangesAreNot() throws Exception {
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(legacy(new byte[]{42})))) {
            assertThrows(IndexOutOfBoundsException.class,()->in.read(new byte[1],Integer.MAX_VALUE,1));
            assertEquals(0,in.read(new byte[0]));
            Thread.currentThread().interrupt();
            IOException failure;
            try { failure = assertThrows(InterruptedIOException.class,in::read); }
            finally { Thread.interrupted(); }
            assertSame(failure,assertThrows(IOException.class,in::read));
        }
    }

    @Test public void closeRetriesThePhysicalSourceButNeverReopensReading() throws Exception {
        int[] calls = {0};
        InputStream source = new ByteArrayInputStream(frame(new byte[]{42})) {
            @Override public void close() throws IOException { if (++calls[0] == 1) throw new IOException("close"); }
        };
        InputStream in = new Lz4ArchiveInputStream(source);
        assertEquals(42,in.read()); assertThrows(IOException.class,in::close);
        assertThrows(IOException.class,in::read); in.close(); in.close(); assertEquals(2,calls[0]);
    }

    @Test public void modernEmptyStoredBlocksBeforeBetweenAndAfterDataAreNotEndMarks() throws Exception {
        for (boolean checks : new boolean[]{false,true}) {
            byte[] empty = modernBlock(new byte[0],true,checks);
            byte[] data = {1,2,3,4};
            byte[] packed = modern(checks?0x74:0x60,0x40,4L,data,
                    empty,modernBlock(new byte[]{1,2},true,checks),empty,
                    modernBlock(new byte[]{3,4},true,checks),empty);
            assertArrayEquals(data,decode(packed));
        }
    }

    @Test public void manyEmptyModernBlocksDoNotRecurseOrSignalPrematureEof() throws Exception {
        byte[][] blocks = new byte[4097][];
        Arrays.fill(blocks,modernBlock(new byte[0],true,true));
        blocks[4096] = modernBlock(new byte[]{42},true,true);
        assertArrayEquals(new byte[]{42},decode(modern(0x74,0x40,1L,new byte[]{42},blocks)));
    }

    @Test public void linkedHistorySurvivesEmptyBlocksAndSingleByteReads() throws Exception {
        byte[] prefix = {'A','B','C','D'};
        byte[] raw = {4,4,0,0x50,1,2,3,4,5}; // eight-byte match, five trailing literals
        byte[] expected = join(prefix,prefix,prefix,new byte[]{1,2,3,4,5});
        byte[] bytes = modern(0x54,0x40,(long)expected.length,expected,
                modernBlock(prefix,true,true),modernBlock(new byte[0],true,true),
                modernBlock(raw,false,true));
        InputStream fragments = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b,int o,int n) { return super.read(b,o,Math.min(n,1)); }
        };
        try (InputStream in = new Lz4ArchiveInputStream(fragments)) {
            for (byte value : expected) assertEquals(value&255,in.read());
            assertEquals(-1,in.read());
        }
    }

    @Test public void linkedHistoryWrapsAt64KiBAndKeepsTheNewestBytes() throws Exception {
        byte[] prefix = Round4Fixtures.data(65536), tail = {81,82,83,84,85,86,87};
        byte[] history = join(prefix,tail);
        byte[] raw = {4,(byte)255,(byte)255,0x50,11,12,13,14,15};
        byte[] expected = join(history,Arrays.copyOfRange(history,history.length-65535,history.length-65535+8),
                new byte[]{11,12,13,14,15});
        byte[] packed = modern(0x54,0x40,(long)expected.length,expected,
                modernBlock(prefix,true,true),modernBlock(tail,true,true),modernBlock(raw,false,true));
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(packed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[257]; int n;
            while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
            assertArrayEquals(expected,out.toByteArray());
        }
    }

    @Test public void linkedFramesCannotReferenceMissingOrPreviousFrameHistory() throws Exception {
        byte[] match = modernBlock(new byte[]{4,4,0,0x50,1,2,3,4,5},false,false);
        assertThrows(IOException.class,()->decode(modern(0x40,0x40,null,new byte[0],match)));
        assertThrows(IOException.class,()->decode(join(modern(0x40,0x40,null,new byte[]{1,2,3,4},
                modernBlock(new byte[]{1,2,3,4},true,false)),
                modern(0x40,0x40,null,new byte[0],match))));
        assertThrows(IOException.class,()->decode(modern(0x60,0x40,null,new byte[0],
                modernBlock(new byte[]{1,2,3,4},true,false),match)));
        assertThrows(IOException.class,()->decode(modern(0x40,0x40,null,new byte[0],
                modernBlock(new byte[]{1},true,false),match)));
    }

    @Test public void modernExplicitZeroSizeAndUnsignedSizeAreNotMissingSizes() throws Exception {
        byte[] empty = modernBlock(new byte[0],true,true);
        assertArrayEquals(new byte[0],decode(modern(0x74,0x40,0L,new byte[0],empty)));
        byte[] nonempty = modern(0x60,0x40,0L,new byte[]{42},modernBlock(new byte[]{42},true,false));
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(nonempty))) {
            assertThrows(IOException.class,in::read); // no payload before size-overrun failure
        }
        byte[] unsigned = modern(0x60,0x40,Long.MIN_VALUE,new byte[]{42},modernBlock(new byte[]{42},true,false));
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(unsigned))) {
            assertEquals(42,in.read()); // not a signed-negative sentinel or allocation request
            assertThrows(IOException.class,in::read); // actual frame is shorter than advertised
        }
    }

    @Test public void modernSizeUnderrunsAndCompressedOverrunsFail() throws Exception {
        assertThrows(IOException.class,()->decode(modern(0x60,0x40,2L,new byte[]{42},
                modernBlock(new byte[]{42},true,false))));
        byte[] compressed = modernBlock(new byte[]{0x30,1,2,3},false,false);
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(
                modern(0x60,0x40,2L,new byte[]{1,2,3},compressed)))) {
            assertThrows(IOException.class,in::read);
        }
    }

    @Test public void modernHeaderReservedBitsBlockCodesAndChecksumAreValidated() throws Exception {
        for (int flags : new int[]{0x20,0xa0,0x62}) {
            assertThrows(IOException.class,()->decode(join(modernHeader(flags,0x40,null,null),word(0))));
        }
        for (int bd : new int[]{0,0x30,0x41,0xc0}) {
            assertThrows(IOException.class,()->decode(join(modernHeader(0x60,bd,null,null),word(0))));
        }
        byte[] header = modernHeader(0x60,0x40,null,null); header[header.length-1]^=1;
        assertThrows(IOException.class,()->decode(join(header,word(0))));
        for (int bd : new int[]{0x40,0x50,0x60,0x70}) {
            assertArrayEquals(new byte[0],decode(join(modernHeader(0x60,bd,null,null),word(0))));
        }
    }

    @Test public void dictionaryIdWithoutReferencesStillRequiresAValidCompleteHeader() throws Exception {
        assertArrayEquals(new byte[0],decode(join(modernHeader(0x60,0x40,0L,123L),word(0))));
        byte[] header = modernHeader(0x60,0x40,0L,123L); header[header.length-1]^=1;
        IOException corrupt = assertThrows(IOException.class,()->decode(join(header,word(0))));
        assertTrue(corrupt.getMessage().contains("checksum"));
    }

    @Test public void unusedDictionaryIdsAllowStoredLiteralAndSelfReferencingBlocks() throws Exception {
        byte[] pattern = {'A','B','C','D'};
        byte[] raw = {0x44,'A','B','C','D',4,0,0x50,1,2,3,4,5};
        byte[] expanded = join(pattern,pattern,pattern,new byte[]{1,2,3,4,5});
        byte[] data = join(new byte[]{9,8,7},expanded);
        for (long id : new long[]{0,123,0xffffffffL}) {
            for (int flags : new int[]{0x54,0x74}) {
                assertArrayEquals(data,decode(modernWithDictionary(flags,0x40,(long)data.length,id,data,
                        modernBlock(new byte[0],true,true),
                        modernBlock(new byte[]{9,8},true,true),
                        modernBlock(new byte[]{0x10,7},false,true),
                        modernBlock(raw,false,true))));
            }
        }
    }

    @Test public void dictionaryTaggedLinkedBlocksUseOnlyActuallyDecodedHistory() throws Exception {
        byte[] prefix = {'A','B','C','D'};
        byte[] raw = {4,4,0,0x50,1,2,3,4,5};
        byte[] expected = join(prefix,prefix,prefix,new byte[]{1,2,3,4,5});
        byte[] bytes = modernWithDictionary(0x54,0x40,(long)expected.length,123L,expected,
                modernBlock(prefix,true,true),modernBlock(new byte[0],true,true),modernBlock(raw,false,true));
        InputStream fragments = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b,int o,int n) { return super.read(b,o,Math.min(n,1)); }
        };
        try (InputStream in = new Lz4ArchiveInputStream(fragments)) {
            for (byte value : expected) assertEquals(value&255,in.read());
            assertEquals(-1,in.read());
        }
    }

    @Test public void missingDictionaryReferenceFailsBeforeEvenTheBlocksLiteralPrefix() throws Exception {
        byte[] raw = {0x24,11,12,8,0,0x50,1,2,3,4,5};
        byte[] bytes = modernWithDictionary(0x70,0x40,null,123L,new byte[0],modernBlock(raw,false,true));
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(bytes))) {
            byte[] destination = {42,42,42};
            IOException failure = assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                    ()->in.read(destination));
            assertTrue(failure.getMessage().contains("preset dictionary"));
            assertArrayEquals(new byte[]{42,42,42},destination);
            assertSame(failure,assertThrows(IOException.class,in::read));
        }
    }

    @Test public void dictionaryTaggedIndependentBlocksCannotBorrowPriorBlockHistory() throws Exception {
        byte[] prefix = {'A','B','C','D'};
        byte[] bytes = modernWithDictionary(0x70,0x40,null,123L,new byte[0],
                modernBlock(prefix,true,true),modernBlock(new byte[]{4,4,0,0x50,1,2,3,4,5},false,true));
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(bytes))) {
            for (byte value : prefix) assertEquals(value&255,in.read());
            assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,in::read);
        }
    }

    @Test public void dictionaryTaggedFramesResetHistoryAndTheMissingDictionaryFlag() throws Exception {
        byte[] prefix = {'A','B','C','D'};
        byte[] first = modernWithDictionary(0x50,0x40,4L,123L,prefix,modernBlock(prefix,true,true));
        byte[] match = modernBlock(new byte[]{4,4,0,0x50,1,2,3,4,5},false,true);
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,()->decode(join(first,
                modernWithDictionary(0x50,0x40,null,123L,new byte[0],match))));
        IOException plain = assertThrows(IOException.class,()->decode(join(first,
                modern(0x50,0x40,null,new byte[0],match))));
        assertFalse(plain instanceof ArchiveSupport.UnsupportedArchiveFeatureException);
        assertArrayEquals(new byte[]{'A','B','C','D',7,8},decode(join(first,legacy(new byte[]{7}),frame(new byte[]{8}))));
    }

    @Test public void dictionaryTagDoesNotWeakenBlockContentSizeOrTailChecks() throws Exception {
        byte[] data = {1,2,3};
        byte[] block = modernBlock(data,true,true);
        byte[] full = modernWithDictionary(0x74,0x40,3L,123L,data,block);
        for (int length=0;length<full.length;length++) {
            byte[] cut = Arrays.copyOf(full,length);
            assertThrows(IOException.class,()->decode(cut));
        }
        full[full.length-1]^=1;
        assertThrows(IOException.class,()->decode(full));
        block[block.length-1]^=1;
        assertThrows(IOException.class,()->decode(modernWithDictionary(0x74,0x40,3L,123L,data,block)));
        assertThrows(IOException.class,()->decode(modernWithDictionary(0x74,0x40,2L,123L,data,
                modernBlock(data,true,true))));
        assertThrows(IOException.class,()->decode(modernWithDictionary(0x70,0x40,null,123L,new byte[0],
                modernBlock(new byte[]{0x10,42,0,0,0},false,true))));
    }

    @Test public void cancellationAfterDictionaryTaggedStoredPayloadDoesNotPublishBytes() throws Exception {
        byte[] header = modernHeader(0x60,0x40,1L,123L);
        byte[] bytes = join(header,modernBlock(new byte[]{42},true,false),word(0));
        InputStream source = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b,int o,int n) {
                int count = super.read(b,o,n);
                if (pos>=header.length+5) Thread.currentThread().interrupt();
                return count;
            }
        };
        try (InputStream in = new Lz4ArchiveInputStream(source)) {
            byte[] result = {99};
            IOException failure;
            try { failure = assertThrows(InterruptedIOException.class,()->in.read(result)); }
            finally { Thread.interrupted(); }
            assertArrayEquals(new byte[]{99},result);
            assertSame(failure,assertThrows(IOException.class,in::read));
        }
    }

    @Test public void modernPackedAndDecodedBlockBoundsFailBeforeOutput() throws Exception {
        assertThrows(IOException.class,()->decode(join(modernHeader(0x60,0x40,null,null),word(0x80010001L))));
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(new byte[]{0x1f,65,1,0});
        int extra = 65536;
        while (extra>=255) { block.write(255); extra-=255; }
        block.write(extra); block.write(new byte[]{0x50,1,2,3,4,5});
        byte[] bytes = modern(0x60,0x40,null,new byte[0],modernBlock(block.toByteArray(),false,false));
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(bytes))) {
            assertThrows(IOException.class,in::read);
        }
    }

    @Test public void modernBlockChecksumsAreCheckedBeforeNormalizationOrOutput() throws Exception {
        byte[] data = {1,2,3};
        assertArrayEquals(data,decode(modern(0x74,0x40,3L,data,
                modernBlock(new byte[]{0x3f,1,2,3},false,true))));
        for (byte[] payload : new byte[][]{new byte[0],data}) {
            byte[] bad = modernBlock(payload,true,true); bad[bad.length-1]^=1;
            try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(
                    modern(0x70,0x40,null,payload,bad)))) {
                IOException error = assertThrows(IOException.class,in::read);
                assertSame(error,assertThrows(IOException.class,in::read));
            }
        }
    }

    @Test public void modernContentChecksumAndEveryTruncatedPrefixFail() throws Exception {
        byte[] full = modern(0x74,0x40,3L,new byte[]{1,2,3},modernBlock(new byte[]{1,2,3},true,true));
        for (int size=0;size<full.length;size++) {
            byte[] cut = Arrays.copyOf(full,size);
            assertThrows(IOException.class,()->decode(cut));
        }
        full[full.length-1]^=1;
        assertThrows(IOException.class,()->decode(full));
    }

    @Test public void compressedEmptyBlocksAndFollowingLegacyFramesAreNotLost() throws Exception {
        byte[] modern = modern(0x74,0x40,0L,new byte[0],
                modernBlock(new byte[]{0},false,true),modernBlock(new byte[0],true,true));
        assertArrayEquals(new byte[]{7,8},decode(join(modern,legacy(new byte[]{7}),frame(new byte[]{8}))));
    }

    @Test public void cancellationDuringEmptyBlockScanIsTerminal() throws Exception {
        byte[] bytes = modern(0x60,0x40,0L,new byte[0],modernBlock(new byte[0],true,false));
        InputStream source = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b,int o,int n) {
                int count = super.read(b,o,n);
                if (pos>=19) Thread.currentThread().interrupt();
                return count;
            }
        };
        try (InputStream in = new Lz4ArchiveInputStream(source)) {
            IOException error;
            try { error = assertThrows(InterruptedIOException.class,in::read); }
            finally { Thread.interrupted(); }
            assertSame(error,assertThrows(IOException.class,in::read));
        }
    }

    static byte[] modern(int flags,int bd,Long size,byte[] decoded,byte[]... blocks) throws IOException {
        return modernWithDictionary(flags,bd,size,null,decoded,blocks);
    }
    static byte[] modernWithDictionary(int flags,int bd,Long size,Long dictionary,
                                       byte[] decoded,byte[]... blocks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(modernHeader(flags,bd,size,dictionary));
        for (byte[] block : blocks) out.write(block);
        out.write(word(0));
        if ((flags&4)!=0) out.write(word(hash(decoded)));
        return out.toByteArray();
    }
    private static byte[] modernHeader(int flags,int bd,Long size,Long dictionary) throws IOException {
        ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
        descriptor.write(flags | (size==null?0:8) | (dictionary==null?0:1)); descriptor.write(bd);
        if (size!=null) { descriptor.write(word(size)); descriptor.write(word(size>>>32)); }
        if (dictionary!=null) descriptor.write(word(dictionary));
        byte[] fields = descriptor.toByteArray();
        return join(word(0x184d2204L),fields,new byte[]{(byte)(hash(fields)>>>8)});
    }
    static byte[] modernBlock(byte[] packed,boolean stored,boolean checksum) throws IOException {
        return join(word(packed.length | (stored?0x80000000L:0)),packed,checksum?word(hash(packed)):new byte[0]);
    }
    private static long hash(byte[] bytes) {
        XXHash32 hash = new XXHash32(); hash.update(bytes,0,bytes.length); return hash.getValue();
    }

    static byte[] legacy(byte[] raw) throws IOException { return join(word(0x184c2102L),block(raw)); }
    private static byte[] block(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream codec = new BlockLZ4CompressorOutputStream(out)) { codec.write(raw); }
        return join(word(out.size()),out.toByteArray());
    }
    static byte[] frame(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream codec = new FramedLZ4CompressorOutputStream(out)) { codec.write(raw); }
        return out.toByteArray();
    }
    static byte[] word(long value) { return new byte[]{(byte)value,(byte)(value>>>8),(byte)(value>>>16),(byte)(value>>>24)}; }
    static byte[] join(byte[]... chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk);
        return out.toByteArray();
    }
    private static byte[] decode(byte[] packed) throws IOException {
        try (InputStream in = new Lz4ArchiveInputStream(new ByteArrayInputStream(packed))) { return in.readAllBytes(); }
    }
}
