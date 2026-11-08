#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <external-archive-fixture-dir> [output-dir]" >&2
  echo "Expected fixture dir may contain rar-test-files-master.zip and optional sample-1.rar...sample-5.rar." >&2
  exit 2
fi

FIXTURE_DIR="$1"
OUT_DIR="${2:-build/reports/rar-fixtures}"
if [[ ! -d "$FIXTURE_DIR" ]]; then
  echo "Fixture directory does not exist: $FIXTURE_DIR" >&2
  exit 2
fi
FIXTURE_DIR="$(cd -- "$FIXTURE_DIR" && pwd -P)"
mkdir -p -- "$OUT_DIR"
OUT_DIR="$(cd -- "$OUT_DIR" && pwd -P)"
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"

"$PROJECT_DIR/gradlew" --project-dir "$PROJECT_DIR" testDebugUnitTest \
  --tests 'com.readwide.manager.archive.ExternalArchiveFixtureSmokeTest.rarFixtureMatrixReport_generatesExtractionSmokeMarkdown' \
  -Dtextview.externalArchiveFixtureDir="$FIXTURE_DIR" \
  -Dtextview.rarFixtureReportOutDir="$OUT_DIR"

echo "Fixture test task completed. Report directory: $OUT_DIR"
echo "Check the test results for skipped fixtures; a skipped fixture does not establish coverage."
