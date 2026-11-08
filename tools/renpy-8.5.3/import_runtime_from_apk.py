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
REQUIRED_MAIN_HOOKS = (
    b"RAMEN_GAME_DIR_HOOK_V1",
    b"RAMEN_SAVES_ROOT_HOOK_V1",
    b"RAMEN_LOG_DIR_HOOK_V1",
    b"RENPY_RAMEN_LAUNCH_ABORT",
)
EMBEDDED_HOST_HOOK = b"RENPY_RAMEN_EMBEDDED_HOST"




def safe_member_path(name: str) -> PurePosixPath | None:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        return None
    return path


def validate_compiled_module(
    module_name: str,
    payload: bytes,
    *,
    module_label: str,
) -> None:
    """Verify that a RAPT-compiled module contains the embedded-host hooks."""
    if not payload:
        raise RuntimeError(f"The compiled Ren'Py module is empty: {module_name}")

    missing = []
    if EMBEDDED_HOST_HOOK not in payload:
        missing.append("RENPY_RAMEN_EMBEDDED_HOST")
    # CPython bytecode stores LOAD_METHOD/LOAD_ATTR names as marshal strings.
    # The Activity.finish method must be part of the compiled code object.
    if b"finish" not in payload:
        missing.append("Activity.finish")
    if missing:
        raise RuntimeError(
            f"The compiled {module_label} module is missing required embedded-host "
            "hooks: " + ", ".join(missing)
            + ". Patch the Ren'Py SDK sources before running android_build."
        )


def validate_private_mp3(payload: bytes) -> str:
    """Fail closed unless the private runtime was built from Ramen-patched SDK sources."""
    if payload[:2] != bytes((0x1F, 0x8B)):
        raise RuntimeError("assets/private.mp3 is not gzip-compressed.")

    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
        members = [member for member in archive.getmembers() if member.isfile()]
        file_payloads: dict[str, bytes] = {}
        for member in members:
            if member.name in file_payloads:
                raise RuntimeError(f"Duplicate runtime tar entry: {member.name}")
            stream = archive.extractfile(member)
            if stream is None:
                raise RuntimeError(f"Could not read runtime tar entry: {member.name}")
            file_payloads[member.name] = stream.read()

    main_files = [
        (name, content)
        for name, content in file_payloads.items()
        if PurePosixPath(name).name == "main.py"
    ]
    if len(main_files) != 1:
        raise RuntimeError(
            f"Expected one Ren'Py main.py in private.mp3, found {len(main_files)}."
        )

    missing_main_hooks = [
        hook.decode("ascii")
        for hook in REQUIRED_MAIN_HOOKS
        if hook not in main_files[0][1]
    ]
    if missing_main_hooks:
        raise RuntimeError(
            "The packaged main.py is missing Ramen SDK path/startup hooks: "
            + ", ".join(missing_main_hooks)
            + ". Run apply_ramen_android_paths.py on the SDK's renpy.py before android_build."
        )

    bootstrap_files = [
        (name, content)
        for name, content in file_payloads.items()
        if name.endswith("renpy/bootstrap.pyc")
    ]
    display_core_files = [
        (name, content)
        for name, content in file_payloads.items()
        if name.endswith("renpy/display/core.pyc")
    ]
    if len(bootstrap_files) != 1:
        raise RuntimeError(
            "Expected one RAPT-compiled renpy/bootstrap.pyc in private.mp3, "
            f"found {len(bootstrap_files)}."
        )
    if len(display_core_files) != 1:
        raise RuntimeError(
            "Expected one RAPT-compiled renpy/display/core.pyc in private.mp3, "
            f"found {len(display_core_files)}."
        )

    validate_compiled_module(
        bootstrap_files[0][0],
        bootstrap_files[0][1],
        module_label="renpy/bootstrap.py",
    )
    validate_compiled_module(
        display_core_files[0][0],
        display_core_files[0][1],
        module_label="renpy/display/core.py",
    )

    return hashlib.md5(payload).hexdigest()




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
                private_payload = archive.read(item)
                private_version = validate_private_mp3(private_payload)
                destination.write_bytes(private_payload)
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
    print("The private runtime was validated against Ramen's patched Ren'Py 8.5.3 SDK sources.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
