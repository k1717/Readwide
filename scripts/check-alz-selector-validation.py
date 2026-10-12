#!/usr/bin/env python3
"""Compile the real ALZ decoder and run the selector regressions with only a JDK.

No decoder stubs, Android runtime, network, JUnit substitute, heap cap, or file-handle
cap is used. Only the compile-time NonNull annotation is supplied locally. The
same checks have a JUnit adapter in AlzSelectorBoundaryTest for the project suite.
"""
from pathlib import Path
import argparse
import shutil
import subprocess
import tempfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--decoder", type=Path, help="Optional alternate decoder for before/after checks")
    parser.add_argument("--probe", action="store_true", help="Print malformed-stream acceptance results")
    args = parser.parse_args()
    root = args.root.resolve()
    java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        parser.error("java and javac must be on PATH (JDK 17 or newer)")
    pkg = Path("com/readwide/manager/archive")
    decoder = (args.decoder or root / "app/src/main/java" / pkg / "AlzBzip2InputStream.java").resolve()
    with tempfile.TemporaryDirectory(prefix="readwide-alz-selector-") as folder:
        work = Path(folder)
        annotation = work / "androidx/annotation/NonNull.java"
        annotation.parent.mkdir(parents=True)
        annotation.write_text("package androidx.annotation; public @interface NonNull {}\n", encoding="utf-8")
        copied = work / pkg / "AlzBzip2InputStream.java"
        copied.parent.mkdir(parents=True)
        shutil.copyfile(decoder, copied)
        classes = work / "classes"
        classes.mkdir()
        tests = root / "app/src/test/java" / pkg
        sources = [annotation, copied, tests / "Round4Fixtures.java", tests / "Round6Fixtures.java",
                   tests / "AlzSelectorBoundaryChecks.java"]
        print("Decoder:", decoder, flush=True)
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                        *map(str, sources)], check=True)
        command = [java, "-cp", str(classes), "com.readwide.manager.archive.AlzSelectorBoundaryChecks"]
        if args.probe:
            command.append("--probe")
        raise SystemExit(subprocess.run(command, check=False).returncode)


if __name__ == "__main__":
    main()
