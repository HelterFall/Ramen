import io
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

from import_runtime_from_apk import REQUIRED_NATIVE_LIBRARIES, collect_runtime


STOCK_MAIN = b"""def path_to_gamedir(basedir, name):
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
"""


def make_private_payload() -> bytes:
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode="w:gz") as output:
        member = tarfile.TarInfo("renpy/main.py")
        member.size = len(STOCK_MAIN)
        output.addfile(member, io.BytesIO(STOCK_MAIN))
    return archive.getvalue()


def make_donor_apk(path: Path, native_entries: dict[str, tuple[str, bytes]]) -> None:
    with zipfile.ZipFile(path, "w") as apk:
        for abi, (library, payload) in native_entries.items():
            apk.writestr(f"lib/{abi}/{library}", payload)
        apk.writestr("assets/private.mp3", make_private_payload())
        apk.writestr("assets/x-renpy/x-common/runtime.dat", b"common")
        apk.writestr("assets/x-game/game/script.rpyc", b"donor game must not be copied")


class ImportRuntimeNativeLibraryTest(unittest.TestCase):
    def test_official_853_payload_requires_only_combined_renpython_library(self):
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
