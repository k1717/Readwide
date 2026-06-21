#!/usr/bin/env bash
set -euo pipefail
# Deletes stale files that may remain when a new Readwide ZIP is extracted over an older working tree.
# Run from the repository root.
for p in \
  "app/src/main/java/com/readwide/manager/archive/RarJunrarFallback.java" \
  "app/src/main/java/com/readwide/manager/archive/Rar3PpmdEngineFixtureProbe.java"
do
  if [ -e "$p" ]; then
    rm -f "$p"
    printf 'Deleted stale removed source: %s\n' "$p"
  fi
done

# Stale unit-test tree left by the com.textview.reader -> com.readwide.manager
# package rename. A ZIP overlay cannot delete files absent from the new archive,
# so old unit tests under the previous package path linger and reference
# now-removed classes, breaking compileDebugUnitTestJavaWithJavac.
# Do NOT remove app/src/androidTest/java/com/textview: a current instrumented
# test lives there and already declares the com.readwide.manager package.
stale_unit_test_dir="app/src/test/java/com/textview"
if [ -d "$stale_unit_test_dir" ]; then
  rm -rf "$stale_unit_test_dir"
  printf 'Deleted stale removed test tree: %s\n' "$stale_unit_test_dir"
fi
