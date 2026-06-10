#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <external-archive-fixture-dir> [output-dir]" >&2
  echo "Expected fixture dir may contain rar-test-files-master.zip and optional sample-1.rar...sample-5.rar." >&2
  exit 2
fi

FIXTURE_DIR="$1"
OUT_DIR="${2:-build/reports/rar-fixtures}"
mkdir -p "$OUT_DIR"

./gradlew testDebugUnitTest \
  --tests 'com.textview.reader.archive.ExternalArchiveFixtureSmokeTest.rarFixtureMatrixReport_generatesExtractionSmokeMarkdown' \
  -Dtextview.externalArchiveFixtureDir="$FIXTURE_DIR" \
  -Dtextview.rarFixtureReportOutDir="$OUT_DIR"

echo "RAR fixture reports written to: $OUT_DIR"
