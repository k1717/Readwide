# GitHub upload notes — Readwide 1.0.18

This checklist is for the maintainer's manual upload. It does not authorize or
record a commit, tag, release, F-Droid submission or device installation.
The maintainer reported a successful 1.0.18 build before the latest timeout-field
and translation changes. That UI/resource follow-up has not been rebuilt;
remaining validation is tracked in the release handoff below.

## Source identity and handoff

- Repository: [k1717/Readwide](https://github.com/k1717/Readwide).
- Application ID: `com.readwide.manager`.
- Version name/code: `1.0.18` / `10018`.
- First-party license: Apache-2.0; dependencies keep their own notices and licenses.
- Release text: [1.0.18 release notes](docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_18.md).
- Remaining validation and exclusions: [release handoff](docs/RELEASE_READINESS_1_0_18.md).

The full-source ZIP contains the **repository-root contents**, not an outer
`source/` directory and not only changed files. After unpacking, `settings.gradle`,
`gradlew`, `app/` and `third_party/` belong at the repository root. Review the
diff before committing; do not accidentally retain obsolete files from an older
checkout. Do not nest this project inside another `source/` or `app/` directory.

## Keep in the source commit

Keep the Gradle wrapper, app/native source, scripts, tests, fastlane metadata and
these documents:

- `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`.
- `README.md`, `CHANGELOG.md`, `PATCHNOTES.md`, `RELEASE_BUILD.md`.
- Current release notes, code map, source status and format-specific scope notes.
- Vendored component licenses, `UPSTREAM.md` files and corresponding native source,
  including libarchive's checked-in `build/cmake/` modules.
- Packaged open-source notices under `app/src/main/assets/open_source_licenses/`.
- The 1.0.18 license report/SBOM with current app identity and source-declared
  dependencies. Keep older versioned reports as historical records.
- Historical release notes and the explicitly labeled historical F-Droid mirror.

Do not include keystores, signing passwords, local properties, personal documents/
backups, private logs, IDE/cache folders or generated build trees. Intentional
release APKs are assets, not source files. The public developer contact address
and licensed test fixtures are intentional, not private signing material.

## Re-create a source ZIP only when needed

The supplied ZIP is already packaged. If source changes afterward, create a
**new** ZIP outside the repository root; never overwrite an older package.
Both scripts exclude generated material, preserve the vendored CMake source and
store portable `/` paths with executable modes for `gradlew` and shell scripts.

From the project root in PowerShell:

```powershell
$sourceZip = Join-Path (Split-Path -Parent (Get-Location).Path) ("Readwide-1.0.18-github-source-full-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".zip")
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\create_source_zip.ps1 -Output $sourceZip
Get-FileHash -LiteralPath $sourceZip -Algorithm SHA256
```

Linux/macOS with Python:

```bash
python3 scripts/create_source_zip.py "../Readwide-1.0.18-github-source-full-$(date +%Y%m%d-%H%M%S).zip"
```

Windows ZIP extraction may not retain Unix executable bits in Git. If the reviewed
Git diff shows lost modes, stage `gradlew` and the packaged shell scripts as
executable before committing; do not mark all source files executable.

## Commit, tag and APK publication

1. Review the unpacked source diff and the known limitations. Source/package
   checks are not APK validation.
2. Run the maintainer checks in [RELEASE_BUILD.md](RELEASE_BUILD.md) on the exact
   final tree. The reported build predates the latest UI/resource changes; signing, unit-test and device
   results have not been reported and should be recorded separately.
3. Commit the reviewed 1.0.18 source. Once validated, create `v1.0.18` at that exact
   commit. Do not move or replace the already released `v1.0.17` tag.
   If `v1.0.18` already exists, inspect it first; never silently retarget a
   published tag. This handoff did not query remote tag/release state.
4. Use the same project release key for the GitHub APK; verify signing, identity,
   version and installation separately. Never attach an unsigned source-builder
   APK as the public installable APK.
5. Publish the intended public APK as `Readwide_1.0.18.apk`. Local build output
   names may differ. Use the matching release notes and source ZIP, and record
   the APK and source ZIP hashes separately.

Do not state that all RAR/7z files work, that benchmarks passed, or that the current
tree built successfully without evidence from that exact tree.

## F-Droid metadata boundary

The checked-in YAML is a historical mirror through 1.0.13, **not** a ready 1.0.18
submission. Leave historical commits intact. For any later submission, start
from current upstream metadata, use the final tag's immutable 40-character
commit, and follow [project-side notes](docs/FDROID_SUBMISSION.md). A read-only
review on 2026-09-11 confirmed that the public F-Droid listing and upstream
metadata cover 1.0.17; neither a 1.0.18 F-Droid build nor approval is established.
No remote metadata, pipeline, merge request, tag or release was changed.
