#!/usr/bin/env python3
"""Patch the exact Ren'Py 8.5.3 Python sources for Ramen's isolated Android host."""

from __future__ import annotations

import argparse
import hashlib
import shutil
from pathlib import Path

BOOTSTRAP_SHA = "4d3b1252dd889cca6ae66dae97848a758180edfd"
DISPLAY_CORE_SHA = "89c74d1ee44e9e504567a237ea700cb8c190aca9"

BOOTSTRAP_MARKER = "# RAMEN_ANDROID_EMBEDDED_HOST_EXIT_V1"
DISPLAY_CORE_MARKER = "# RAMEN_ANDROID_EMBEDDED_BACKGROUND_EXIT_V1"

BOOTSTRAP_ANCHOR = "            android.activity.finishAndRemoveTask()\n"
BOOTSTRAP_INSERT = """            # RAMEN_ANDROID_EMBEDDED_HOST_EXIT_V1
            if os.environ.get("RENPY_RAMEN_EMBEDDED_HOST") == "1":
                android.activity.finish()
            else:
                android.activity.finishAndRemoveTask()
"""

DISPLAY_CORE_ANCHOR = "                    android.activity.finishAndRemoveTask()\n"
DISPLAY_CORE_INSERT = """                    # RAMEN_ANDROID_EMBEDDED_BACKGROUND_EXIT_V1
                    if os.environ.get("RENPY_RAMEN_EMBEDDED_HOST") == "1":
                        android.activity.finish()
                    else:
                        android.activity.finishAndRemoveTask()
"""


def git_blob_sha(source: str) -> str:
    payload = source.encode("utf-8")
    header = b"blob " + str(len(payload)).encode("ascii") + b"\\0"
    return hashlib.sha1(header + payload).hexdigest()


def apply_one(
    source: str,
    *,
    expected_sha: str,
    marker: str,
    anchor: str,
    insertion: str,
    description: str,
    verify_source: bool = True,
) -> str:
    if marker in source:
        if 'os.environ.get("RENPY_RAMEN_EMBEDDED_HOST") == "1"' not in source:
            raise ValueError(f"Incomplete Ramen embedded-host patch in {description}.")
        return source

    if verify_source:
        actual_sha = git_blob_sha(source)
        if actual_sha != expected_sha:
            raise ValueError(
                f"Unexpected {description} source. Expected Git blob "
                f"{expected_sha}, found {actual_sha}."
            )

    count = source.count(anchor)
    if count != 1:
        raise ValueError(
            f"Expected exactly one Android task-removal anchor in {description}, "
            f"found {count}; refusing to patch an unknown Ren'Py source."
        )

    return source.replace(anchor, insertion + anchor, 1)


def patch_bootstrap(source: str, *, verify_source: bool = True) -> str:
    return apply_one(
        source,
        expected_sha=BOOTSTRAP_SHA,
        marker=BOOTSTRAP_MARKER,
        anchor=BOOTSTRAP_ANCHOR,
        insertion=BOOTSTRAP_INSERT,
        description="renpy/bootstrap.py",
        verify_source=verify_source,
    )


def patch_display_core(source: str, *, verify_source: bool = True) -> str:
    return apply_one(
        source,
        expected_sha=DISPLAY_CORE_SHA,
        marker=DISPLAY_CORE_MARKER,
        anchor=DISPLAY_CORE_ANCHOR,
        insertion=DISPLAY_CORE_INSERT,
        description="renpy/display/core.py",
        verify_source=verify_source,
    )


def write_patched(path: Path, patched: str, *, in_place: bool) -> None:
    if in_place:
        backup = path.with_name(path.name + ".ramen-backup")
        if not backup.exists():
            shutil.copy2(path, backup)
        target = path
    else:
        target = path.with_name(path.stem + ".ramen" + path.suffix)

    target.write_text(patched, encoding="utf-8", newline="\\n")
    print(f"Patched Ren'Py Android host source: {target}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bootstrap_py", type=Path)
    parser.add_argument("display_core_py", type=Path)
    parser.add_argument("--in-place", action="store_true")
    args = parser.parse_args()

    bootstrap = args.bootstrap_py.read_text(encoding="utf-8")
    display_core = args.display_core_py.read_text(encoding="utf-8")

    # Patch both completely before writing either file.
    patched_bootstrap = patch_bootstrap(bootstrap)
    patched_display_core = patch_display_core(display_core)

    if patched_bootstrap != bootstrap:
        write_patched(args.bootstrap_py, patched_bootstrap, in_place=args.in_place)
    else:
        print("Ren'Py bootstrap embedded-host hook is already present.")

    if patched_display_core != display_core:
        write_patched(args.display_core_py, patched_display_core, in_place=args.in_place)
    else:
        print("Ren'Py display/core embedded-host hook is already present.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
