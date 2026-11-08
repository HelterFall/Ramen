import importlib.util
import io
import marshal
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

from apply_ramen_android_paths import apply_patch as patch_sdk_paths
from import_runtime_from_apk import REQUIRED_NATIVE_LIBRARIES, collect_runtime
from patch_renpy_embedded_host import patch_bootstrap, patch_display_core


STOCK_MAIN = b"""import os
import sys

def path_to_gamedir(basedir, name):
    # A list of candidate game directory names.
    candidates = [ name ]
    return basedir


def path_to_saves(gamedir, save_directory=None):
    if renpy.android:
        paths = [
            "default",
        ]
    return gamedir


def path_to_logdir(basedir):
    if renpy.android:
        return os.environ['ANDROID_PUBLIC']
    return basedir


def main():

    renpy_base = path_to_renpy_base()
"""

STOCK_BOOTSTRAP = b"""import os

def bootstrap_exit():
    try:
        if renpy.android:
            from jnius import autoclass

            import android

            android.activity.finishAndRemoveTask()

            # Avoid running Python shutdown.
            System = autoclass("java.lang.System")
            System.exit(0)
"""

STOCK_DISPLAY_CORE = b"""import os

class DisplayCore:
    def mobile_background(self):
        if renpy.config.quit_on_mobile_background:
            if renpy.android:
                try:
                    android.activity.finishAndRemoveTask()
                except Exception:
                    pass

                from jnius import autoclass

                System = autoclass("java.lang.System")
                System.exit(0)
"""


def compile_pyc(source: bytes, filename: str) -> bytes:
    code = compile(source.decode("utf-8"), filename, "exec")
    return importlib.util.MAGIC_NUMBER + bytes(12) + marshal.dumps(code)


def make_private_payload(*, include_display_core: bool = True) -> bytes:
    patched_main = patch_sdk_paths(STOCK_MAIN.decode("utf-8"), verify_source=False).encode("utf-8")
    patched_bootstrap = patch_bootstrap(STOCK_BOOTSTRAP.decode("utf-8"), verify_source=False)
    patched_core = patch_display_core(STOCK_DISPLAY_CORE.decode("utf-8"), verify_source=False)

    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode="w:gz") as output:
        entries = [
            ("main.py", patched_main),
            ("renpy/bootstrap.pyc", compile_pyc(patched_bootstrap.encode("utf-8"), "renpy/bootstrap.py")),
        ]
        if include_display_core:
            entries.append((
                "renpy/display/core.pyc",
                compile_pyc(patched_core.encode("utf-8"), "renpy/display/core.py"),
            ))

        for name, payload in entries:
            member = tarfile.TarInfo(name)
            member.size = len(payload)
            output.addfile(member, io.BytesIO(payload))
    return archive.getvalue()


def make_donor_apk(path: Path, native_entries: dict[str, tuple[str, bytes]], *, include_display_core: bool = True) -> None:
    with zipfile.ZipFile(path, "w") as apk:
        for abi, (library, payload) in native_entries.items():
            apk.writestr(f"lib/{abi}/{library}", payload)
        apk.writestr(
            "assets/private.mp3",
            make_private_payload(include_display_core=include_display_core),
        )
        apk.writestr("assets/x-renpy/x-common/runtime.dat", b"common")
        apk.writestr("assets/x-game/game/script.rpyc", b"donor game must not be copied")


class ImportRuntimeNativeLibraryTest(unittest.TestCase):
    def test_accepts_official_combined_renpython_library_and_patched_bytecode(self):
        self.assertEqual(REQUIRED_NATIVE_LIBRARIES, {"librenpython.so"})

        with tempfile.TemporaryDirectory(prefix="ramen-renpy-import-test-") as temporary:
            root = Path(temporary)
            apk = root / "donor.apk"
            stage = root / "stage"
            stage.mkdir()
            make_donor_apk(
                apk,
                {
                    "arm64-v8a": ("librenpython.so", b"renpy engine with statically linked SDL2"),
                    "armeabi-v7a": ("librenpython.so", b"renpy engine with statically linked SDL2"),
                },
            )

            abis, asset_count, private_version = collect_runtime(apk, stage)

            self.assertEqual(abis, {"arm64-v8a", "armeabi-v7a"})
            self.assertGreater(asset_count, 0)
            self.assertTrue(private_version)
            for abi in abis:
                self.assertTrue((stage / "jniLibs" / abi / "librenpython.so").is_file())
                self.assertFalse((stage / "jniLibs" / abi / "libSDL2.so").exists())

            self.assertTrue((stage / "assets" / "private.mp3").is_file())
            self.assertTrue((stage / "assets" / "x-renpy" / "x-common" / "runtime.dat").is_file())
            self.assertFalse((stage / "assets" / "x-game").exists())

            with tarfile.open(stage / "assets" / "private.mp3", "r:gz") as private_tar:
                sources = {
                    member.name: private_tar.extractfile(member).read()
                    for member in private_tar.getmembers()
                    if member.isfile()
                }

            self.assertIn(b"RENPY_RAMEN_GAME_DIR", sources["main.py"])
            self.assertIn(b"RENPY_RAMEN_SAVES_ROOT", sources["main.py"])
            self.assertIn(b"RENPY_RAMEN_LOG_DIR", sources["main.py"])
            self.assertIn(b"RENPY_RAMEN_LAUNCH_ABORT", sources["main.py"])
            self.assertIn(b"RENPY_RAMEN_EMBEDDED_HOST", sources["renpy/bootstrap.pyc"])
            self.assertIn(b"finish", sources["renpy/bootstrap.pyc"])
            self.assertIn(b"RENPY_RAMEN_EMBEDDED_HOST", sources["renpy/display/core.pyc"])
            self.assertIn(b"finish", sources["renpy/display/core.pyc"])

    def test_rejects_private_tar_without_compiled_display_core_hook(self):
        with tempfile.TemporaryDirectory(prefix="ramen-renpy-incomplete-runtime-") as temporary:
            root = Path(temporary)
            apk = root / "donor.apk"
            stage = root / "stage"
            stage.mkdir()
            make_donor_apk(
                apk,
                {"arm64-v8a": ("librenpython.so", b"engine")},
                include_display_core=False,
            )

            with self.assertRaisesRegex(RuntimeError, "renpy/display/core.pyc"):
                collect_runtime(apk, stage)

    def test_rejects_apk_that_has_only_a_separate_sdl_library(self):
        with tempfile.TemporaryDirectory(prefix="ramen-renpy-missing-engine-test-") as temporary:
            root = Path(temporary)
            apk = root / "donor.apk"
            stage = root / "stage"
            stage.mkdir()
            make_donor_apk(
                apk,
                {"arm64-v8a": ("libSDL2.so", b"SDL only, no Ren'Py engine")},
            )

            with self.assertRaisesRegex(RuntimeError, "No ABI contains librenpython.so"):
                collect_runtime(apk, stage)


if __name__ == "__main__":
    unittest.main()
