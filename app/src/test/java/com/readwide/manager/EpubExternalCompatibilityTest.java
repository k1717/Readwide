package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.util.EpubBindingRewriter;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/** Optional read-only audit over the separately downloaded IDPF EPUB 3 samples. */
public class EpubExternalCompatibilityTest {

    private static final int EXPECTED_SAMPLE_COUNT = 45;
    private static final byte[] EPUB_MIMETYPE =
            "application/epub+zip".getBytes(StandardCharsets.US_ASCII);

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void auditsIdpf20230704PackagedOrUnpackedSamples() throws Exception {
        String rootValue = System.getenv("READWIDE_EPUB_SAMPLE_DIR");
        Assume.assumeTrue(rootValue != null && !rootValue.trim().isEmpty());
        File root = new File(rootValue);
        Assume.assumeTrue(root.isDirectory());
        List<EpubSample> samples = discoverSamples(root);
        assertEquals("Expected the complete 2023-07-04 sample set",
                EXPECTED_SAMPLE_COUNT, samples.size());

        int missingSpineResources = 0;
        int rawSpineItemRefs = 0;
        int resolvedSpineItems = 0;
        int validatedOverlayCues = 0;
        int rewrittenBindings = 0;
        int kusamakuraStableSentenceIds = 0;
        boolean kusamakuraVerticalSampleSeen = false;
        Set<String> validLinkedOverlays = new HashSet<>();
        Map<String, Boolean> globallyFixed = new HashMap<>();

        for (EpubSample sample : samples) {
            File archive = sample.packagedFile;
            boolean temporaryArchive = false;
            if (archive == null) {
                archive = new File(temporaryFolder.getRoot(), sample.name);
                packageUnpackedEpub(sample.unpackedDirectory, archive);
                temporaryArchive = true;
            }
            try (ZipFile zip = new ZipFile(archive)) {
                List<DocumentArchiveUtils.EpubSpineItem> spine =
                        DocumentArchiveUtils.findEpubSpineItems(zip);
                assertFalse(sample.name + " has no supported spine", spine.isEmpty());
                DocumentArchiveUtils.EpubPackageResources resources =
                        DocumentArchiveUtils.findEpubPackageResources(zip);
                int rawItemRefs = countDirectSpineItemRefs(zip, resources.packagePath);
                assertEquals(sample.name + " dropped an OPF spine itemref",
                        rawItemRefs, spine.size());
                rawSpineItemRefs += rawItemRefs;
                resolvedSpineItems += spine.size();
                globallyFixed.put(sample.name,
                        DocumentArchiveUtils.detectEpubFixedLayoutLike(zip));
                boolean kusamakuraVertical =
                        "kusamakura-japanese-vertical-writing.epub"
                                .equalsIgnoreCase(sample.name);
                if (kusamakuraVertical) {
                    kusamakuraVerticalSampleSeen = true;
                    assertTrue("Kusamakura must retain vertical-writing metadata",
                            DocumentArchiveUtils.detectEpubVerticalWritingLike(zip));
                }

                Set<String> entries = new HashSet<>();
                zip.stream().filter(e -> !e.isDirectory()).forEach(e ->
                        entries.add(DocumentArchiveUtils.normalizeZipPath(e.getName())));
                Map<String, String> handlers = new HashMap<>();
                for (Map.Entry<String, DocumentArchiveUtils.EpubBinding> binding
                        : resources.bindingsByMediaType.entrySet()) {
                    handlers.put(binding.getKey(), binding.getValue().handlerPath);
                }

                for (DocumentArchiveUtils.EpubSpineItem item : spine) {
                    ZipEntry entry = zip.getEntry(item.path);
                    if (entry == null || entry.isDirectory()) {
                        missingSpineResources++;
                        continue;
                    }
                    if (item.hasMediaOverlay()) {
                        String overlayKey = sample.name + "\n" + item.mediaOverlayPath;
                        if (validLinkedOverlays.add(overlayKey)) {
                            EpubSmilParser.Timeline timeline =
                                    EpubSmilParser.parse(zip, item.mediaOverlayPath);
                            assertFalse(sample.name + " has an empty linked overlay",
                                    timeline.isEmpty());
                            validatedOverlayCues += timeline.cues.size();
                        }
                    }
                    if (kusamakuraVertical && item.isHtml()) {
                        String html = DocumentArchiveUtils.readZipEntryString(zip, entry);
                        java.util.regex.Matcher stableIds = java.util.regex.Pattern.compile(
                                "(?is)<(?:span|p|li|blockquote)\\b[^>]*\\bid\\s*=")
                                .matcher(html);
                        while (stableIds.find()) kusamakuraStableSentenceIds++;
                    }
                    if (item.isHtml() && !handlers.isEmpty()) {
                        String html = DocumentArchiveUtils.readZipEntryString(zip, entry);
                        rewrittenBindings += EpubBindingRewriter.rewriteBoundObjects(
                                html,
                                item.path,
                                handlers,
                                "https://readwide.local",
                                entries).replacementCount;
                    }
                }
            } finally {
                if (temporaryArchive) {
                    Files.deleteIfExists(archive.toPath());
                }
            }
        }

        assertEquals(0, missingSpineResources);
        assertEquals("Every raw itemref must resolve to one supported spine item",
                rawSpineItemRefs, resolvedSpineItems);
        assertEquals("Expected all linked SMIL documents in the sample set",
                7, validLinkedOverlays.size());
        assertEquals("Expected every validated SMIL cue in the sample set",
                1_137, validatedOverlayCues);
        assertEquals("Expected the figure-gallery and quiz binding objects to rewrite",
                2, rewrittenBindings);
        assertEquals(Boolean.FALSE, globallyFixed.get("cole-voyage-of-life.epub"));
        assertEquals(Boolean.TRUE, globallyFixed.get("cole-voyage-of-life-tol.epub"));
        assertTrue("Expected the Kusamakura vertical-writing sample",
                kusamakuraVerticalSampleSeen);
        assertTrue("Kusamakura sentence ids must remain available to bookmark anchors",
                kusamakuraStableSentenceIds >= 400);
    }

