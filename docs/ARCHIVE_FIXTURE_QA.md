# Archive fixture QA

Readwide's archive support claims should stay tied to real fixtures. Keep user/private fixture archives outside the public repository and generate local reports before broadening README or release-note wording.

## Generic archive matrix

Use this for ZIP/ZIPX/7z/TAR/ALZ/EGG and single-compressor archive families. RAR has a separate report because its support boundary is more specialized.

```bash
./scripts/generate_archive_fixture_reports.sh /path/to/archive-fixtures build/reports/archive-fixtures
```

```powershell
.\scripts\generate_archive_fixture_reports.ps1 "D:\fixtures\archives" "build\reports\archive-fixtures"
```

If the fixture set needs one shared password, pass it as the third argument or set `TEXTVIEW_ARCHIVE_FIXTURE_PASSWORD`.

The generated `archive_fixture_matrix.md` records:

- archive type detection
- listing success/failure
- first file entry selected for probing
- single-entry extraction success/failure
- password-required, bad-password, unsupported-feature, corrupt-archive, and generic failure classification

The report is diagnostic only. A successful fixture row confirms that exact fixture under the current backend set; it is not a blanket compatibility claim for the whole archive family.

For 7z/CB7, keep separate fixtures for ordinary, password-protected, split, and split+password archives. Missing-volume fixtures should be recorded as corrupt/incomplete rather than password-required.

## RAR matrix

RAR remains documented in `docs/RAR_FIXTURE_QA.md` and should be verified with `generate_rar_fixture_reports.*`. Do not merge generic report results into RAR compatibility claims.
