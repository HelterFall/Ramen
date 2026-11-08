#!/usr/bin/env python3
"""Patch the upstream Ren'Py SDL Activity with Ramen's launch Intent contract."""

from __future__ import annotations

import argparse
import hashlib
import shutil
from pathlib import Path

EXPECTED_UPSTREAM_BLOB_SHA = "ae9ef69291f56d3aa727129ebba243b42912f4c1"
HANDOFF_MARKER = "// RAMEN_ANDROID_INTENT_HANDOFF_V1"

ANCHOR = '''        unpackData("private", getFilesDir());

        nativeSetEnv("ANDROID_PRIVATE", getFilesDir().getAbsolutePath());
'''

INSERT = """        // RAMEN_ANDROID_INTENT_HANDOFF_V1
        try {
            Intent ramenIntent = getIntent();
            String ramenGameId = ramenIntent == null ? null :
                ramenIntent.getStringExtra("io.github.helterfall.ramen.extra.RENPY_GAME_ID");
            String ramenGameDirectoryValue = ramenIntent == null ? null :
                ramenIntent.getStringExtra("io.github.helterfall.ramen.extra.RENPY_GAME_DIR");
            String ramenSavesRootValue = ramenIntent == null ? null :
                ramenIntent.getStringExtra("io.github.helterfall.ramen.extra.RENPY_SAVES_ROOT");
            String ramenLogDirectoryValue = ramenIntent == null ? null :
                ramenIntent.getStringExtra("io.github.helterfall.ramen.extra.RENPY_LOG_DIR");

            if (ramenGameId == null || !ramenGameId.matches("[A-Za-z0-9-]{1,64}") ||
                    ramenGameDirectoryValue == null || ramenSavesRootValue == null ||
                    ramenLogDirectoryValue == null) {
                throw new IllegalStateException("Missing or invalid Ramen Ren'Py launch parameters.");
            }

            File ramenFilesRoot = getFilesDir().getCanonicalFile();
            File ramenGamesRoot = new File(ramenFilesRoot, "games").getCanonicalFile();
            File ramenImportedRoot = new File(ramenGamesRoot, ramenGameId).getCanonicalFile();
            File ramenGameDirectory = new File(ramenGameDirectoryValue).getCanonicalFile();

            String ramenGamesPrefix = ramenGamesRoot.getPath() + File.separator;
            String ramenImportedPrefix = ramenImportedRoot.getPath() + File.separator;
            if (!ramenGamesRoot.getPath().startsWith(ramenFilesRoot.getPath() + File.separator) ||
                    !ramenImportedRoot.getPath().startsWith(ramenGamesPrefix) ||
                    !ramenImportedRoot.isDirectory() ||
                    !(ramenGameDirectory.equals(ramenImportedRoot) ||
                      ramenGameDirectory.getPath().startsWith(ramenImportedPrefix)) ||
                    !ramenGameDirectory.isDirectory()) {
                throw new IllegalStateException("Ramen Ren'Py game path is outside the imported game.");
            }

            File ramenDataBase = new File(ramenFilesRoot, "renpy-data").getCanonicalFile();
            File ramenExpectedDataRoot = new File(ramenDataBase, ramenGameId).getCanonicalFile();
            File ramenSavesRoot = new File(ramenSavesRootValue).getCanonicalFile();
            File ramenLogDirectory = new File(ramenLogDirectoryValue).getCanonicalFile();

            File ramenExpectedSavesRoot = new File(ramenExpectedDataRoot, "saves").getCanonicalFile();
            File ramenExpectedLogDirectory = new File(ramenExpectedDataRoot, "logs").getCanonicalFile();

            if (!ramenDataBase.getPath().startsWith(ramenFilesRoot.getPath() + File.separator) ||
                    !ramenExpectedDataRoot.getPath().startsWith(ramenDataBase.getPath() + File.separator) ||
                    !ramenSavesRoot.equals(ramenExpectedSavesRoot) ||
                    !ramenLogDirectory.equals(ramenExpectedLogDirectory) ||
                    !ramenSavesRoot.isDirectory() || !ramenLogDirectory.isDirectory()) {
                throw new IllegalStateException("Ramen Ren'Py data paths are outside the per-game data folder.");
            }

            nativeSetEnv("RENPY_RAMEN_GAME_DIR", ramenGameDirectory.getAbsolutePath());
            nativeSetEnv("RENPY_RAMEN_SAVES_ROOT", ramenSavesRoot.getAbsolutePath());
            nativeSetEnv("RENPY_RAMEN_LOG_DIR", ramenLogDirectory.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Could not validate Ramen Ren'Py launch paths.", e);
        }

"""

def git_blob_sha(source: str) -> str:
    payload = source.encode("utf-8")
    header = b"blob " + str(len(payload)).encode("ascii") + b"\0"
    return hashlib.sha1(header + payload).hexdigest()


def apply_patch(source: str, *, verify_source: bool = True) -> str:
    if HANDOFF_MARKER in source:
        return source

    actual_sha = git_blob_sha(source)
    if verify_source and actual_sha != EXPECTED_UPSTREAM_BLOB_SHA:
        raise ValueError(
            "Unexpected PythonSDLActivity.java source. "
            f"Expected Git blob {EXPECTED_UPSTREAM_BLOB_SHA}, found {actual_sha}."
        )

    count = source.count(ANCHOR)
    if count != 1:
        raise ValueError(
            f"Expected exactly one preparePython handoff anchor, found {count}."
        )

    return source.replace(ANCHOR, INSERT + ANCHOR, 1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("activity_java", type=Path, help="Path to PythonSDLActivity.java")
    parser.add_argument(
        "--in-place",
        action="store_true",
        help="Patch in place after creating a .ramen-backup copy",
    )
    args = parser.parse_args()

    source_path = args.activity_java
    source = source_path.read_text(encoding="utf-8")
    patched = apply_patch(source)

    if patched == source:
        print("Ramen Android Intent handoff is already present.")
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
    print(f"Patched PythonSDLActivity.java: {target}")
    if args.in_place:
        print(f"Original source backup: {backup}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