    private static List<EpubSample> discoverSamples(File root) {
        File[] packaged = root.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".epub"));
        assertNotNull(packaged);
        if (packaged.length > 0) {
            Arrays.sort(packaged, Comparator.comparing(File::getName,
                    String.CASE_INSENSITIVE_ORDER));
            assertEquals("Expected the complete 2023-07-04 packaged sample set",
                    EXPECTED_SAMPLE_COUNT, packaged.length);
            List<EpubSample> samples = new ArrayList<>(packaged.length);
            for (File file : packaged) samples.add(EpubSample.packaged(file));
            return samples;
        }

        File unpackedRoot = new File(root, "30");
        if (!unpackedRoot.isDirectory()) unpackedRoot = root;
        File[] directories = unpackedRoot.listFiles(File::isDirectory);
        assertNotNull(directories);
        Arrays.sort(directories, Comparator.comparing(File::getName,
                String.CASE_INSENSITIVE_ORDER));
        List<EpubSample> samples = new ArrayList<>(directories.length);
        for (File directory : directories) {
            if (!isUnpackedEpub(directory)) continue;
            samples.add(EpubSample.unpacked(directory));
        }
        assertEquals("Expected 45 unpacked EPUB samples under the source tree's 30 directory",
                EXPECTED_SAMPLE_COUNT, samples.size());
        return samples;
    }

    private static boolean isUnpackedEpub(File directory) {
        return new File(directory, "mimetype").isFile()
                && new File(directory, "META-INF/container.xml").isFile();
    }

    /** Packages one official expanded sample without modifying the downloaded source tree. */
    private static void packageUnpackedEpub(File sourceDirectory, File archive)
            throws Exception {
        Path root = sourceDirectory.toPath();
        Path mimetypePath = root.resolve("mimetype");
        byte[] mimetype = Files.readAllBytes(mimetypePath);
        assertTrue(sourceDirectory.getName() + " has an invalid EPUB mimetype payload",
                Arrays.equals(EPUB_MIMETYPE, mimetype));

        CRC32 crc = new CRC32();
        crc.update(mimetype);
        try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(archive)))) {
            // OCF requires this to be the first entry and stored without compression.
            ZipEntry mimetypeEntry = new ZipEntry("mimetype");
            mimetypeEntry.setMethod(ZipEntry.STORED);
            mimetypeEntry.setSize(mimetype.length);
            mimetypeEntry.setCompressedSize(mimetype.length);
            mimetypeEntry.setCrc(crc.getValue());
            output.putNextEntry(mimetypeEntry);
            output.write(mimetype);
            output.closeEntry();

            List<Path> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !path.equals(mimetypePath))
                        .forEach(files::add);
            }
            files.sort(Comparator.comparing(path -> zipPath(root, path)));
            for (Path file : files) {
                String entryName = zipPath(root, file);
                assertFalse("Unsafe expanded EPUB path: " + entryName,
                        entryName.isEmpty()
                                || entryName.startsWith("/")
                                || entryName.equals("..")
                                || entryName.startsWith("../")
                                || entryName.contains("/../"));
                output.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static String zipPath(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static final class EpubSample {
        final String name;
        final File packagedFile;
        final File unpackedDirectory;

        private EpubSample(String name, File packagedFile, File unpackedDirectory) {
            this.name = name;
            this.packagedFile = packagedFile;
            this.unpackedDirectory = unpackedDirectory;
        }

        static EpubSample packaged(File file) {
            return new EpubSample(file.getName(), file, null);
        }

        static EpubSample unpacked(File directory) {
            return new EpubSample(directory.getName() + ".epub", null, directory);
        }
    }

    private static int countDirectSpineItemRefs(ZipFile zip, String opfPath)
            throws Exception {
        ZipEntry opf = zip.getEntry(opfPath);
        assertNotNull("EPUB package document is missing: " + opfPath, opf);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        Document document;
        try (InputStream input = zip.getInputStream(opf)) {
            document = factory.newDocumentBuilder().parse(input);
        }
        NodeList itemRefs = document.getElementsByTagNameNS("*", "itemref");
        int count = 0;
        for (int i = 0; i < itemRefs.getLength(); i++) {
            Node itemRef = itemRefs.item(i);
            Node parent = itemRef != null ? itemRef.getParentNode() : null;
            if (parent != null && "spine".equals(parent.getLocalName())) count++;
        }
        return count;
    }
}
