#!/usr/bin/env python3
"""Import and adapt the engine payload from a universal Ren'Py 8.5.3 APK.

Build the donor APK with the official Ren'Py 8.5.3 SDK and matching RAPT.
The donor game's x-game assets are deliberately excluded: Ramen resolves the
selected game from app-private storage using the path hooks added below.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import io
import re
import shutil
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

REQUIRED_NATIVE_LIBRARIES = {"librenpython.so"}
REQUIRED_COMMON_PREFIX = "assets/x-renpy/x-common/"
RUNTIME_VERSION = "8.5.3"
PATH_PATCH_MARKER = "# RAMEN_ANDROID_RUNTIME_PATHS_V1"

GAME_DIR_ANCHOR = "    # A list of candidate game directory names.\n"
GAME_DIR_INSERT = """    # RAMEN_ANDROID_RUNTIME_PATHS_V1
    ramen_game_directory = os.environ.get("RENPY_RAMEN_GAME_DIR")
    if ramen_game_directory:
        ramen_game_directory = os.path.abspath(ramen_game_directory)
        if not os.path.isdir(ramen_game_directory):
            raise OSError("Ramen Ren'Py game directory does not exist.")
        return ramen_game_directory

"""

SAVES_ANCHOR = "    if renpy.android:\n        paths = [\n"
SAVES_REPLACEMENT = """    if renpy.android:
        ramen_saves_root = os.environ.get("RENPY_RAMEN_SAVES_ROOT")
        if ramen_saves_root:
            return os.path.abspath(ramen_saves_root)

        paths = [
"""

LOG_DIR_ANCHOR = "        return os.environ['ANDROID_PUBLIC']\n"
LOG_DIR_REPLACEMENT = (
    "        return os.environ.get('RENPY_RAMEN_LOG_DIR') or os.environ['ANDROID_PUBLIC']\n"
)


def safe_member_path(name: str) -> PurePosixPath | None:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        return None
    return path


def patch_main_py(source: bytes) -> bytes:
    """Patch game paths and abort early when the Java host rejected a launch."""
    text = source.decode("utf-8")

    if PATH_PATCH_MARKER not in text:
        required_anchors = (
            GAME_DIR_ANCHOR,
            SAVES_ANCHOR,
            LOG_DIR_ANCHOR,
        )
        for anchor in required_anchors:
            if text.count(anchor) != 1:
                raise RuntimeError(
                    "The main.py in the donor APK does not match the expected "
                    "Ren'Py 8.5.3 path functions; refusing to make a partial patch."
                )

        text = text.replace(GAME_DIR_ANCHOR, GAME_DIR_INSERT + GAME_DIR_ANCHOR, 1)
        text = text.replace(SAVES_ANCHOR, SAVES_REPLACEMENT, 1)
        text = text.replace(LOG_DIR_ANCHOR, LOG_DIR_REPLACEMENT, 1)
    else:
        required_hooks = (
            "RENPY_RAMEN_GAME_DIR",
            "RENPY_RAMEN_SAVES_ROOT",
            "RENPY_RAMEN_LOG_DIR",
        )
        if not all(hook in text for hook in required_hooks):
            raise RuntimeError(
                "The private runtime contains an incomplete Ramen path patch; "
                "refusing to accept a partially patched main.py."
            )

    abort_marker = "# RAMEN_ANDROID_LAUNCH_ABORT_V1"
    abort_anchor = "def main():\\n\\n    renpy_base = path_to_renpy_base()\\n"
    abort_insert = """def main():

    # RAMEN_ANDROID_LAUNCH_ABORT_V1
    if os.environ.get("RENPY_RAMEN_LAUNCH_ABORT") == "1":
        raise SystemExit(1)

    renpy_base = path_to_renpy_base()
"""
    if abort_marker not in text:
        if text.count(abort_anchor) != 1:
            raise RuntimeError(
                "The Ren'Py 8.5.3 main.py entry point does not match the "
                "expected main() startup anchor."
            )
        text = text.replace(abort_anchor, abort_insert, 1)
    elif 'os.environ.get("RENPY_RAMEN_LAUNCH_ABORT")' not in text:
        raise RuntimeError("The private runtime contains an incomplete launch-abort patch.")

    return text.encode("utf-8")


def patch_bootstrap_py(source: bytes) -> bytes:
    """Keep Ren'Py's process exit inside the isolated Runtime process/task."""
    text = source.decode("utf-8")
    marker = "# RAMEN_ANDROID_EMBEDDED_HOST_EXIT_V1"
    if marker in text:
        if 'os.environ.get("RENPY_RAMEN_EMBEDDED_HOST") == "1"' not in text:
            raise RuntimeError("The Ren'Py bootstrap contains an incomplete embedded-host patch.")
        return source

    anchor = "            android.activity.finishAndRemoveTask()\\n"
    replacement = """            # RAMEN_ANDROID_EMBEDDED_HOST_EXIT_V1
            if os.environ.get("RENPY_RAMEN_EMBEDDED_HOST") == "1":
                android.activity.finish()
            else:
                android.activity.finishAndRemoveTask()
"""
    if text.count(anchor) != 1:
        raise RuntimeError(
            "The Ren'Py 8.5.3 bootstrap does not contain the expected "
            "Android task-finish anchor."
        )
    return text.replace(anchor, replacement, 1).encode("utf-8")


def patch_display_core_py(source: bytes) -> bytes:
    """Avoid removing Ramen's task when a game opts into mobile-background quit."""
    text = source.decode("utf-8")
    marker = "# RAMEN_ANDROID_EMBEDDED_BACKGROUND_EXIT_V1"
    if marker in text:
        if 'os.environ.get("RENPY_RAMEN_EMBEDDED_HOST") == "1"' not in text:
            raise RuntimeError("Ren'Py display core contains an incomplete embedded-host patch.")
        return source

    anchor = "                    android.activity.finishAndRemoveTask()\\n"
    replacement = """                    # RAMEN_ANDROID_EMBEDDED_BACKGROUND_EXIT_V1
                    if os.environ.get("RENPY_RAMEN_EMBEDDED_HOST") == "1":
                        android.activity.finish()
                    else:
                        android.activity.finishAndRemoveTask()
"""
    if text.count(anchor) != 1:
        raise RuntimeError(
            "The Ren'Py 8.5.3 display core does not contain the expected "
            "Android background-exit anchor."
        )
    return text.replace(anchor, replacement, 1).encode("utf-8")


def patch_private_mp3(payload: bytes) -> tuple[bytes, str]:
    """Patch game paths and Android host-exit behavior inside private.mp3."""
    if payload[:2] != b"\\x1f\\x8b":
        raise RuntimeError("assets/private.mp3 is not gzip-compressed.")

    source_stream = io.BytesIO(payload)
    output_stream = io.BytesIO()
    patched_by_name: dict[str, bytes] = {}
    seen_sources: set[str] = set()

    with tarfile.open(fileobj=source_stream, mode="r:gz") as source_tar:
        members = source_tar.getmembers()

        for member in members:
            if not member.isfile():
                continue

            member_path = PurePosixPath(member.name).as_posix()
            member_stream = source_tar.extractfile(member)
            if member_stream is None:
                continue
            candidate = member_stream.read()

            if PurePosixPath(member_path).name == "main.py" and b"def path_to_gamedir(basedir, name):" in candidate:
                key = "main"
                patcher = patch_main_py
            elif member_path.endswith("renpy/bootstrap.py"):
                key = "bootstrap"
                patcher = patch_bootstrap_py
            elif member_path.endswith("renpy/display/core.py"):
                key = "display_core"
                patcher = patch_display_core_py
            else:
                continue

            if key in seen_sources:
                raise RuntimeError(f"The runtime tarball contains multiple {key} source files.")
            seen_sources.add(key)
            patched_by_name[member.name] = patcher(candidate)

        required_sources = {"main", "bootstrap", "display_core"}
        missing_sources = required_sources - seen_sources
        if missing_sources:
            raise RuntimeError(
                "Could not patch all required Ren'Py 8.5.3 runtime sources: "
                + ", ".join(sorted(missing_sources))
            )

        with tarfile.open(
            fileobj=output_stream,
            mode="w:gz",
            format=tarfile.GNU_FORMAT,
        ) as output_tar:
            for member in members:
                patched = patched_by_name.get(member.name)
                if patched is not None:
                    info = copy.copy(member)
                    info.size = len(patched)
                    output_tar.addfile(info, io.BytesIO(patched))
                elif member.isfile():
                    member_stream = source_tar.extractfile(member)
                    if member_stream is None:
                        raise RuntimeError(f"Could not read runtime file: {member.name}")
                    output_tar.addfile(member, member_stream)
                else:
                    output_tar.addfile(member)

    patched_payload = output_stream.getvalue()
    return patched_payload, hashlib.md5(patched_payload).hexdigest()


def collect_runtime(apk: Path, stage: Path) -> tuple[set[str], int, str]:
    native_root = stage / "jniLibs"
    assets_root = stage / "assets"
    native_root.mkdir(parents=True)
    assets_root.mkdir(parents=True)

    native_by_abi: dict[str, set[str]] = {}
    copied_assets = 0
    private_version: str | None = None
    has_common_assets = False

    with zipfile.ZipFile(apk, "r") as archive:
        for item in archive.infolist():
            name = item.filename
            if item.is_dir():
                continue

            path = safe_member_path(name)
            if path is None:
                raise RuntimeError(f"Unsafe path in APK: {name}")

            # Collect every native library for every ABI in the donor APK.
            if len(path.parts) >= 3 and path.parts[0] == "lib":
                abi = path.parts[1]
                if not name.endswith(".so"):
                    continue
                relative = Path(*path.parts[2:])
                destination = native_root / abi / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(item) as source, destination.open("wb") as target:
                    shutil.copyfileobj(source, target)
                native_by_abi.setdefault(abi, set()).add(destination.name)
                continue

            if not name.startswith("assets/"):
                continue

            relative_parts = path.parts[1:]
            if not relative_parts:
                continue

            # The donor APK is only a source of runtime files, never of a game.
            if relative_parts[0] == "x-game":
                continue

            relative = Path(*relative_parts)
            destination = assets_root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)

            if name == "assets/private.mp3":
                patched_payload, private_version = patch_private_mp3(archive.read(item))
                destination.write_bytes(patched_payload)
            else:
                with archive.open(item) as source, destination.open("wb") as target:
                    shutil.copyfileobj(source, target)

            copied_assets += 1
            if name.startswith(REQUIRED_COMMON_PREFIX):
                has_common_assets = True

    if private_version is None:
        raise RuntimeError(
            "APK has no assets/private.mp3. Build a universal APK with the "
            "official Ren'Py 8.5.3 SDK and matching RAPT."
        )
    if not has_common_assets:
        raise RuntimeError(
            "APK has no assets/x-renpy/x-common files. Use a universal APK "
            "built by the matching official Ren'Py 8.5.3 RAPT."
        )

    usable_abis = {
        abi
        for abi, libraries in native_by_abi.items()
        if REQUIRED_NATIVE_LIBRARIES.issubset(libraries)
    }
    if not usable_abis:
        summary = "; ".join(
            f"{abi}: {', '.join(sorted(libraries))}"
            for abi, libraries in sorted(native_by_abi.items())
        ) or "no native libraries found"
        raise RuntimeError(
            "No ABI contains librenpython.so (the Ren'Py 8.5.3 engine, "
            "which includes SDL2). " + summary
        )

    return usable_abis, copied_assets, private_version


