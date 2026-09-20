#!/usr/bin/env python3
"""Create Readwide's portable, complete GitHub source ZIP."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path, PurePosixPath
import shutil
import stat
import time
import zipfile
import uuid


ROOT = Path(__file__).resolve().parents[1]
LIBARCHIVE_CMAKE = PurePosixPath(
    "third_party/libarchive-android/library/src/main/jni/"
    "external/libarchive/build/cmake"
)
EXCLUDED_DIRECTORIES = {
    ".git",
    ".gradle",
    ".idea",
    ".cxx",
    ".externalNativeBuild",
    ".kotlin",
    "__pycache__",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    ".vscode",
    ".captures",
    "build",
    "captures",
}
EXCLUDED_NAMES = {
    ".DS_Store",
    "Thumbs.db",
    "desktop.ini",
    "GoogleService-Info.plist",
    "google-services.json",
    "keystore.properties",
    "local.properties",
    "search_func.txt",
    "search_funcs.txt",
    "search_ui.txt",
    "secrets.properties",
}
EXCLUDED_SUFFIXES = {
    ".aab",
    ".apk",
    ".apks",
    ".ap_",
    ".bak",
    ".class",
    ".der",
    ".dex",
    ".hprof",
    ".iml",
    ".pyc",
    ".pyo",
    ".jks",
    ".keystore",
    ".log",
    ".orig",
    ".p12",
    ".pem",
    ".pfx",
    ".swp",
    ".tmp",
}


def is_under(path: PurePosixPath, directory: PurePosixPath) -> bool:
    return path == directory or path.parts[: len(directory.parts)] == directory.parts


def should_include(relative: PurePosixPath, output_relative: PurePosixPath | None) -> bool:
    if relative.parts and relative.parts[0] in ("docs", "scripts") and len(relative.parts) > 1:
        if relative.parts[1].lower().startswith(("review-", "archive-review-")):
            return False
    if output_relative is not None and relative == output_relative:
        return False
    if relative.name in EXCLUDED_NAMES or Path(relative.name).suffix.lower() in EXCLUDED_SUFFIXES:
        return False
    if relative.name == ".env" or relative.name.startswith(".env."):
        return False
    lower_name = relative.name.lower()
    if lower_name.endswith(".json") and lower_name.startswith(
        ("readwide_backup_", "textview_backup_")
    ):
        return False
    for index, part in enumerate(relative.parts[:-1]):
        if part not in EXCLUDED_DIRECTORIES:
            continue
        directory = PurePosixPath(*relative.parts[: index + 1])
        if part == "build" and is_under(relative, LIBARCHIVE_CMAKE):
            continue
        return False
    return True


def zip_info(relative: PurePosixPath, source: Path) -> zipfile.ZipInfo:
    modified = time.localtime(source.stat().st_mtime)
    year = min(max(modified.tm_year, 1980), 2107)
    info = zipfile.ZipInfo(
        relative.as_posix(),
        (year, modified.tm_mon, modified.tm_mday,
         modified.tm_hour, modified.tm_min, modified.tm_sec),
    )
    info.create_system = 3
    permissions = 0o755 if relative.as_posix() == "gradlew" or source.suffix.lower() == ".sh" else 0o644
    info.external_attr = (stat.S_IFREG | permissions) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    return info


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path, help="destination .zip path")
    args = parser.parse_args()

    output = args.output.expanduser().resolve()
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite existing ZIP: {output}")
    temporary = output.with_name(output.name + "." + uuid.uuid4().hex + ".new")
    output.parent.mkdir(parents=True, exist_ok=True)

    try:
        output_relative = PurePosixPath(output.relative_to(ROOT).as_posix())
    except ValueError:
        output_relative = None

    files: list[tuple[PurePosixPath, Path]] = []
    for source in ROOT.rglob("*"):
        if not source.is_file():
            continue
        relative = PurePosixPath(source.relative_to(ROOT).as_posix())
        if should_include(relative, output_relative):
            files.append((relative, source))
    files.sort(key=lambda item: item[0].as_posix())

    owns_temporary = False
    try:
        with temporary.open("xb"):
            owns_temporary = True
        with zipfile.ZipFile(
            temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
        ) as archive:
            for relative, source in files:
                with source.open("rb") as input_file, archive.open(
                    zip_info(relative, source), "w"
                ) as output_file:
                    shutil.copyfileobj(input_file, output_file, length=1024 * 1024)

        expected_names = {relative.as_posix() for relative, _ in files}
        with zipfile.ZipFile(temporary, "r") as archive:
            actual_names = set(archive.namelist())
            if actual_names != expected_names:
                raise RuntimeError("ZIP entry list does not match the filtered source tree")
            corrupt = archive.testzip()
            if corrupt is not None:
                raise RuntimeError(f"ZIP CRC validation failed at {corrupt}")
            if not any(is_under(PurePosixPath(name), LIBARCHIVE_CMAKE) for name in actual_names):
                raise RuntimeError("vendored libarchive CMake source directory is missing")
            for info in archive.infolist():
                expected_mode = 0o755 if info.filename == "gradlew" or info.filename.endswith(".sh") else 0o644
                actual_mode = (info.external_attr >> 16) & 0o777
                if info.create_system != 3 or actual_mode != expected_mode:
                    raise RuntimeError(
                        f"non-portable ZIP mode for {info.filename}: {oct(actual_mode)}"
                    )

        # Exclusive creation also refuses a file created during packaging.
        # Do not use replace(), which would silently overwrite an older ZIP.
        owns_output = False
        try:
            with output.open("xb") as published:
                owns_output = True
                with temporary.open("rb") as verified:
                    shutil.copyfileobj(verified, published, length=1024 * 1024)
        except BaseException:
            if owns_output:
                output.unlink()
            raise
    finally:
        if owns_temporary and temporary.exists():
            temporary.unlink()

    digest = hashlib.sha256(output.read_bytes()).hexdigest().upper()
    print(f"ZIP={output}")
    print(f"FILES={len(files)}")
    print(f"BYTES={output.stat().st_size}")
    print(f"SHA256={digest}")


if __name__ == "__main__":
    main()
