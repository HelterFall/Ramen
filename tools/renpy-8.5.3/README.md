# Ren'Py 8.5.3 Android runtime import

Ramen uses the official Ren'Py 8.5.3 Android runtime. Do not copy native
libraries from Ren'Py `master` or from Ren'Py 8.6+ into this profile.

## Build a runtime donor APK

1. Download the official [Ren'Py 8.5.3 SDK release](https://github.com/renpy/renpy/releases/tag/8.5.3.26051504).
2. Download the matching `renpy-8.5.3-rapt.zip` asset from that same release.
3. Extract the RAPT archive into the extracted SDK directory so the SDK has a
   `rapt/` directory at its root.
4. Open that SDK, install the Android build tools when prompted, and build any
   small Android APK with the matching SDK/RAPT pair. A blank test game is
   sufficient; it is only a source of the engine payload.
5. Use the universal APK (not an AAB or split APK set) as the importer input.

The matching RAPT archive published with that release has SHA-256:

```
8a12be34a2f5238d125ff6dd76a56772fd8e44838af864f81bca82c1059b00e6
```

## Import into Ramen

From the Ramen repository root:

```sh
python tools/renpy-8.5.3/import_runtime_from_apk.py /path/to/renpy-8.5.3-donor.apk
```

Use `--replace` only when replacing an already imported runtime:

```sh
python tools/renpy-8.5.3/import_runtime_from_apk.py /path/to/renpy-8.5.3-donor.apk --replace
```

The importer:
- copies all packaged ABI libraries into `app/src/main/jniLibs`;
- copies `private.mp3` and Ren'Py common assets, while excluding the donor
  game's `x-game` assets;
- patches the version-pinned Android path hooks for Ramen's imported game,
  per-game save, and log directories;
- recalculates the MD5 cache key used by the upstream Android host and updates
  `private_version`, forcing the patched runtime to be extracted after install;
- refuses APKs missing `libSDL2.so`, `librenpython.so`, the private runtime
  tarball, common assets, or expected 8.5.3 path hooks.

The donor APK itself is not a runtime dependency. Only the extracted runtime
payload is added to Ramen.
