package com.readwide.manager;

import androidx.annotation.Nullable;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.readwide.manager.util.SecureXml;
import com.readwide.manager.util.UriPathCodec;

import javax.xml.parsers.DocumentBuilder;

final class DocumentArchiveUtils {
    private static final int DETECTION_TEXT_READ_LIMIT_BYTES = 512 * 1024;
    private static final int MAX_EPUB_FALLBACK_DEPTH = 64;

    private DocumentArchiveUtils() {}

    /**
     * One supported resource in EPUB spine order. {@link #path} and
     * {@link #mediaType} describe the resource Readwide can render after safely
     * following any manifest fallback chain. {@link #fallback} retains the
     * fallback ID declared by the item originally referenced by the spine, while
     * {@link #properties} combines the rendered manifest resource and itemref
     * properties so page-side and per-item rendition overrides remain available
     * without leaking active/layout flags from an unsupported primary fallback.
     */
    static final class EpubSpineItem {
        final String manifestId;
        final String itemRefId;
        final int spineIndex;
        final String path;
        final String mediaType;
        final String properties;
        final String fallback;
        final String mediaOverlayPath;
        final boolean linear;

        EpubSpineItem(String path,
                      String mediaType,
                      String properties,
                      String fallback) {
            this("", "", -1, path, mediaType, properties, fallback, "", true);
        }

        EpubSpineItem(String manifestId,
                      String itemRefId,
                      int spineIndex,
                      String path,
                      String mediaType,
                      String properties,
                      String fallback,
                      String mediaOverlayPath,
                      boolean linear) {
            this.manifestId = manifestId != null ? manifestId : "";
            this.itemRefId = itemRefId != null ? itemRefId : "";
            this.spineIndex = spineIndex;
            this.path = path != null ? path : "";
            this.mediaType = mediaType != null ? mediaType : "";
            this.properties = properties != null ? properties : "";
            this.fallback = fallback != null ? fallback : "";
            this.mediaOverlayPath = mediaOverlayPath != null ? mediaOverlayPath : "";
            this.linear = linear;
        }

        boolean isHtml() {
            String mime = mediaType.toLowerCase(Locale.ROOT);
            return "application/xhtml+xml".equals(mime)
                    || "text/html".equals(mime)
                    || isEpubHtmlPath(path);
        }

        boolean isImage() {
            String mime = mediaType.toLowerCase(Locale.ROOT);
            if (mime.startsWith("image/")) return true;
            String lower = path.toLowerCase(Locale.ROOT);
            return lower.endsWith(".png") || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                    || lower.endsWith(".svg") || lower.endsWith(".webp");
        }

        boolean hasProperty(String property) {
            if (property == null || property.trim().isEmpty()) return false;
            String wanted = property.trim();
            for (String token : properties.trim().split("\\s+")) {
                if (wanted.equals(token)) return true;
            }
            return false;
        }

        boolean isReflowableOverride() {
            return hasProperty("rendition:layout-reflowable");
        }

        boolean isFixedLayoutOverride() {
            return hasProperty("rendition:layout-pre-paginated");
        }

        boolean isScripted() {
            return hasProperty("scripted");
        }

        boolean hasMediaOverlay() {
            return !mediaOverlayPath.isEmpty();
        }
    }

    private static final class EpubManifestItem {
        final String id;
        final String path;
        final String mediaType;
        final String properties;
        final String fallback;
        final String mediaOverlay;

        EpubManifestItem(String id,
                         String path,
                         String mediaType,
                         String properties,
                         String fallback,
                         String mediaOverlay) {
            this.id = id != null ? id : "";
            this.path = path != null ? path : "";
            this.mediaType = mediaType != null ? mediaType : "";
            this.properties = properties != null ? properties : "";
            this.fallback = fallback != null ? fallback : "";
            this.mediaOverlay = mediaOverlay != null ? mediaOverlay : "";
        }
    }

    static final class EpubBinding {
        final String mediaType;
        final String handlerPath;

