# Keep Bookmark/ReaderState models for JSON serialization.
-keep class com.readwide.manager.model.** { *; }
-keepclassmembers class com.readwide.manager.model.** {
    public *;
}

# JUniversalChardet is invoked reflectively from FileUtils.
-keep class org.mozilla.universalchardet.** { *; }
-dontwarn org.mozilla.universalchardet.**

# Keep WebView bridge methods exposed through @JavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Strip low-value release logs while leaving warnings/errors available.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep stack trace line numbers but anonymize source-file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Zstandard support is bundled through com.github.luben:zstd-jni for Commons Compress ZIP fallback.
# Keep its JNI-facing classes/members in release builds.
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# libarchive-android exposes a JNI binding layer. Keep the full package so R8 cannot
# rename/remove native methods, callback interfaces, ArchiveEntry accessors, or format
# enable methods used by the bundled archive-jni library. This mirrors the library's
# own consumer ProGuard rule and keeps release APK behavior aligned with debug builds.
-keep class me.zhanghai.android.libarchive.** { *; }
-dontwarn me.zhanghai.android.libarchive.**

# dogfoot HWP/HWPX libraries are the scoped text extraction backend for .hwp/.hwpx.
# Keep them stable because the libraries contain object graphs and reader helpers that
# may be traversed indirectly during document parsing.
-keep class kr.dogfoot.hwplib.** { *; }
-keep class kr.dogfoot.hwpxlib.** { *; }
-dontwarn kr.dogfoot.hwplib.**
-dontwarn kr.dogfoot.hwpxlib.**
-dontwarn javax.xml.bind.**

# PdfBox-Android (PDF text extraction for in-document Find). The optional
# JP2/JPEG2000 image decoder (com.gemalto.jp2) is not bundled; JPX images are
# ignored at runtime, so tell R8 to ignore the missing optional class instead of
# failing the release build. PdfBox itself is pure Java and can be shrunk.
-dontwarn com.gemalto.jp2.**
