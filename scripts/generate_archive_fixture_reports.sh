#!/usr/bin/env bash
set -euo pipefail

FIXTURE_DIR="${1:-}"
OUT_DIR="${2:-build/reports/archive-fixtures}"
PASSWORD="${3:-}"

if [[ -z "$FIXTURE_DIR" || ! -d "$FIXTURE_DIR" ]]; then
  echo "Usage: $0 /path/to/archive-fixtures [out-dir] [password]" >&2
  exit 2
fi

FIXTURE_DIR="$(cd -- "$FIXTURE_DIR" && pwd -P)"
mkdir -p -- "$OUT_DIR"
OUT_DIR="$(cd -- "$OUT_DIR" && pwd -P)"
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
ARGS=("-Dtextview.externalArchiveFixtureDir=$FIXTURE_DIR" "-Dtextview.archiveFixtureReportOutDir=$OUT_DIR")
if [[ -n "$PASSWORD" ]]; then
  ARGS+=("-Dtextview.archiveFixturePassword=$PASSWORD")
fi

"$PROJECT_DIR/gradlew" --project-dir "$PROJECT_DIR" testDebugUnitTest --tests com.readwide.manager.archive.ExternalArchiveFixtureSmokeTest --tests com.readwide.manager.archive.ArchiveFixtureMatrixReportTest "${ARGS[@]}"

echo "Fixture test task completed. Report directory: $OUT_DIR"
echo "Check the test results for skipped fixtures; a skipped fixture does not establish coverage."
