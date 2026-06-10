# RAR Fixture QA

Readwide keeps RAR support conservative: normal compressed RAR extraction is libarchive-first, while first-party Java paths cover metadata, safe paths, stored entries, selected stored split paths, and diagnostics. This document describes how to verify that boundary with real fixtures instead of expanding support claims from theory.

## Required external fixture folder

Keep real RAR fixtures outside the public repository. A useful local fixture folder may contain:

```text
rar-test-files-master.zip
sample-1.rar
sample-2.rar
sample-3.rar
sample-4.rar
sample-5.rar
```

The public source package must not include those archives unless their licenses and redistribution rights are explicitly cleared.

## Generate reports

Windows:

```powershell
.\scripts\generate_rar_fixture_reports.ps1 "D:\fixtures\archives" "build\reports\rar-fixtures"
```

macOS/Linux:

```bash
./scripts/generate_rar_fixture_reports.sh /path/to/archive-fixtures build/reports/rar-fixtures
```

The script runs the external RAR fixture smoke test and writes these reports when fixtures are available:

```text
rar_fixture_matrix.md
rar_fixture_boundary.md
rar_solid_boundary.md
rar_solid_first_party_probe.md
rar_solid_probes/
```

## What each report means

- `rar_fixture_matrix.md` records real-fixture listability, first-file extraction, and first-image extraction. This is the fastest compatibility smoke matrix for user-visible behavior.
- `rar_fixture_boundary.md` records route classification: first-party stored paths, libarchive-owned compressed paths, and clean unsupported gaps.
- `rar_solid_boundary.md` separates solid RAR cases from non-solid cases and keeps first-party limits explicit.
- `rar_solid_first_party_probe.md` is diagnostic infrastructure for first-party compressed-solid research. It is not a release support claim.

## How to read failures

Expected unsupported cases should remain cleanly classified as unsupported rather than being described as supported. In particular, do not upgrade public claims for:

- broad RAR3/RAR4 solid archives;
- PPMd payloads;
- custom VM-filtered payloads;
- compressed split chains;
- encrypted compressed RAR;
- RAR5 compressed/solid/encrypted-header archives;
- generic executable/SFX wrappers without a detectable embedded RAR signature.

A report row that succeeds through libarchive confirms backend-dependent behavior for that fixture. It does not mean the same family is complete in the first-party Java decoder.
