package com.textview.reader.archive;

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
    public void releaseRulesKeepZstdJniBindingPackage() throws IOException {
        String rules = readProguardRules();
        assertTrue(rules.contains("-keep class com.github.luben.zstd.** { *; }"));
        assertTrue(rules.contains("-dontwarn com.github.luben.zstd.**"));
    }

    @Test
    public void rarAvailabilityDoesNotDependOnPerFormatProbe() throws IOException {
        String bridge = readProjectFile("app/src/main/java/com/textview/reader/archive/LibarchiveNativeBridge.java");
        assertTrue(bridge.contains("private static final String RAR_FORMAT_PROBE_STATUS"));
        assertTrue(bridge.contains("return AVAILABLE;"));
        assertTrue(bridge.contains("probeRarFormatSupportForDiagnosticsOnly"));
        assertTrue(!bridge.contains("RAR_FORMAT_AVAILABLE"));
    }

    @Test
    public void libarchiveBridgeUsesFileNameOpenBeforeCallbackFallback() throws IOException {
        String bridge = readProjectFile("app/src/main/java/com/textview/reader/archive/LibarchiveNativeBridge.java");
        assertTrue(bridge.contains("openReaderWithFileNames"));
        assertTrue(bridge.contains("Archive.readOpenFileName("));
        assertTrue(bridge.contains("Archive.readOpenFileNames("));
        assertTrue(bridge.contains("openReaderWithCallbacks"));
        assertTrue(bridge.contains("shouldUseCallbackReaderFallback"));
    }

    @Test
    public void libarchiveBridgeUsesOwnedHeaderPointerIteration() throws IOException {
        String bridge = readProjectFile("app/src/main/java/com/textview/reader/archive/LibarchiveNativeBridge.java");
        assertTrue(bridge.contains("Archive.readNextHeader(archive)"));
        assertTrue(!bridge.contains("Archive.readNextHeader2("));
        assertTrue(!bridge.contains("ArchiveEntry.new2("));
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