def update_private_version_resource(strings_file: Path, version: str, staged_file: Path) -> None:
    text = strings_file.read_text(encoding="utf-8")
    pattern = re.compile(
        r'(<string\s+name="private_version"\s*>)(.*?)(</string>)',
        re.DOTALL,
    )
    updated, count = pattern.subn(lambda match: match.group(1) + version + match.group(3), text)
    if count != 1:
        raise RuntimeError(
            f"Expected exactly one private_version string in {strings_file}, found {count}."
        )
    staged_file.parent.mkdir(parents=True, exist_ok=True)
    staged_file.write_text(updated, encoding="utf-8", newline="\n")


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(
        description=(
            "Import Ren'Py 8.5.3 native libraries, private runtime and common "
            "assets from an official universal APK."
        )
    )
    parser.add_argument("apk", type=Path, help="Universal APK built with Ren'Py 8.5.3")
    parser.add_argument(
        "--project-root",
        type=Path,
        default=repo_root,
        help="Ramen repository root (defaults to this checkout)",
    )
    parser.add_argument(
        "--replace",
        action="store_true",
        help="Replace an already imported runtime payload",
    )
    args = parser.parse_args()

    apk = args.apk.expanduser().resolve()
    project_root = args.project_root.expanduser().resolve()
    if not apk.is_file():
        parser.error(f"APK does not exist: {apk}")

    app_main = project_root / "app" / "src" / "main"
    jni_destination = app_main / "jniLibs"
    assets_destination = app_main / "assets"
    strings_destination = app_main / "res" / "values" / "strings.xml"
    if not app_main.is_dir():
        parser.error(f"Not a Ramen Android project: {app_main}")
    if not strings_destination.is_file():
        parser.error(f"Missing Android string resources: {strings_destination}")

    existing = [
        path
        for path in (
            jni_destination,
            assets_destination / "private.mp3",
            assets_destination / "x-renpy",
        )
        if path.exists()
    ]
    if existing and not args.replace:
        parser.error(
            "A Ren'Py payload already exists. Re-run with --replace to replace "
            "it: " + ", ".join(str(path) for path in existing)
        )

    with tempfile.TemporaryDirectory(prefix="ramen-renpy-853-") as temporary:
        stage = Path(temporary)
        try:
            abis, asset_count, private_version = collect_runtime(apk, stage)
            staged_strings = stage / "res" / "values" / "strings.xml"
            update_private_version_resource(strings_destination, private_version, staged_strings)
        except (OSError, zipfile.BadZipFile, tarfile.TarError, RuntimeError) as error:
            print(f"Runtime import failed: {error}", file=sys.stderr)
            return 1

        if args.replace:
            if jni_destination.exists():
                shutil.rmtree(jni_destination)
            for path in (
                assets_destination / "private.mp3",
                assets_destination / "x-renpy",
            ):
                if path.is_dir():
                    shutil.rmtree(path)
                elif path.exists():
                    path.unlink()

        shutil.copytree(stage / "jniLibs", jni_destination, dirs_exist_ok=True)
        shutil.copytree(stage / "assets", assets_destination, dirs_exist_ok=True)
        shutil.copy2(staged_strings, strings_destination)

    print(f"Imported and adapted Ren'Py {RUNTIME_VERSION} runtime from: {apk}")
    print("ABIs: " + ", ".join(sorted(abis)))
    print(f"Runtime APK assets copied (excluding x-game): {asset_count}")
    print(f"private_version cache key: {private_version}")
    print("The embedded main.py now uses Ramen's per-game game, saves, and log directories.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
