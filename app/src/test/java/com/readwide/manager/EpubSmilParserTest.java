package com.readwide.manager;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EpubSmilParserTest {

    @Test
    public void parsesSampleClockForms() {
        assertEquals(1_979L, EpubSmilParser.parseClockMillis("1.979"));
        assertEquals(24_500L, EpubSmilParser.parseClockMillis("0:00:24.500"));
        assertEquals(62_345L, EpubSmilParser.parseClockMillis("01:02.345"));
        assertEquals(12_500L, EpubSmilParser.parseClockMillis("npt=12.5s"));
        assertEquals(75L, EpubSmilParser.parseClockMillis("75ms"));
        assertEquals(90_000L, EpubSmilParser.parseClockMillis("1.5min"));
        assertEquals(1_800_000L, EpubSmilParser.parseClockMillis("0.5h"));
    }

    @Test
    public void rejectsMalformedOrOverflowingClocks() {
        assertEquals(-1L, EpubSmilParser.parseClockMillis(null));
        assertEquals(-1L, EpubSmilParser.parseClockMillis(""));
        assertEquals(-1L, EpubSmilParser.parseClockMillis("-1s"));
        assertEquals(-1L, EpubSmilParser.parseClockMillis("0:60"));
        assertEquals(-1L, EpubSmilParser.parseClockMillis("1:2:3:4"));
        assertEquals(-1L, EpubSmilParser.parseClockMillis("NaN"));
        assertEquals(-1L, EpubSmilParser.parseClockMillis("999999999999999999999999h"));
    }

    @Test
    public void parsesUnicodePercentPathsAndDocumentOrder() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/text/一.xhtml", utf8("<html><body><p id='文 1'>一</p></body></html>"));
        entries.put("OPS/audio/narration+part.mp3", new byte[]{1, 2, 3});
        entries.put("OPS/audio/shared.mp4", new byte[]{4, 5, 6});
        entries.put("OPS/overlays/章.smil", utf8(
                "<?xml version='1.0' encoding='UTF-8'?>"
                        + "<smil xmlns='http://www.w3.org/ns/SMIL' version='3.0'><body><seq>"
                        + "<par id='first'><text src='../text/%E4%B8%80.xhtml#%E6%96%87%201'/>"
                        + "<audio src='../audio/narration%2Bpart.mp3' clipBegin='1.979' clipEnd='8.039'/></par>"
                        + "<par id='second'><text src='../text/%E4%B8%80.xhtml#second'/>"
                        + "<audio src='../audio/shared.mp4' clipBegin='0:00:24.500' clipEnd='0:00:29.268'/></par>"
                        + "</seq></body></smil>"));

        File epub = createZip(entries);
        try (ZipFile zip = new ZipFile(epub)) {
            EpubSmilParser.Timeline timeline = EpubSmilParser.parse(
                    zip, "OPS/overlays/%E7%AB%A0.smil");
            assertEquals("OPS/overlays/章.smil", timeline.smilPath);
            assertEquals(2, timeline.cues.size());

            EpubSmilParser.Cue first = timeline.cues.get(0);
            assertEquals("first", first.parId);
            assertEquals("OPS/text/一.xhtml", first.textPath);
            assertEquals("文 1", first.textFragment);
            assertEquals("OPS/audio/narration+part.mp3", first.audioPath);
            assertEquals(1_979L, first.clipBeginMs);
            assertEquals(8_039L, first.clipEndMs);
            assertEquals(6_060L, first.durationMs());

            EpubSmilParser.Cue second = timeline.cues.get(1);
            assertEquals("second", second.parId);
            assertEquals("OPS/audio/shared.mp4", second.audioPath);
            assertEquals(24_500L, second.clipBeginMs);
            assertEquals(29_268L, second.clipEndMs);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void parsesOnlyTheExplicitlyLinkedSmilEntry() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/text/chapter.xhtml", utf8("<html><body id='body'>ok</body></html>"));
        entries.put("OPS/audio/chapter.mp3", new byte[]{1});
        entries.put("OPS/linked.smil", utf8(smilWithPar(
                "text/chapter.xhtml#body", "audio/chapter.mp3", "0", "1")));
        // Publisher artifact deliberately refers to a missing audio file. A parser
        // called with OPS/linked.smil must never discover or merge this resource.
        entries.put("OPS/orphan.smil", utf8(smilWithPar(
                "text/chapter.xhtml#body", "audio/missing.mp3", "0", "5")));

        File epub = createZip(entries);
        try (ZipFile zip = new ZipFile(epub)) {
            EpubSmilParser.Timeline timeline = EpubSmilParser.parse(zip, "OPS/linked.smil");
            assertEquals(1, timeline.cues.size());
            assertEquals("OPS/audio/chapter.mp3", timeline.cues.get(0).audioPath);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void skipsMalformedIndividualParsWithoutDroppingValidCues() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/text/chapter.xhtml", utf8("<html><body id='ok'>ok</body></html>"));
        entries.put("OPS/audio/chapter.mp3", new byte[]{1});
        entries.put("OPS/audio/not-audio.txt", utf8("not audio"));
        entries.put("OPS/overlay.smil", utf8(
                "<smil xmlns='http://www.w3.org/ns/SMIL'><body><seq>"
                        + par("valid-default-begin", "text/chapter.xhtml#ok", "audio/chapter.mp3", null, "1.5")
                        + par("external", "text/chapter.xhtml#ok", "https://example.test/a.mp3", "0", "1")
                        + par("missing", "text/chapter.xhtml#ok", "audio/missing.mp3", "0", "1")
                        + par("wrong-type", "text/chapter.xhtml#ok", "audio/not-audio.txt", "0", "1")
                        + par("bad-begin", "text/chapter.xhtml#ok", "audio/chapter.mp3", "bad", "1")
                        + par("reversed", "text/chapter.xhtml#ok", "audio/chapter.mp3", "2", "1")
                        + par("missing-end", "text/chapter.xhtml#ok", "audio/chapter.mp3", "0", null)
                        + "<par id='no-text'><audio src='audio/chapter.mp3' clipBegin='0' clipEnd='1'/></par>"
                        + "</seq></body></smil>"));

        File epub = createZip(entries);
        try (ZipFile zip = new ZipFile(epub)) {
            EpubSmilParser.Timeline timeline = EpubSmilParser.parse(zip, "OPS/overlay.smil");
            assertEquals(1, timeline.cues.size());
            assertEquals("valid-default-begin", timeline.cues.get(0).parId);
            assertEquals(0L, timeline.cues.get(0).clipBeginMs);
            assertEquals(1_500L, timeline.cues.get(0).clipEndMs);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
    }

    @Test
    public void missingLinkedSmilFailsWithoutScanningAlternatives() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPS/unrelated.smil", utf8("<smil xmlns='http://www.w3.org/ns/SMIL'/>"));
        File epub = createZip(entries);
        try (ZipFile zip = new ZipFile(epub)) {
            try {
                EpubSmilParser.parse(zip, "OPS/missing.smil");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("missing"));
                return;
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            epub.delete();
        }
        throw new AssertionError("Expected missing linked SMIL to fail");
    }

    private static String smilWithPar(String text,
                                      String audio,
                                      String begin,
                                      String end) {
        return "<smil xmlns='http://www.w3.org/ns/SMIL'><body><seq>"
                + par("cue", text, audio, begin, end)
                + "</seq></body></smil>";
    }

    private static String par(String id,
                              String text,
                              String audio,
                              String begin,
                              String end) {
        return "<par id='" + id + "'><text src='" + text + "'/><audio src='" + audio + "'"
                + (begin != null ? " clipBegin='" + begin + "'" : "")
                + (end != null ? " clipEnd='" + end + "'" : "")
                + "/></par>";
    }

    private static File createZip(Map<String, byte[]> entries) throws Exception {
        File file = File.createTempFile("readwide-smil-", ".epub");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
