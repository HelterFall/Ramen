# Ren'Py 8.5.3 Android runtime

Ramen embeds the Ren'Py 8.5.3 Android engine. The matching public SDK and
RAPT packages are downloaded and verified automatically by the Android CI
workflow. No user-supplied APK or blank game is required.

## Build

The workflow runs:

```sh
bash tools/renpy-8.5.3/build_runtime.sh
```

The script downloads the version-pinned Ren'Py 8.5.3 SDK and RAPT archives,
checks their SHA-256 hashes, configures the Android build tools, and invokes
Ren'Py's own Android packaging pipeline on the SDK's included sample project.
It extracts the resulting engine payload into Ramen using
`import_runtime_from_apk.py`. The temporary sample APK is a build-time
intermediate only: it is not installed, committed, or shipped inside Ramen.
Only the Ren'Py native libraries, private runtime archive, and common engine
assets are included in the Ramen APK.

The pinned RAPT archive SHA-256 is:

```
8a12be34a2f5238d125ff6dd76a56772fd8e44838af864f81bca82c1059b00e6
```

The pinned SDK ZIP SHA-256 is:

```
ff57648f9c04f27e381c48af6d8e3ee3cdec296bed4d3831f47f09b0a71b505e
```

## Local runtime import

If a universal Ren'Py 8.5.3 APK already exists, the importer can still be
used directly:

```sh
python tools/renpy-8.5.3/import_runtime_from_apk.py /path/to/runtime-source.apk --replace
```

The importer excludes the source game's `x-game` assets and patches the
Android game, save, and log paths for Ramen's per-game private directories.
