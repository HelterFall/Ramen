#!/usr/bin/env python3
"""Import the engine payload from a universal APK built with Ren'Py 8.5.3.

The APK must be produced by the official Ren'Py 8.5.3 SDK + matching RAPT.
Game-specific assets (assets/x-game/) are deliberately excluded: Ramen loads
the user's imported game from app-private storage instead.
"""

from __future__ import annotations

import argparse
import shutil
import sys
import zipfile
from pathlib import Path, PurePosixPath

REQUIRED_NATIVE_LIBRARIES = {"libSDL2.so", "librenpython.so"}
REQUIRED_COMMON_PREFIX = "assets/x-renpy/x-common/"
RUNTIME_VERSION = "8.5.3"


def safe_member_path(name: str) -> PurePosixPath | None:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        return None
    return path


def collect_runtime(apk: Path, stage: Path) -> tuple[set[str], int]:
    native_root = stage / "jniLibs"
    assets_root = stage / "assets"
    native_root.mkdir(parents=True)
    assets_root.mkdir(parents=True)

    native_by_abi: dict[str, set[str]] = {}
    copied_assets = 0
    has_private_runtime = False
    has_common_assets = False

    with zipfile.ZipFile(apk, "r") as archive:
        members = archive.infolist()

        private_info = next(
            (item for item in members if item.filename == "assets/private.mp3"),
            None,
        )
        if private_info is None:
            raise RuntimeError(
                "APK has no assets/private.mp3. Build a universal Android APK "
                "with Ren'Py 8.5.3 and its matching RAPT."
            )
        with archive.open(private_info) as stream:
            if stream.read(2) != b"\x1f\x8b":
                raise RuntimeError("assets/private.mp3 is not a gzip-compressed tar payload.")
        has_private_runtime = True

        for item in members:
            name = item.filename
            if item.is_dir():
                continue

            path = safe_member_path(name)
            if path is None:
                raise RuntimeError(f"Unsafe path in APK: {name}")

            # Collect every packaged native library for every ABI.
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

            # The APK is just a runtime donor. Do not import its sample game.
            if relative_parts[0] == "x-game":
                continue

            relative = Path(*relative_parts)
            destination = assets_root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(item) as source, destination.open("wb") as target:
                shutil.copyfileobj(source, target)
            copied_assets += 1

            if name.startswith(REQUIRED_COMMON_PREFIX):
                has_common_assets = True

    if not has_private_runtime:
        raise RuntimeError("Missing private runtime payload.")
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
            "No ABI contains both libSDL2.so and librenpython.so. " + summary
        )

    return usable_abis, copied_assets


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(
        description=(
            "Extract Ren'Py 8.5.3 engine libraries and common assets from an "
            "official universal APK into the Ramen Android app."
        )
    )
    parser.add_argument("apk", type=Path, help="Universal APK built with Ren'Py 8.5.3")
    parser.add_argument(
        "--project-root",
        type=Path,
        default=repo_root,
        help="Ramen repository root (defaults to the current checkout)",
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
    if not app_main.is_dir():
        parser.error(f"Not a Ramen Android project: {app_main}")

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

    import tempfile

    with tempfile.TemporaryDirectory(prefix="ramen-renpy-853-") as temporary:
        stage = Path(temporary)
        try:
            abis, asset_count = collect_runtime(apk, stage)
        except (OSError, zipfile.BadZipFile, RuntimeError) as error:
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
        shutil.copytree(
            stage / "assets",
            assets_destination,
            dirs_exist_ok=True,
        )

    print(f"Imported Ren'Py {RUNTIME_VERSION} runtime from: {apk}")
    print("ABIs: " + ", ".join(sorted(abis)))
    print(f"Runtime APK assets copied (excluding x-game): {asset_count}")
    print("Ramen should now detect private.mp3, libSDL2.so, and librenpython.so.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
