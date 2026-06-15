#!/usr/bin/env bash
set -euo pipefail

FIXTURE_DIR="${1:-}"
OUT_DIR="${2:-build/reports/archive-fixtures}"
PASSWORD="${3:-}"

if [[ -z "$FIXTURE_DIR" || ! -d "$FIXTURE_DIR" ]]; then
  echo "Usage: $0 /path/to/archive-fixtures [out-dir] [password]" >&2
  exit 2
fi

mkdir -p "$OUT_DIR"
ARGS=("-Dtextview.externalArchiveFixtureDir=$FIXTURE_DIR" "-Dtextview.archiveFixtureReportOutDir=$OUT_DIR")
if [[ -n "$PASSWORD" ]]; then
  ARGS+=("-Dtextview.archiveFixturePassword=$PASSWORD")
fi

./gradlew testDebugUnitTest --tests com.readwide.manager.archive.ExternalArchiveFixtureSmokeTest --tests com.readwide.manager.archive.ArchiveFixtureMatrixReportTest "${ARGS[@]}"

echo "Archive fixture reports written under: $OUT_DIR"
