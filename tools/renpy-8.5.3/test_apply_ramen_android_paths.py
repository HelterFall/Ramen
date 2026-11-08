import unittest

from apply_ramen_android_paths import (
    GAME_MARKER,
    LOG_MARKER,
    SAVES_MARKER,
    ABORT_MARKER,
    apply_patch,
    git_blob_sha,
)


SYNTHETIC_STOCK = '''def path_to_gamedir(basedir, name):
    """
    Returns the absolute path.
    """
    # A list of candidate game directory names.
    candidates = [ name ]
    return basedir


def path_to_saves(gamedir, save_directory=None):
    # Android.
    if renpy.android:
        paths = [
            "old",
        ]
    return gamedir


def path_to_logdir(basedir):
    if renpy.android:
        return os.environ['ANDROID_PUBLIC']
    return basedir
''

def main():

    renpy_base = path_to_renpy_base()
'''


class RenPyPathPatcherTest(unittest.TestCase):
    def test_git_blob_sha_matches_git_known_value(self):
        self.assertEqual(
            git_blob_sha("hello"),
            "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0",
        )

    def test_inserts_all_three_hooks(self):
        patched = apply_patch(SYNTHETIC_STOCK, verify_source=False)
        self.assertIn(GAME_MARKER, patched)
        self.assertIn(SAVES_MARKER, patched)
        self.assertIn(LOG_MARKER, patched)
        self.assertIn(ABORT_MARKER, patched)
        self.assertIn("RENPY_RAMEN_GAME_DIR", patched)
        self.assertIn("RENPY_RAMEN_SAVES_ROOT", patched)
        self.assertIn("RENPY_RAMEN_LOG_DIR", patched)
        self.assertIn("RENPY_RAMEN_LAUNCH_ABORT", patched)

    def test_patch_is_idempotent(self):
        patched = apply_patch(SYNTHETIC_STOCK, verify_source=False)
        self.assertEqual(apply_patch(patched), patched)

    def test_refuses_partial_patch(self):
        partial = SYNTHETIC_STOCK.replace(
            "    # A list of candidate game directory names.\n",
            f"    {GAME_MARKER}\n    # A list of candidate game directory names.\n",
        )
        with self.assertRaises(ValueError):
            apply_patch(partial, verify_source=False)

    def test_refuses_unknown_source_when_fingerprint_is_required(self):
        with self.assertRaises(ValueError):
            apply_patch(SYNTHETIC_STOCK)


if __name__ == "__main__":
    unittest.main()
