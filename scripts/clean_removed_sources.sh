#!/usr/bin/env bash
set -euo pipefail
# Deletes stale files that may remain when a new Readwide ZIP is extracted over an older working tree.
# Run from the repository root.
for p in \
  "app/src/main/java/com/readwide/manager/archive/RarJunrarFallback.java"
do
  if [ -e "$p" ]; then
    rm -f "$p"
    printf 'Deleted stale removed source: %s\n' "$p"
  fi
done
