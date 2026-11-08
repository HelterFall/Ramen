#!/usr/bin/env python3
"""Apply the Ramen Android path handoff to the exact Ren'Py 8.5.3 renpy.py."""

from __future__ import annotations

import argparse
import hashlib
import shutil
from pathlib import Path

EXPECTED_STOCK_BLOB_SHA = "2b2afe0ebcfcbba25dba246b45b074e839202528"

GAME_MARKER = "# RAMEN_GAME_DIR_HOOK_V1"
SAVES_MARKER = "# RAMEN_SAVES_ROOT_HOOK_V1"
LOG_MARKER = "# RAMEN_LOG_DIR_HOOK_V1"

GAME_ANCHOR = "    # A list of candidate game directory names.\n"
GAME_INSERT = """    # RAMEN_GAME_DIR_HOOK_V1
    if "RENPY_RAMEN_GAME_DIR" in os.environ:
        ramen_gamedir = os.path.realpath(os.environ["RENPY_RAMEN_GAME_DIR"])
        if not os.path.isabs(ramen_gamedir) or not os.path.isdir(ramen_gamedir):
            raise RuntimeError("Ramen supplied an invalid Ren'Py game directory.")
        return ramen_gamedir

"""

SAVES_ANCHOR = "    # Android.\n    if renpy.android:\n        paths = [\n"
SAVES_INSERT = """    # RAMEN_SAVES_ROOT_HOOK_V1
    if renpy.android and "RENPY_RAMEN_SAVES_ROOT" in os.environ:
        ramen_saves_root = os.path.realpath(os.environ["RENPY_RAMEN_SAVES_ROOT"])
        os.makedirs(ramen_saves_root, exist_ok=True)

        if save_directory:
            ramen_save_name = os.fsdecode(save_directory)
            ramen_save_parts = ramen_save_name.replace("\\", "/").split("/")
            if os.path.isabs(ramen_save_name) or any(i == ".." for i in ramen_save_parts):
                raise RuntimeError("Ren'Py save directory escapes Ramen's per-game data folder.")
            ramen_save_dir = os.path.realpath(os.path.join(ramen_saves_root, ramen_save_name))
        else:
            ramen_save_dir = ramen_saves_root

        try:
            ramen_save_contained = os.path.commonpath([ramen_saves_root, ramen_save_dir]) == ramen_saves_root
        except ValueError:
            ramen_save_contained = False

        if not ramen_save_contained:
            raise RuntimeError("Ren'Py save directory escapes Ramen's per-game data folder.")

        os.makedirs(ramen_save_dir, exist_ok=True)
        ramen_save_dir = os.path.realpath(ramen_save_dir)
        if os.path.commonpath([ramen_saves_root, ramen_save_dir]) != ramen_saves_root:
            raise RuntimeError("Ren'Py save directory resolves outside Ramen's per-game data folder.")

        return ramen_save_dir

"""

LOG_ANCHOR = "    if renpy.android:\n        return os.environ['ANDROID_PUBLIC']\n"
LOG_INSERT = """    # RAMEN_LOG_DIR_HOOK_V1
    if renpy.android and "RENPY_RAMEN_LOG_DIR" in os.environ:
        ramen_log_dir = os.path.realpath(os.environ["RENPY_RAMEN_LOG_DIR"])
        os.makedirs(ramen_log_dir, exist_ok=True)
        return ramen_log_dir

"""


def git_blob_sha(source: str) -> str:
    payload = source.encode("utf-8")
    header = b"blob " + str(len(payload)).encode("ascii") + b"\0"
    return hashlib.sha1(header + payload).hexdigest()


def _replace_once(source: str, anchor: str, replacement: str, description: str) -> str:
    count = source.count(anchor)
    if count != 1:
        raise ValueError(
            f"Expected exactly one {description} anchor, found {count}; "
            "the SDK source may not be Ren'Py 8.5.3."
        )
    return source.replace(anchor, replacement + anchor, 1)


def apply_patch(source: str, *, verify_source: bool = True) -> str:
    markers = (GAME_MARKER, SAVES_MARKER, LOG_MARKER)
    present = tuple(marker in source for marker in markers)

    if all(present):
        return source
    if any(present):
        raise ValueError("Partial Ramen path patch detected; refusing to modify the SDK.")

    actual_sha = git_blob_sha(source)
    if verify_source and actual_sha != EXPECTED_STOCK_BLOB_SHA:
        raise ValueError(
            "Unexpected renpy.py source. "
            f"Expected Git blob {EXPECTED_STOCK_BLOB_SHA}, found {actual_sha}."
        )

    patched = _replace_once(source, GAME_ANCHOR, GAME_INSERT, "game directory")
    patched = _replace_once(patched, SAVES_ANCHOR, SAVES_INSERT, "Android saves")
    patched = _replace_once(patched, LOG_ANCHOR, LOG_INSERT, "Android log directory")
    return patched


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("renpy_py", type=Path, help="Path to the SDK's renpy.py")
    parser.add_argument(
        "--in-place",
        action="store_true",
        help="Patch the file after creating a .ramen-backup copy",
    )
    args = parser.parse_args()

    source_path = args.renpy_py
    source = source_path.read_text(encoding="utf-8")
    patched = apply_patch(source)

    if patched == source:
        print("Ramen Android path hooks are already present.")
        return 0

    if args.in_place:
        backup = source_path.with_name(source_path.name + ".ramen-backup")
        if backup.exists():
            raise FileExistsError(f"Refusing to overwrite existing backup: {backup}")
        shutil.copy2(source_path, backup)
        target = source_path
    else:
        target = source_path.with_name(source_path.stem + ".ramen" + source_path.suffix)

    target.write_text(patched, encoding="utf-8", newline="\n")
    print(f"Patched Ren'Py 8.5.3 path hooks: {target}")
    if args.in_place:
        print(f"Original source backup: {backup}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
