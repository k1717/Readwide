# TextView Reader 2.2.6 direct dependency license report

This report records the direct dependencies declared by the default 2.2.6 source package and the project-level license boundary used for public source/APK release review.

Scope and limits:

- This is a direct-dependency report based on `app/build.gradle` and the files included in this source package.
- It is not a Gradle-resolved transitive dependency report because the current validation environment could not download/resolve Gradle artifacts.
- For Play Store, F-Droid, or stricter repository submission, regenerate a full dependency report/SBOM from a network-enabled build environment and compare it against this direct report.
- Optional local jars under `app/libs/*.jar` are outside this report. A build that adds any jar there must be audited separately before being described as the default FOSS build.

## First-party project

| Component | Version / path | Purpose | License position |
| --- | --- | --- | --- |
| TextView Reader first-party source | repository source | Android local reader/file manager/archive viewer | Apache License 2.0; see `LICENSE` and `NOTICE` |
| Modified Java AZO decoder port | `AzoDecoder.java` | EGG method-3/AZO extraction only | zlib license notice retained from `kippler/xunazo`; altered-source status documented in source and `THIRD_PARTY_NOTICES.md` |

## Runtime dependencies declared in `app/build.gradle`

| Maven coordinate | Purpose | License position recorded for release |
| --- | --- | --- |
| `androidx.appcompat:appcompat:1.7.1` | AppCompat UI/runtime support | Apache License 2.0 |
| `com.google.android.material:material:1.14.0` | Material Components UI widgets | Apache License 2.0 |
| `androidx.recyclerview:recyclerview:1.4.0` | RecyclerView lists | Apache License 2.0 |
| `androidx.constraintlayout:constraintlayout:2.2.1` | ConstraintLayout UI layouts | Apache License 2.0 |
| `androidx.activity:activity:1.10.1` | Activity compatibility APIs | Apache License 2.0 |
| `androidx.drawerlayout:drawerlayout:1.2.0` | DrawerLayout UI | Apache License 2.0 |
| `com.github.albfernandez:juniversalchardet:2.5.0` | Text encoding detection helper | MPL-1.1 option used by this project |
| `org.apache.commons:commons-compress:1.28.0` | ZIP fallback, 7z/TAR/compressor stream handling, BZip2 paths | Apache License 2.0 |
| `me.zhanghai.android.libarchive:library:1.1.6` | Bundled Android libarchive backend for archive fallback/RAR compressed paths | Android library artifact under Apache License 2.0; bundled native libarchive under permissive BSD-style upstream notices; keep upstream native notices with binary release materials |
| `org.tukaani:xz:1.12` | XZ/LZMA/LZMA2 codec support | 0BSD |
| `com.github.luben:zstd-jni:1.5.7-9` | Zstandard codec used by Commons Compress ZIP/stream fallback | JNI binding under BSD 2-Clause; bundled native Zstandard available under permissive BSD licensing path |
| `net.lingala.zip4j:zip4j:2.11.6` | Primary ZIP/CBZ listing/extraction/encryption/split support | Apache License 2.0 |

## Test dependencies declared in `app/build.gradle`

| Maven coordinate | Purpose | License position recorded for release |
| --- | --- | --- |
| `junit:junit:4.13.2` | JVM unit tests | Eclipse Public License 1.0 |
| `androidx.test:runner:1.7.0` | Android instrumentation test runner | Apache License 2.0 |
| `androidx.test.ext:junit:1.3.0` | AndroidX JUnit extensions | Apache License 2.0 |

## Build tooling declared in the package

| Component | Purpose | License / terms position |
| --- | --- | --- |
| Gradle wrapper / Gradle | Build system wrapper and build runtime | Apache License 2.0 |
| Android Gradle Plugin `com.android.application` 9.2.0 | Android build plugin resolved from Google Maven | Android SDK / Google Maven distribution terms; not vendored as app runtime code |
| Gradle Foojay Toolchains Resolver Convention Plugin 1.0.0 | Java toolchain resolver | Apache License 2.0; resolved at build time |

## Release checklist tied to this report

Before publishing a source or APK release:

1. Include `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`, `docs/FOSS_STATUS.md`, this report, and `docs/SBOM_2_2_6.spdx.json` with release materials.
2. Confirm `app/libs` is absent or contains no optional local jars.
3. Confirm no Junrar/UnRAR-license code or jar is bundled in the default source/APK.
4. Confirm native dependency notices for libarchive-android/libarchive and zstd-jni/Zstandard are kept with binary release materials.
5. For repository submission, regenerate a resolved dependency report from Gradle and compare transitive dependencies against this direct report.
