import unittest

from patch_python_sdl_activity import (
    HANDOFF_MARKER,
    apply_patch,
    git_blob_sha,
)


SYNTHETIC_STOCK = '''package org.renpy.android;

public class PythonSDLActivity {
    public native void nativeSetEnv(String variable, String value);

    public void preparePython() {
        unpackData("private", getFilesDir());

        nativeSetEnv("ANDROID_PRIVATE", getFilesDir().getAbsolutePath());
    }
}
'''


class PythonSdlActivityPatcherTest(unittest.TestCase):
    def test_git_blob_sha_matches_git_known_value(self):
        self.assertEqual(
            git_blob_sha("hello"),
            "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0",
        )

    def test_inserts_launch_extras_before_python_environment_setup(self):
        patched = apply_patch(SYNTHETIC_STOCK, verify_source=False)
        self.assertIn(HANDOFF_MARKER, patched)
        self.assertIn("RENPY_RAMEN_GAME_DIR", patched)
        self.assertIn("RENPY_RAMEN_SAVES_ROOT", patched)
        self.assertIn("RENPY_RAMEN_LOG_DIR", patched)
        self.assertIn("ramenGamesRoot.getPath().startsWith(ramenFilesRoot.getPath()", patched)
        self.assertIn("ramenDataBase.getPath().startsWith(ramenFilesRoot.getPath()", patched)
        self.assertLess(
            patched.index("nativeSetEnv(\\"RENPY_RAMEN_GAME_DIR\\""),
            patched.index('nativeSetEnv("ANDROID_PRIVATE"'),
        )

    def test_patch_is_idempotent(self):
        patched = apply_patch(SYNTHETIC_STOCK, verify_source=False)
        self.assertEqual(apply_patch(patched), patched)

    def test_refuses_unknown_source_when_fingerprint_is_required(self):
        with self.assertRaises(ValueError):
            apply_patch(SYNTHETIC_STOCK)


if __name__ == "__main__":
    unittest.main()
