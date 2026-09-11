package com.readwide.manager.archive;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class NativeBackendReleaseRulesTest {
    @Test
    public void releaseRulesKeepLibarchiveJniBindingPackage() throws IOException {
        String rules = readProguardRules();
        assertTrue(rules.contains("-keep class me.zhanghai.android.libarchive.** { *; }"));
        assertTrue(rules.contains("-dontwarn me.zhanghai.android.libarchive.**"));
    }

    @Test
    public void releaseRulesTreatZstdJniAsTestOnlyOptionalCodec() throws IOException {
        String rules = readProguardRules();
        assertTrue(!rules.contains("-keep class com.github.luben.zstd.** { *; }"));
        assertTrue(rules.contains("-dontwarn com.github.luben.zstd.**"));
    }

    @Test
    public void rarAvailabilityDoesNotDependOnPerFormatProbe() throws IOException {
        String bridge = readProjectFile("app/src/main/java/com/readwide/manager/archive/LibarchiveNativeBridge.java", "src/main/java/com/readwide/manager/archive/LibarchiveNativeBridge.java");
        assertTrue(bridge.contains("private static final String RAR_FORMAT_PROBE_STATUS"));
        assertTrue(bridge.contains("return AVAILABLE;"));
        assertTrue(bridge.contains("probeRarFormatSupportForDiagnosticsOnly"));
        assertTrue(!bridge.contains("RAR_FORMAT_AVAILABLE"));
    }

    @Test
    public void libarchiveBridgeUsesFileNameOpenBeforeCallbackFallback() throws IOException {
        String bridge = readProjectFile("app/src/main/java/com/readwide/manager/archive/LibarchiveNativeBridge.java", "src/main/java/com/readwide/manager/archive/LibarchiveNativeBridge.java");
        assertTrue(bridge.contains("openReaderWithFileNames"));
        assertTrue(bridge.contains("Archive.readOpenFileName("));
        assertTrue(bridge.contains("Archive.readOpenFileNames("));
        assertTrue(bridge.contains("openReaderWithCallbacks"));
        assertTrue(bridge.contains("shouldUseCallbackReaderFallback"));
    }

    @Test
    public void libarchiveBridgeUsesOwnedHeaderPointerIteration() throws IOException {
        String bridge = readProjectFile("app/src/main/java/com/readwide/manager/archive/LibarchiveNativeBridge.java", "src/main/java/com/readwide/manager/archive/LibarchiveNativeBridge.java");
        assertTrue(bridge.contains("Archive.readNextHeader(archive)"));
        assertTrue(!bridge.contains("Archive.readNextHeader2("));
        assertTrue(!bridge.contains("ArchiveEntry.new2("));
    }

    @Test
    public void rawZstdReaderRequiresSelectedZstdFilter() throws IOException {
        String bridge = readProjectFile("app/src/main/java/com/readwide/manager/archive/LibarchiveNativeBridge.java", "src/main/java/com/readwide/manager/archive/LibarchiveNativeBridge.java");
        assertTrue(bridge.contains("Archive.readSupportFilterZstd(archive)"));
        assertTrue(bridge.contains("Archive.filterCode(archive, index) == Archive.FILTER_ZSTD"));
        assertTrue(bridge.contains("Input is not a Zstandard-compressed stream"));
    }

    @Test
    public void apkSourceCarriesPinnedNativeBackendNotices() throws IOException {
        String notices = readProjectFile(
                "app/src/main/assets/open_source_licenses/libarchive_android_and_codecs.txt",
                "src/main/assets/open_source_licenses/libarchive_android_and_codecs.txt");
        assertTrue(notices.contains("3a592be028c7be41847f667570bd343c0010bd9d"));
        assertTrue(notices.contains("27cbc7827172698143e440801fc0ba39ccb4f1f5"));
        assertTrue(notices.contains("libarchive  27cbc7827172698143e440801fc0ba39ccb4f1f5 (3.8.9)"));
        assertTrue(notices.contains("4b73f2ec19a99ef465282fbce633e8deb33691b3"));
        assertTrue(notices.contains("068ff080b369adfac81509f9b57b2afabaf82dc5"));
        assertTrue(notices.contains("bzip2 / libbzip2"));
        assertTrue(notices.contains("XZ Utils / liblzma"));
        assertTrue(notices.contains("Zstandard"));
        assertTrue(notices.contains("Mbed TLS / libmbedcrypto"));
        assertTrue(notices.contains("END OF TERMS AND CONDITIONS"));
    }

    @Test
    public void nativeBackendUsesVendoredSourceModuleInsteadOfMavenAar() throws IOException {
        String build = readProjectFile("app/build.gradle", "build.gradle");
        assertTrue(build.contains("implementation project(':libarchiveAndroid')"));
        assertTrue(!build.contains("me.zhanghai.android.libarchive:library:"));

        String upstream = readProjectFile(
                "third_party/libarchive-android/UPSTREAM.md",
                "../third_party/libarchive-android/UPSTREAM.md");
        assertTrue(upstream.contains("3a592be028c7be41847f667570bd343c0010bd9d"));
        assertTrue(upstream.contains("27cbc7827172698143e440801fc0ba39ccb4f1f5"));
        assertTrue(upstream.contains("libarchive 3.8.9"));
        assertTrue(upstream.contains("No prebuilt AAR or"));
        assertTrue(upstream.contains("native library is stored in this directory"));
    }

    @Test
    public void nativeBackendCompilesCabAndLhaReaders() throws IOException {
        String cmake = readProjectFile(
                "third_party/libarchive-android/library/CMakeLists.txt",
                "../third_party/libarchive-android/library/CMakeLists.txt");
        assertTrue(cmake.contains("archive_read_support_format_cab.c"));
        assertTrue(cmake.contains("archive_read_support_format_lha.c"));

        String detector = readProjectFile(
                "app/src/main/java/com/readwide/manager/archive/ArchiveTypeDetector.java",
                "src/main/java/com/readwide/manager/archive/ArchiveTypeDetector.java");
        assertTrue(detector.contains("name.endsWith(\".cab\")"));
        assertTrue(detector.contains("name.endsWith(\".lha\")"));
        assertTrue(detector.contains("name.endsWith(\".lzh\")"));
    }

    private static String readProguardRules() throws IOException {
        return readProjectFile("app/proguard-rules.pro", "proguard-rules.pro");
    }

    private static String readProjectFile(String preferred) throws IOException {
        return readProjectFile(preferred, preferred);
    }

    private static String readProjectFile(String preferred, String fallback) throws IOException {
        Path moduleRoot = Paths.get(System.getProperty("user.dir", "."));
        Path direct = moduleRoot.resolve(fallback);
        Path fromProjectRoot = moduleRoot.resolve(preferred);
        Path path = Files.exists(fromProjectRoot) ? fromProjectRoot : direct;
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
