# Readwide 1.0.2 direct dependency license report

This report is generated from the dependencies declared in `app/build.gradle` plus source-bundled notices that are relevant to the default Readwide 1.0.2 build.

It is a **direct-dependency/source-declared report**, not a fully resolved transitive SBOM. For strict repository submission, regenerate a resolved Gradle dependency report/SBOM from a clean network-enabled build environment.

## App identity

| Field | Value |
| --- | --- |
| App name | Readwide |
| Android applicationId | `com.textview.reader` |
| Version name | `1.0.2` |
| Version code | `10002` |
| First-party license | Apache-2.0 |

## Runtime dependencies declared in `app/build.gradle`

| Maven coordinate | Purpose | Recorded license position |
| --- | --- | --- |
| `androidx.appcompat:appcompat:1.7.1` | AppCompat UI/runtime support | Apache-2.0 |
| `com.google.android.material:material:1.14.0` | Material Components UI support | Apache-2.0 |
| `androidx.recyclerview:recyclerview:1.4.0` | RecyclerView UI lists | Apache-2.0 |
| `androidx.constraintlayout:constraintlayout:2.2.1` | ConstraintLayout UI layouts | Apache-2.0 |
| `androidx.activity:activity:1.10.1` | AndroidX Activity APIs | Apache-2.0 |
| `androidx.drawerlayout:drawerlayout:1.2.0` | DrawerLayout UI | Apache-2.0 |
| `com.github.albfernandez:juniversalchardet:2.5.0` | Text encoding detection | MPL-1.1 option from upstream tri-license |
| `org.apache.commons:commons-compress:1.28.0` | TAR/7z/stream archive support and ZIP method fallback | Apache-2.0 |
| `me.zhanghai.android.libarchive:library:1.1.6` | Android libarchive backend for RAR/backend-dependent archive paths | Apache-2.0 for Android artifact plus permissive BSD-style native libarchive notices |
| `org.tukaani:xz:1.12` | XZ/LZMA/LZMA2 codec support | 0BSD |
| `com.github.luben:zstd-jni:1.5.7-9` | Zstandard codec used by Commons Compress | BSD-family licensing path recorded in notices |
| `net.lingala.zip4j:zip4j:2.11.6` | ZIP/CBZ listing/extraction/password/split support | Apache-2.0 |
| `kr.dogfoot:hwplib:1.1.10` | HWP 5.x text extraction backend | Apache-2.0 |
| `kr.dogfoot:hwpxlib:1.0.9` | HWPX text extraction backend | Apache-2.0 |

## Test dependencies declared in `app/build.gradle`

| Maven coordinate | Purpose | Recorded license position |
| --- | --- | --- |
| `junit:junit:4.13.2` | JVM unit tests | EPL-1.0 |
| `androidx.test:runner:1.7.0` | Android instrumentation test runner | Apache-2.0 |
| `androidx.test.ext:junit:1.3.0` | AndroidX JUnit extension | Apache-2.0 |

## Build tooling

| Component | Purpose | License / terms position |
| --- | --- | --- |
| Gradle wrapper / Gradle | Build system wrapper and build runtime | Apache-2.0 |
| Android Gradle Plugin `com.android.application` 9.2.0 | Android build plugin resolved from Google Maven | Android SDK / Google Maven distribution terms; not vendored as app runtime code |

## Source-bundled / first-party notices

| Component | Purpose | License/provenance note |
| --- | --- | --- |
| Readwide first-party source | App code | Apache-2.0 |
| RAR3/RAR4 PPMd decoder code | Scoped decode-only fallback | First-party Java implementation of public-domain algorithm behavior; no UnRAR/libarchive source copied |
| RAR5 compressed decoder code | Scoped decode-only fallback | First-party Java implementation; no RAR compression/creation/password recovery; no UnRAR/libarchive source copied |
| xunazo-derived AZO Java port | EGG AZO extraction | zlib license notice retained in source; modified extraction-only port |
| `javax.xml.bind.DatatypeConverter` shim | hwplib Java 17/Android compatibility | Project-local shim covered by Readwide Apache-2.0 license |
| Launcher artwork | App icon | Project-owned generated artwork; see `docs/ASSET_PROVENANCE.md` |

## Release checklist tied to this report

Before publishing source or APK release materials:

1. Include `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`, `docs/FOSS_STATUS.md`, this report, and `docs/SBOM_READWIDE_1_0_2.spdx.json`.
2. Confirm `app/libs` is absent or contains no optional local jars.
3. Confirm no Junrar/UnRAR-license code or jar is bundled in the default source/APK.
4. Confirm native dependency notices for libarchive-android/libarchive and zstd-jni/Zstandard are kept with binary release materials.
5. Regenerate a resolved Gradle/transitive dependency report before stricter repository submission.