        EpubBinding(String mediaType, String handlerPath) {
            this.mediaType = mediaType != null ? mediaType : "";
            this.handlerPath = handlerPath != null ? handlerPath : "";
        }
    }

    static final class EpubPackageResources {
        final Map<String, String> mediaTypesByPath = new LinkedHashMap<>();
        final Map<String, EpubBinding> bindingsByMediaType = new LinkedHashMap<>();
        String packagePath = "";
        String mediaOverlayActiveClass = "";

        String mediaTypeForPath(String path) {
            String normalized = normalizeZipPath(path);
            String declared = mediaTypesByPath.get(normalized);
            return declared != null && !declared.isEmpty()
                    ? declared : mimeForPath(normalized);
        }

        boolean hasBindings() {
            return !bindingsByMediaType.isEmpty();
        }
    }

    static boolean detectEpubFixedLayoutLike(ZipFile zip) {
        try {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry != null) {
                Document containerDoc;
                try (InputStream is = zip.getInputStream(containerEntry)) {
                    containerDoc = secureDocumentBuilder().parse(is);
                }
                NodeList rootFiles = containerDoc.getElementsByTagName("rootfile");
                if (rootFiles.getLength() == 0) rootFiles = containerDoc.getElementsByTagNameNS("*", "rootfile");
                if (rootFiles.getLength() > 0) {
                    Node fullPathAttr = rootFiles.item(0).getAttributes() != null
                            ? rootFiles.item(0).getAttributes().getNamedItem("full-path") : null;
                    if (fullPathAttr != null) {
                        String opfPath = normalizeZipPath(
                                decodeHref(fullPathAttr.getNodeValue()));
                        ZipEntry opfEntry = zip.getEntry(opfPath);
                        if (opfEntry != null) {
                            Document opfDoc;
                            try (InputStream is = zip.getInputStream(opfEntry)) {
                                opfDoc = secureDocumentBuilder().parse(is);
                            }
                            String packageLayout = epubPackageLayout(opfDoc);
                            if ("pre-paginated".equals(packageLayout)) return true;
                            if ("reflowable".equals(packageLayout)) return false;
                        }
                    }
                }
            }

            // A numeric viewport on one cover or a few item-level
            // pre-paginated pages does not make a mixed-layout book globally
            // fixed. Inspect only rendered spine HTML and require a strong
            // majority; per-item rendition overrides are handled separately by
            // DocumentPageActivity.
            int htmlPages = 0;
            int viewportPages = 0;
            for (EpubSpineItem item : findEpubSpineItems(zip)) {
                if (item == null || !item.isHtml()) continue;
                ZipEntry entry = zip.getEntry(item.path);
                if (entry == null || entry.isDirectory()) continue;
                htmlPages++;
                String html = readZipEntryPreviewString(zip, entry);
                EpubViewportParser.Dimensions viewport = EpubViewportParser.parse(html);
                if (viewport.isUsable()) viewportPages++;
            }
            if (htmlPages == 0 || viewportPages == 0) return false;
            if (htmlPages <= 2) return viewportPages == htmlPages;
            return viewportPages * 4 >= htmlPages * 3;
        } catch (Throwable ignored) {
            // Fall through to reflowable handling if detection fails.
        }
        return false;
    }

    private static String epubPackageLayout(Document opfDoc) {
        if (opfDoc == null) return "";
        NodeList metas = opfDoc.getElementsByTagName("meta");
        if (metas.getLength() == 0) metas = opfDoc.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Node meta = metas.item(i);
            NamedNodeMap attrs = meta != null ? meta.getAttributes() : null;
            String property = attributeValue(attrs, "property").toLowerCase(Locale.ROOT);
            String name = attributeValue(attrs, "name").toLowerCase(Locale.ROOT);
            String content = attributeValue(attrs, "content").toLowerCase(Locale.ROOT);
            String text = meta != null && meta.getTextContent() != null
                    ? meta.getTextContent().trim().toLowerCase(Locale.ROOT) : "";
            if ("rendition:layout".equals(property)) {
                if ("pre-paginated".equals(text)) return "pre-paginated";
                if ("reflowable".equals(text)) return "reflowable";
            }
            if ("fixed-layout".equals(name) || "fixed layout".equals(name)) {
                if ("true".equals(content) || "yes".equals(content)
                        || "pre-paginated".equals(content)) {
                    return "pre-paginated";
                }
                if ("false".equals(content) || "no".equals(content)
                        || "reflowable".equals(content)) {
                    return "reflowable";
                }
            }
        }
        return "";
    }

    /**
     * Classifies only genuinely page-image EPUBs for landscape spreads. A fixed
     * layout declaration is a hint, not proof: fixed-layout EPUBs can still be
     * ordinary HTML/text documents. Cover-only books are rejected because the
     * non-cover spine items must also be image-dominant.
     */
    static boolean detectEpubImagePageLike(List<String> htmlPages,
                                           boolean fixedLayoutHint) {
        return EpubImagePageClassifier.isImagePageBook(htmlPages, fixedLayoutHint);
    }

    static boolean detectEpubDeclaredFont(ZipFile zip) {
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int scanned = 0;
            while (entries.hasMoreElements() && scanned < 160) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String name = entry.getName();
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.US);
                if (!(lower.endsWith(".css") || lower.endsWith(".html") || lower.endsWith(".xhtml") || lower.endsWith(".htm"))) {
                    continue;
                }
                scanned++;
                String text = readZipEntryPreviewString(zip, entry);
                if (text == null) continue;
                String compact = text.toLowerCase(Locale.US);
                if (compact.contains("@font-face") || compact.contains("font-family")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // If detection fails, fall back to normal Readwide font handling.
        }
        return false;
    }

    /**
     * Detects EPUBs whose active publisher styles use vertical writing. This is
     * intentionally broader than the spine HTML alone because most books keep
     * writing-mode in an external stylesheet.
     */
    static boolean detectEpubVerticalWritingLike(ZipFile zip) {
        if (zip == null) return false;
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int scanned = 0;
            while (entries.hasMoreElements() && scanned < 200) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String name = entry.getName();
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".css") || isEpubHtmlPath(lower))) continue;
                scanned++;
                String text = readZipEntryPreviewString(zip, entry);
                if (EpubCssCompatibility.detectsVerticalWriting(text)) return true;
            }
        } catch (Throwable ignored) {
            // A failed hint must never make an otherwise readable EPUB fail.
        }
        return false;
    }

    static List<String> findEpubSpinePaths(ZipFile zip) {
        ArrayList<String> result = new ArrayList<>();
        for (EpubSpineItem item : findEpubSpineItems(zip)) {
            // Historical callers consume XHTML strings. Keep that contract while
            // the typed API exposes direct image spine resources to newer code.
            if (item != null && item.isHtml()) result.add(item.path);
        }
        return result;
    }

    /**
     * Parses the OPF manifest and returns supported resources in exact spine
     * order. Unlike the legacy path-only API, direct image spine items are kept.
     * Unsupported resources follow their ID-based fallback chain with cycle and
     * depth protection; an unresolved item is omitted without disturbing later
     * valid spine items.
     */
    static List<EpubSpineItem> findEpubSpineItems(ZipFile zip) {
        ArrayList<EpubSpineItem> result = new ArrayList<>();
        try {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry == null) return result;

            Document containerDoc;
            try (InputStream is = zip.getInputStream(containerEntry)) {
                containerDoc = secureDocumentBuilder().parse(is);
            }

            NodeList rootFiles = containerDoc.getElementsByTagName("rootfile");
            if (rootFiles.getLength() == 0) rootFiles = containerDoc.getElementsByTagNameNS("*", "rootfile");
            if (rootFiles.getLength() == 0) return result;

            Node rootFile = rootFiles.item(0);
            NamedNodeMap rootAttrs = rootFile.getAttributes();
            Node fullPathAttr = rootAttrs != null ? rootAttrs.getNamedItem("full-path") : null;
            if (fullPathAttr == null) return result;

            String opfPath = normalizeZipPath(decodeHref(fullPathAttr.getNodeValue()));
            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) return result;

            Document opfDoc;
            try (InputStream is = zip.getInputStream(opfEntry)) {
                opfDoc = secureDocumentBuilder().parse(is);
            }

            String basePath = parentPath(opfPath);
            Map<String, EpubManifestItem> manifest = new LinkedHashMap<>();
            NodeList items = opfDoc.getElementsByTagName("item");
            if (items.getLength() == 0) items = opfDoc.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Node item = items.item(i);
                if (!isDirectChildOf(item, "manifest")) continue;
                NamedNodeMap attrs = item.getAttributes();
                if (attrs == null) continue;
                String id = attributeValue(attrs, "id");
                String href = attributeValue(attrs, "href");
                if (id.isEmpty() || href.isEmpty()) continue;
                String path = normalizeZipPath(basePath + "/" + decodeHref(href));
                manifest.put(id, new EpubManifestItem(
                        id,
                        path,
                        attributeValue(attrs, "media-type"),
                        attributeValue(attrs, "properties"),
                        attributeValue(attrs, "fallback"),
                        attributeValue(attrs, "media-overlay")));
            }

            NodeList itemRefs = opfDoc.getElementsByTagName("itemref");
            if (itemRefs.getLength() == 0) itemRefs = opfDoc.getElementsByTagNameNS("*", "itemref");
            int spinePosition = 0;
            for (int i = 0; i < itemRefs.getLength(); i++) {
                Node itemRef = itemRefs.item(i);
                if (!isDirectChildOf(itemRef, "spine")) continue;
                int currentSpineIndex = spinePosition++;
                NamedNodeMap attrs = itemRef.getAttributes();
                if (attrs == null) continue;
                String idRef = attributeValue(attrs, "idref");
                if (idRef.isEmpty()) continue;
                EpubManifestItem original = manifest.get(idRef);
                if (original == null) continue;
                EpubManifestItem resolved = resolveSupportedEpubSpineItem(zip, manifest, idRef);
                if (resolved == null) continue;
                String properties = combineProperties(
                        resolved.properties,
                        attributeValue(attrs, "properties"));
                // The rendered resource owns the usable overlay. If an
                // unsupported primary manifest item falls back to another
                // document, its audio/text mapping does not describe the
                // fallback DOM and must not override the fallback's linkage.
                String overlayId = resolved.mediaOverlay;
                String overlayPath = "";
                EpubManifestItem overlay = manifest.get(overlayId);
                ZipEntry overlayEntry = overlay != null ? zip.getEntry(overlay.path) : null;
                if (overlay != null
                        && "application/smil+xml".equalsIgnoreCase(overlay.mediaType)
                        && overlayEntry != null
                        && !overlayEntry.isDirectory()) {
                    overlayPath = overlay.path;
                }
                result.add(new EpubSpineItem(
                        resolved.id,
                        attributeValue(attrs, "id"),
                        currentSpineIndex,
                        resolved.path,
                        resolved.mediaType,
                        properties,
                        original.fallback,
                        overlayPath,
                        !"no".equalsIgnoreCase(attributeValue(attrs, "linear"))));
            }
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * Finds the raster cover resource declared by an EPUB package.
     *
     * <p>EPUB 3's {@code cover-image} property wins, followed by EPUB 2's
     * {@code <meta name="cover" content="...">}. Older packages that omit
     * either declaration fall back to a cover-named manifest image and then
     * the first raster image in spine order. SVG-only covers deliberately
     * remain on the normal file icon path because the lightweight browser
     * thumbnail worker has no WebView/SVG renderer.</p>
     */
    @Nullable
    static String findEpubCoverImagePath(ZipFile zip) {
        if (zip == null) return null;
        try {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry == null) return null;
            Document containerDoc;
            try (InputStream is = zip.getInputStream(containerEntry)) {
                containerDoc = secureDocumentBuilder().parse(is);
            }
            NodeList rootFiles = containerDoc.getElementsByTagName("rootfile");
            if (rootFiles.getLength() == 0) {
                rootFiles = containerDoc.getElementsByTagNameNS("*", "rootfile");
            }
            if (rootFiles.getLength() == 0) return null;
            String opfPath = normalizeZipPath(decodeHref(attributeValue(
                    rootFiles.item(0).getAttributes(), "full-path")));
            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null || opfEntry.isDirectory()) return null;

            Document opfDoc;
            try (InputStream is = zip.getInputStream(opfEntry)) {
                opfDoc = secureDocumentBuilder().parse(is);
            }
            String basePath = parentPath(opfPath);
            Map<String, EpubManifestItem> manifest = new LinkedHashMap<>();
            NodeList items = opfDoc.getElementsByTagName("item");
            if (items.getLength() == 0) items = opfDoc.getElementsByTagNameNS("*", "item");
            EpubManifestItem epub3Cover = null;
            EpubManifestItem namedCover = null;
            EpubManifestItem firstRaster = null;
            for (int i = 0; i < items.getLength(); i++) {
                Node itemNode = items.item(i);
                if (!isDirectChildOf(itemNode, "manifest")) continue;
                NamedNodeMap attrs = itemNode.getAttributes();
                String id = attributeValue(attrs, "id");
                String href = attributeValue(attrs, "href");
                if (id.isEmpty() || href.isEmpty()) continue;
                EpubManifestItem item = new EpubManifestItem(
                        id,
                        normalizeZipPath(basePath + "/" + decodeHref(href)),
                        attributeValue(attrs, "media-type"),
                        attributeValue(attrs, "properties"),
                        attributeValue(attrs, "fallback"),
                        attributeValue(attrs, "media-overlay"));
                manifest.put(id, item);
                if (!isRasterCoverResource(zip, item)) continue;
                if (containsProperty(item.properties, "cover-image")) epub3Cover = item;
                String probe = (item.id + " " + fileNameFromPath(item.path))
                        .toLowerCase(Locale.ROOT);
                if (namedCover == null && probe.contains("cover")) namedCover = item;
                if (firstRaster == null) firstRaster = item;
            }
            if (epub3Cover != null) return epub3Cover.path;

            String epub2CoverId = "";
            NodeList metas = opfDoc.getElementsByTagName("meta");
            if (metas.getLength() == 0) metas = opfDoc.getElementsByTagNameNS("*", "meta");
            for (int i = 0; i < metas.getLength(); i++) {
                NamedNodeMap attrs = metas.item(i).getAttributes();
                if (!"cover".equalsIgnoreCase(attributeValue(attrs, "name"))) continue;
                epub2CoverId = attributeValue(attrs, "content");
                if (!epub2CoverId.isEmpty()) break;
            }
            EpubManifestItem epub2Cover = manifest.get(epub2CoverId);
            if (isRasterCoverResource(zip, epub2Cover)) return epub2Cover.path;
            if (namedCover != null) return namedCover.path;

            for (EpubSpineItem spineItem : findEpubSpineItems(zip)) {
                EpubManifestItem item = manifest.get(spineItem.manifestId);
                if (isRasterCoverResource(zip, item)) return item.path;
            }
            return firstRaster != null ? firstRaster.path : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isRasterCoverResource(ZipFile zip,
                                                 @Nullable EpubManifestItem item) {
        if (zip == null || item == null || item.path.isEmpty()) return false;
        ZipEntry entry = zip.getEntry(item.path);
        if (entry == null || entry.isDirectory()) return false;
        String lowerMime = item.mediaType.toLowerCase(Locale.ROOT);
        String lowerPath = item.path.toLowerCase(Locale.ROOT);
        return ("image/jpeg".equals(lowerMime)
                || "image/png".equals(lowerMime)
                || "image/gif".equals(lowerMime)
                || "image/webp".equals(lowerMime)
                || "image/bmp".equals(lowerMime)
                || lowerPath.endsWith(".jpg")
                || lowerPath.endsWith(".jpeg")
                || lowerPath.endsWith(".png")
                || lowerPath.endsWith(".gif")
                || lowerPath.endsWith(".webp")
                || lowerPath.endsWith(".bmp"));
    }

    /**
     * Reads non-spine OPF data needed while a page is hosted in WebView. The
     * path-to-MIME map keeps script, XML, media, and custom binding resources
     * from being downgraded to {@code application/octet-stream}. Bindings are
     * accepted only when their handler is an existing scripted XHTML manifest
     * item; Chromium does not implement the EPUB bindings element itself, so
     * the activity may explicitly host that local handler.
     */
    static EpubPackageResources findEpubPackageResources(ZipFile zip) {
        EpubPackageResources result = new EpubPackageResources();
        if (zip == null) return result;
        try {
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry == null) return result;
            Document containerDoc;
            try (InputStream is = zip.getInputStream(containerEntry)) {
                containerDoc = secureDocumentBuilder().parse(is);
            }
            NodeList rootFiles = containerDoc.getElementsByTagName("rootfile");
            if (rootFiles.getLength() == 0) {
                rootFiles = containerDoc.getElementsByTagNameNS("*", "rootfile");
            }
            if (rootFiles.getLength() == 0) return result;
            String opfPath = normalizeZipPath(decodeHref(attributeValue(
                    rootFiles.item(0).getAttributes(), "full-path")));
            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) return result;
            result.packagePath = opfPath;
            Document opfDoc;
            try (InputStream is = zip.getInputStream(opfEntry)) {
                opfDoc = secureDocumentBuilder().parse(is);
            }
            String basePath = parentPath(opfPath);
            Map<String, EpubManifestItem> manifest = new LinkedHashMap<>();
            NodeList items = opfDoc.getElementsByTagName("item");
            if (items.getLength() == 0) items = opfDoc.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Node manifestNode = items.item(i);
                if (!isDirectChildOf(manifestNode, "manifest")) continue;
                NamedNodeMap attrs = manifestNode.getAttributes();
                String id = attributeValue(attrs, "id");
                String href = attributeValue(attrs, "href");
                if (id.isEmpty() || href.isEmpty()) continue;
                String path = normalizeZipPath(basePath + "/" + decodeHref(href));
                String mediaType = attributeValue(attrs, "media-type");
                EpubManifestItem item = new EpubManifestItem(
                        id,
                        path,
                        mediaType,
                        attributeValue(attrs, "properties"),
                        attributeValue(attrs, "fallback"),
                        attributeValue(attrs, "media-overlay"));
                manifest.put(id, item);
                ZipEntry resourceEntry = zip.getEntry(path);
                if (resourceEntry != null && !resourceEntry.isDirectory()
                        && !mediaType.isEmpty()) {
                    result.mediaTypesByPath.put(path, mediaType);
                }
            }

            NodeList bindingNodes = opfDoc.getElementsByTagName("mediaType");
            if (bindingNodes.getLength() == 0) {
                bindingNodes = opfDoc.getElementsByTagNameNS("*", "mediaType");
            }
            for (int i = 0; i < bindingNodes.getLength(); i++) {
                Node bindingNode = bindingNodes.item(i);
                if (!isDirectChildOf(bindingNode, "bindings")) continue;
                NamedNodeMap attrs = bindingNode.getAttributes();
                String mediaType = attributeValue(attrs, "media-type");
                String handlerId = attributeValue(attrs, "handler");
                EpubManifestItem handler = manifest.get(handlerId);
                ZipEntry handlerEntry = handler != null ? zip.getEntry(handler.path) : null;
                if (!isConcreteMediaType(mediaType) || handler == null
                        || !"application/xhtml+xml".equalsIgnoreCase(handler.mediaType)
                        || !isEpubHtmlPath(handler.path)
                        || !containsProperty(handler.properties, "scripted")
                        || handlerEntry == null
                        || handlerEntry.isDirectory()) {
                    continue;
                }
                result.bindingsByMediaType.putIfAbsent(
                        mediaType.toLowerCase(Locale.ROOT),
                        new EpubBinding(mediaType, handler.path));
            }

            NodeList metas = opfDoc.getElementsByTagName("meta");
            if (metas.getLength() == 0) metas = opfDoc.getElementsByTagNameNS("*", "meta");
            for (int i = 0; i < metas.getLength(); i++) {
                Node meta = metas.item(i);
                String property = attributeValue(meta.getAttributes(), "property");
                if (!"media:active-class".equalsIgnoreCase(property)) continue;
                String value = meta.getTextContent() != null ? meta.getTextContent().trim() : "";
                if (value.matches("[A-Za-z_-][A-Za-z0-9_-]{0,63}")) {
                    result.mediaOverlayActiveClass = value;
                }
                break;
            }
        } catch (Exception ignored) {
            // Auxiliary capabilities are optional; base spine rendering remains.
        }
        return result;
    }

    private static boolean containsProperty(String properties, String wanted) {
        if (properties == null || wanted == null) return false;
        for (String token : properties.trim().split("\\s+")) {
            if (wanted.equals(token)) return true;
        }
        return false;
    }

    private static boolean isDirectChildOf(Node node, String parentLocalName) {
        if (node == null || parentLocalName == null) return false;
        Node parent = node.getParentNode();
        if (parent == null) return false;
        String localName = parent.getLocalName();
        if (parentLocalName.equals(localName)) return true;
        String nodeName = parent.getNodeName();
        return parentLocalName.equals(nodeName)
                || (nodeName != null && nodeName.endsWith(":" + parentLocalName));
    }

    private static boolean isConcreteMediaType(String mediaType) {
        if (mediaType == null) return false;
        String value = mediaType.trim();
        int slash = value.indexOf('/');
        if (slash <= 0 || slash != value.lastIndexOf('/') || slash == value.length() - 1) {
            return false;
        }
        return isMediaTypeToken(value.substring(0, slash))
                && isMediaTypeToken(value.substring(slash + 1));
    }

    private static boolean isMediaTypeToken(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alphaNumeric = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!alphaNumeric && "!#$&^_.+-".indexOf(c) < 0) return false;
        }
        return true;
    }

    private static EpubManifestItem resolveSupportedEpubSpineItem(
            ZipFile zip,
            Map<String, EpubManifestItem> manifest,
            String initialId) {
        String id = initialId != null ? initialId : "";
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth < MAX_EPUB_FALLBACK_DEPTH && !id.isEmpty(); depth++) {
            if (!visited.add(id)) return null;
            EpubManifestItem item = manifest.get(id);
            if (item == null) return null;
            if (isSupportedEpubSpineResource(zip, item)) return item;
            id = item.fallback;
        }
        return null;
    }

    private static boolean isSupportedEpubSpineResource(ZipFile zip, EpubManifestItem item) {
        if (zip == null || item == null || item.path.isEmpty()) return false;
        ZipEntry entry = zip.getEntry(item.path);
        if (entry == null || entry.isDirectory()) return false;
        EpubSpineItem probe = new EpubSpineItem(
                item.path, item.mediaType, item.properties, item.fallback);
        if (probe.isHtml()) return true;
        return probe.isImage() && mimeForPath(item.path).startsWith("image/");
    }

    private static String attributeValue(NamedNodeMap attrs, String name) {
        if (attrs == null || name == null) return "";
        Node value = attrs.getNamedItem(name);
        if (value == null) value = attrs.getNamedItemNS("*", name);
        return value != null && value.getNodeValue() != null
                ? value.getNodeValue().trim() : "";
    }

    private static String combineProperties(String... values) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.trim().isEmpty()) continue;
                for (String token : value.trim().split("\\s+")) {
                    if (!token.isEmpty()) tokens.add(token);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (out.length() > 0) out.append(' ');
            out.append(token);
        }
        return out.toString();
    }

    static List<String> findEpubHtmlEntries(ZipFile zip) {
        ArrayList<String> result = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && isEpubHtmlPath(entry.getName())) result.add(entry.getName());
        }
        java.util.Collections.sort(result);
        return result;
    }

    /** Cap a single document text entry (EPUB/HTML/OPF, Word/HWPX XML) so a crafted
     *  oversized entry can't exhaust memory during page rendering. */
    private static final long MAX_DOCUMENT_TEXT_ENTRY_BYTES = 32L * 1024L * 1024L;

    static String readZipEntryString(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] data = readAllBytesWithLimit(is, MAX_DOCUMENT_TEXT_ENTRY_BYTES);
            return DocumentTextDecoder.decode(data);
        }
    }

    static byte[] readAllBytesWithLimit(InputStream is, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long total = 0L;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Document text entry exceeds size limit");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String readZipEntryPreviewString(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] data = readAtMostBytes(is, DETECTION_TEXT_READ_LIMIT_BYTES);
            return DocumentTextDecoder.decode(data);
        }
    }

    private static byte[] readAtMostBytes(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(8192, Math.max(0, maxBytes)));
        byte[] buf = new byte[8192];
        int remaining = Math.max(0, maxBytes);
        while (remaining > 0) {
            int n = is.read(buf, 0, Math.min(buf.length, remaining));
            if (n == -1) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    static DocumentBuilder secureDocumentBuilder() throws Exception {
        return SecureXml.newDocumentBuilder(true);
    }

    static Node firstNodeByLocalName(Document doc, String localName) {
        NodeList list = doc.getElementsByTagNameNS("*", localName);
        if (list.getLength() > 0) return list.item(0);
        list = doc.getElementsByTagName("w:" + localName);
        if (list.getLength() > 0) return list.item(0);
        list = doc.getElementsByTagName(localName);
        if (list.getLength() > 0) return list.item(0);
        return null;
    }

    static Node firstDirectChildByLocalName(Node node, String localName) {
        if (node == null) return null;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (localName.equals(child.getLocalName()) || ("w:" + localName).equals(child.getNodeName())) return child;
        }
        return null;
    }

    static String titleFromHtml(String html) {
        if (html == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (m.find()) return htmlToText(m.group(1)).trim();
        m = java.util.regex.Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>").matcher(html);
        if (m.find()) return htmlToText(m.group(1)).trim();
        return "";
    }

    static String htmlToText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ");
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Builds a local XHTML canvas for an OPF spine item that is an image. */
    static String buildDirectImageSpineHtml(String imagePath, int width, int height) {
        String rawFileName = fileNameFromPath(imagePath);
        String fileName = escapeHtml(rawFileName);
        String source = escapeHtml(UriPathCodec.encodePathSegment(rawFileName));
        String viewport = width >= 100 && height >= 100
                ? "<meta name=\"viewport\" content=\"width=" + width
                + ", height=" + height + "\"/>"
                : "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>";
        return "<!doctype html><html><head><meta charset=\"UTF-8\"/>"
                + viewport
                + "<title>" + fileName + "</title></head>"
                + "<body><img src=\"" + source + "\" alt=\"\"/></body></html>";
    }

    static boolean isEpubHtmlPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    static String parentPath(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    static String fileNameFromPath(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    static String decodeHref(String href) {
        return UriPathCodec.decodePercentEscapes(href);
    }

    static String normalizeZipPath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        ArrayList<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append('/');
            out.append(parts.get(i));
        }
        return out.toString();
    }

    static String mimeForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xhtml")) return "application/xhtml+xml";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".smil")) return "application/smil+xml";
        if (lower.endsWith(".opf")) return "application/oebps-package+xml";
        if (lower.endsWith(".ncx")) return "application/x-dtbncx+xml";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".vtt")) return "text/vtt";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) return "audio/mp4";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".oga") || lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    static String attr(Node node, String qualified, String localName) {
        if (node == null || node.getAttributes() == null) return null;
        Node a = node.getAttributes().getNamedItem(qualified);
        if (a == null) a = node.getAttributes().getNamedItem(localName);
        if (a == null) a = node.getAttributes().getNamedItemNS("*", localName);
        return a != null ? a.getNodeValue() : null;
    }
}
