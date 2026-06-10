# FOSS status for Readwide 1.0.1

This note summarizes the default source package and default release APK status for communities that require Free and Open Source Software (FOSS), such as r/FOSSdroid.

## Current assessment

The default Readwide 1.0.1 source package is intended to fit the usual FOSS definition:

- the first-party source is licensed under Apache License 2.0;
- the source code is included in the repository/source package;
- the app can be run, copied, distributed, studied, changed, and improved under the applicable licenses;
- the default build does not include a proprietary app EULA;
- the default build no longer includes Junrar or other UnRAR-license fallback code;
- the source package does not include private signing files, keystores, build outputs, or bundled proprietary binary dependencies;
- the app manifest disables Android app-data Auto Backup with `android:allowBackup="false"` for local-data privacy consistency.

This is not a legal opinion, but it is the current project-level compliance position for the default Readwide 1.0.1 source/APK line.

## Default runtime dependency status

The default runtime dependency graph uses FOSS-compatible licenses:

| Component | Use | License status |
|---|---|---|
| Readwide first-party source | app code | Apache License 2.0 |
| AndroidX / Material Components | Android UI/runtime support | Apache License 2.0 |
| Apache Commons Compress | primary TAR/7z/stream archive support and ZIP method fallback | Apache License 2.0 |
| Zip4j | primary ZIP/CBZ handling plus password/AES-specialist handling | Apache License 2.0 |
| libarchive-android | default RAR read/extract backend plus ZIP/TAR/7z compatibility fallback | Android library artifact under Apache License 2.0; bundled native libarchive is BSD-style |
| zstd-jni | Zstandard codec used by Commons Compress | JNI binding under BSD 2-Clause; native Zstandard under the permissive BSD licensing path |
| XZ for Java | XZ/LZMA support | 0BSD |
| JUniversalChardet | text encoding detection | MPL-1.1 option used by this project |
| xunazo-derived AZO decoder port | EGG AZO extraction | zlib license notice retained in source |

## RAR/CBR licensing boundary

RAR/CBR support is deliberately extraction-only.

Readwide 1.0.1 does **not** bundle Junrar or RARLAB UnRAR-license code in the default build. The old Junrar fallback was removed because UnRAR-style license restrictions can conflict with FOSS-focused distribution expectations.

The default RAR path is now:

1. bundled libarchive-android as the default RAR read/extract backend;
2. first-party Java metadata/stored-entry handling where covered;
3. first-party RAR5 stored-entry handling where covered;
4. dedicated Java ZIP/TAR/7z readers as their primary paths, with libarchive retained as fallback;
5. no optional local RAR5 decoder bridge in the default source tree; unsupported cases fail cleanly.

`junrar/commons-vfs-rar` was reviewed and rejected because it depends on `com.github.junrar:junrar`, which would reintroduce the same Junrar/UnRAR-license concern.

## Optional local jars

The default source package contains no optional decoder jar under `app/libs`, and no optional RAR decoder bridge is wired into the default archive stack. In a clean source package the `app/libs` folder may be absent.

The default Gradle dependency graph does not include a local `app/libs/*.jar` dependency hook. If a developer adds one in a private fork, that custom build must be treated as a separate build and its license must be rechecked before calling the resulting APK FOSS.

The default GitHub source package and default release APK should be evaluated without optional local jars unless the release notes explicitly say otherwise.

## Release signing boundary

Release signing is conditional in the public Gradle build. When the four `TEXTVIEW_*` signing values are not provided, `assembleRelease` can build an unsigned release artifact for F-Droid-style source builders instead of requiring a developer keystore. Local public release APKs should still be signed with the developer release key outside the committed source tree.

The GitHub source keeps the standard Gradle wrapper files for developer convenience. The F-Droid metadata draft at `fdroid/metadata/com.textview.reader.yml` removes `gradle/wrapper/gradle-wrapper.jar` before build scanning so F-Droid can build with its own Gradle environment.

## Binary release notes

When distributing APK/AAB files, keep these files available alongside the binary release materials:

- `LICENSE`
- `NOTICE`
- `THIRD_PARTY_NOTICES.md`
- `PRIVACY.md`
- `docs/LICENSE_REPORT_READWIDE_1_0_1.md`
- `docs/SBOM_READWIDE_1_0_1.spdx.json`
- this `docs/FOSS_STATUS.md` note

The Gradle packaging block excludes duplicate dependency `META-INF/LICENSE*` / `META-INF/NOTICE*` resources to avoid Android packaging conflicts. That does not remove the release obligation to provide the project-level license and third-party notices with the source and binary release materials.

## Current caveats

- RAR support is not complete. Common compressed RAR entries are attempted through libarchive-android by default, with first-party Java used for stored-entry fallback and metadata/safety handling. Split/multi-volume RAR and encrypted RAR are not guaranteed in the current GitHub-ready package because they have not been re-tested for this release. Broad compressed-solid/header-encrypted/SFX/unusual RAR variants are not guaranteed. ZIP/TAR/7z keep dedicated Java readers first, with libarchive retained for fallback and special cases.
- Optional local jars are outside the default FOSS assessment unless explicitly audited and documented. No optional RAR decoder jar is wired into the default archive stack.
- Android Gradle Plugin, Android SDK, and Gradle tooling are build-time tools resolved from their normal upstream channels; they are not vendored into the app source package.
- This package includes a direct-dependency license report (`docs/LICENSE_REPORT_READWIDE_1_0_1.md`) and a source-declared direct-dependency SPDX draft (`docs/SBOM_READWIDE_1_0_1.spdx.json`). They are not a Gradle-resolved transitive SBOM. For stricter distribution channels, regenerate a full resolved dependency report/SBOM from a network-enabled build environment before submission.
