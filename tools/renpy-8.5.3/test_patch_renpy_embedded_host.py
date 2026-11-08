import unittest

from patch_renpy_embedded_host import (
    BOOTSTRAP_MARKER,
    DISPLAY_CORE_MARKER,
    patch_bootstrap,
    patch_display_core,
)


STOCK_BOOTSTRAP = '''import os

def bootstrap_exit():
    try:
        if renpy.android:
            from jnius import autoclass  # type: ignore

            import android

            android.activity.finishAndRemoveTask()

            System = autoclass("java.lang.System")
            System.exit(0)
'''


STOCK_DISPLAY_CORE = '''import os

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
'''


class RenPyEmbeddedHostPatcherTest(unittest.TestCase):
    def test_patches_both_android_task_removal_sites(self):
        bootstrap = patch_bootstrap(STOCK_BOOTSTRAP, verify_source=False)
        display_core = patch_display_core(STOCK_DISPLAY_CORE, verify_source=False)

        self.assertIn(BOOTSTRAP_MARKER, bootstrap)
        self.assertIn(DISPLAY_CORE_MARKER, display_core)
        self.assertIn('os.environ.get("RENPY_RAMEN_EMBEDDED_HOST") == "1"', bootstrap)
        self.assertIn('os.environ.get("RENPY_RAMEN_EMBEDDED_HOST") == "1"', display_core)
        self.assertIn("android.activity.finish()", bootstrap)
        self.assertIn("android.activity.finish()", display_core)
        self.assertIn("System.exit(0)", bootstrap)
        self.assertIn("System.exit(0)", display_core)

    def test_patch_is_idempotent(self):
        bootstrap = patch_bootstrap(STOCK_BOOTSTRAP, verify_source=False)
        display_core = patch_display_core(STOCK_DISPLAY_CORE, verify_source=False)

        self.assertEqual(patch_bootstrap(bootstrap), bootstrap)
        self.assertEqual(patch_display_core(display_core), display_core)

    def test_refuses_unknown_source_when_fingerprint_is_required(self):
        with self.assertRaises(ValueError):
            patch_bootstrap(STOCK_BOOTSTRAP)
        with self.assertRaises(ValueError):
            patch_display_core(STOCK_DISPLAY_CORE)

    def test_refuses_partial_embedded_host_patch(self):
        partial = STOCK_BOOTSTRAP.replace(
            "        android.activity.finishAndRemoveTask()",
            f"        {BOOTSTRAP_MARKER}\\n        android.activity.finishAndRemoveTask()",
        )
        with self.assertRaises(ValueError):
            patch_bootstrap(partial, verify_source=False)

    def test_patched_modules_compile(self):
        bootstrap = patch_bootstrap(STOCK_BOOTSTRAP, verify_source=False)
        display_core = patch_display_core(STOCK_DISPLAY_CORE, verify_source=False)

        compile(bootstrap, "renpy/bootstrap.py", "exec")
        compile(display_core, "renpy/display/core.py", "exec")


if __name__ == "__main__":
    unittest.main()
