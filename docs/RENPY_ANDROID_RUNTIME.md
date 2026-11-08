# Ren'Py Android Runtime Research for Ramen

**Updated:** 2026-11-08  
**Status:** the Ren'Py 8.5.3 engine payload is now built and imported by Android CI, and the app-side host bridge is integrated. Actual execution of an imported title on an Android device is not yet validated, so titles must remain non-playable until launch and lifecycle tests pass.

## Product target

Ramen should remain one Android application. A user imports a normal Ren'Py game folder, gets a library card, and launches it through an integrated Ren'Py Runtime. The user must not need to rebuild each game into its own APK or install a separate plugin APK.

This is an Android host for the original Ren'Py engine, not a CPU emulator and not a reimplementation of the Ren'Py language.

## Current Ramen integration point

Ramen already provides:
- a Kotlin / Jetpack Compose Android app;
- SAF folder selection and import into app-private storage at files/games/<game-id>;
- a bounded, path-safe streaming folder copier;
- a GameRecord with engine ID, evidence and compatibility status;
- a detector that keeps recognition separate from playback support;
- a library UI and separate Activity-based runtime adapters.

The import pipeline is directly useful for Ren'Py: the engine expects filesystem paths and seekable files, while SAF itself supplies content URIs. Ramen already imports the selected tree into app-private storage, so a first Ren'Py prototype can pass a normal private filesystem path to the engine without implementing a SAF-backed Python file system.

The Ren'Py detector must never mark a game as playable until a real runtime has launched it and passed tests. The expected interim state is DETECTED_UNSUPPORTED.

## Official engine and Android host

Primary upstream repositories:
- Ren'Py engine: https://github.com/renpy/renpy
- Ren'Py build system: https://github.com/renpy/renpy-build
- Android documentation: https://www.renpy.org/doc/html/android.html
- Existing research notebook: https://github.com/HelterFall/Renpy

### Stable baseline

The research selected Ren'Py **8.5.3**, release tag **8.5.3.26051504** (source commit 39895c1), as the first stable baseline. The official release publishes an Android package named renpy-8.5.3-rapt.zip; the release page reports 65.4 MB and SHA-256 8a12be34a2f5238d125ff6dd76a56772fd8e44838af864f81bca82c1059b00e6.

Use the matching 8.5.3 SDK and RAPT archive as the first control build. Do not mix Python, Ren'Py modules, renpython, SDL bindings or other native libraries from a different source/build revision.

Ren'Py 8.6 prerelease is a separate experimental line with SDL3. Ren'Py 7.x belongs to a separate legacy family (Python 2.7). Neither should block the first 8.5.3 proof of concept.

Release reference: https://github.com/renpy/renpy/releases/tag/8.5.3.26051504

### Verified source hooks in 8.5.3

These files were fetched from the exact 8.5.3 release tag:
- [renpy.py](https://github.com/renpy/renpy/blob/8.5.3.26051504/renpy.py), blob 2b2afe0ebcfcbba25dba246b45b074e839202528;
- [renpy/bootstrap.py](https://github.com/renpy/renpy/blob/8.5.3.26051504/renpy/bootstrap.py), blob 4d3b1252dd889cca6ae66dae97848a758180edfd;
- [renpy/loader.py](https://github.com/renpy/renpy/blob/8.5.3.26051504/renpy/loader.py), blob 3bbdb7d4027950f443fe284b3cebadaec7043225;
- [renpy/script.py](https://github.com/renpy/renpy/blob/8.5.3.26051504/renpy/script.py), blob 0133ccfcd52fd9e9dd1dbe7f4bb1f16f9a0c48ae.

Findings:
1. renpy.py exposes the distributor hook path_to_gamedir(basedir, name). The bootstrap calls it to obtain the game's script/assets directory.
2. renpy/bootstrap.py subsequently assigns config.basedir, config.gamedir and config.logdir. This is the likely injection point for a selected/imported game path.
3. renpy/loader.py has a filesystem scanner which indexes directories in config.searchpath, in addition to its Android APK-asset and RPA archive loaders.
4. The Android predefined_searchpath() adds ANDROID_PUBLIC/game when present. In this release it reads RENPY_SEARCHPATH only in the non-Android branch; setting that variable is not a ready-made Android folder-launch solution.
5. The Android path_to_saves() / path_to_logdir() logic uses app-wide environment paths. Ramen needs separate save, persistent and log directories for each game ID.

### Android Activity contract

The official 8.5.3 Android implementation is based on SDL2 and a Java PythonSDLActivity host which loads only `renpython`, exposes the Activity through a static field, and bridges to Python with Pyjnius. In this RAPT build, SDL2 is statically linked into `librenpython.so`; the APK contains no separate `libSDL2.so`. The Android host also sets:
- ANDROID_PRIVATE to the app's internal files directory;
- ANDROID_PUBLIC to the app-specific external files directory;
- ANDROID_OLD_PUBLIC to a legacy app-specific external path;
- ANDROID_APK to the installed APK path.

ANDROID_PUBLIC is **not** the arbitrary folder selected by the user. Do not put a SAF URI there or treat it as a user-visible storage path.

The current renpy-build master tree is useful for understanding the modern host, but it is not a substitute for the matching 8.5.3 RAPT archive. First get the official 8.5.3 baseline working, then patch/fork its compatible Android host.

## Proposed Ramen runtime shape

~~~text
Ramen app
|
+-- Compose library + GameRecord
+-- SAF importer -> files/games/<game-id>
+-- Ren'Py detector / compatibility evidence
+-- Runtime manager
|   +-- profile manifest
|   +-- installed/runtime-available state
|   +-- one active Ren'Py session
+-- Ren'Py SDL Activity (adapted official host)
|   +-- Python runtime + Ren'Py packages
|   +-- renpython / native dependencies
|   +-- SDL + Pyjnius bridge
|   +-- game path / save path launch contract
+-- Per-game data
    +-- saves/<game-id>
    +-- persistent/<game-id>
    +-- logs/<game-id>
~~~

For the first proof of concept, run one game and one Runtime profile at a time. Do not try to keep several Python interpreters or different Ren'Py/Python/SDL generations loaded simultaneously. Whether the engine Activity needs a separate Android process should be established with the native prototype, not assumed.

### Game path handoff

1. Ramen's existing SAF importer copies the selected tree into files/games/<game-id>.
2. A launch coordinator passes that absolute private path to the Ren'Py Activity (for example, through a controlled Intent extra).
3. A distributor-specific path_to_gamedir() implementation returns the imported game directory.
4. Ren'Py's own loader handles ordinary files and RPA archives; Ramen should not reimplement the script parser or unpack RPA as a prerequisite.
5. The Runtime host directs saves, persistent data and logs into per-game app-private directories.

The path hook and file scanner make this plausible, but an Android APK test is still required. Verify how the release bootstrap sets renpy_base, basedir, gamedir, commondir and searchpath; verify that the external game directory is scanned and the Runtime's common files remain available. An absolute game path combined with os.path.join(config.basedir, entry) needs an executable proof, not just static reading.

## Recognition and file formats

Ren'Py game payloads may contain:
- .rpy — source scripts;
- .rpyc — compiled scripts; retail distributions may have no .rpy;
- .rpym / .rpymc — script modules;
- .rpa — resource archives;
- Python modules and engine-specific resources.

For compiled scripts, Ren'Py 8.5.3 uses the RENPY RPC2 RPYC header for the current format; its legacy load path accepts zlib-compressed RPYC data. The detector can verify these short signatures without deserializing or executing the file.

RPA 2.0 and 3.0 begin with the seven-byte magic prefixes RPA-2.0 / RPA-3.0. Ramen currently recognizes only this prefix; it does not validate the full archive structure. RPA must remain read-only and bounded. Do not unpickle an RPA index in the detector. Recognition of an RPA magic prefix does not prove that the whole archive is valid or that its game can run.

The scanner should understand both:
- a base folder containing game/ and possibly renpy/;
- the game/ directory selected directly by the user.

## Runtime version strategy

- Start with 8.5.3 as one coherent profile.
- Test games that declare older script versions on this Runtime before adding more profiles.
- Create a new profile only when tests identify an actual Python ABI, native library, SDL, serialization or behavior incompatibility.
- Keep 8.6 / SDL3 separate until it is stable and a matching official Android host is available.
- Treat Ren'Py 7.x / Python 2.7 as a later, distinct legacy profile.

config.script_version controls script behavior compatibility. It is not the exact engine build number. config.version is set by the game author and is not a reliable engine detector. Do not select a Runtime solely from either field.

## Security, licensing and distribution

A Ren'Py game may contain Python code and native extensions. Import and inspection must not execute game code. When launched, game code runs inside the app's process/UID unless an explicit stronger isolation design is implemented; separate save folders are data separation, not a security sandbox.

Before shipping, audit licenses for the exact Ren'Py release, Python, SDL, FFmpeg and every included native dependency. Preserve notices and review any LGPL obligations. Do not copy or redistribute JoiPlay plugin binaries; build from the official Ren'Py sources/SDK and comply with their licenses.

For a Google Play release, do not design around arbitrary external downloads of executable .so, DEX or JAR files. Investigate Android App Bundle and Play Feature Delivery for on-demand modules that are part of the same app. Play Asset Delivery is intended for assets, not executable code. Ramen's existing scope already excludes arbitrary third-party runtime plugins downloaded at execution time.

## Proof-of-concept checklist

- [ ] Build and install an official Ren'Py 8.5.3 Android sample using its matching SDK and RAPT.
- [ ] Record the device, Android version, ABI, GPU and official build logs.
- [ ] Adapt the matching PythonSDLActivity while preserving the SDL/Python bridge.
- [ ] Launch a synthetic minimal game from Ramen's imported files/games/<id> directory, not from APK game assets.
- [ ] Verify dialog, label/jump, menu choice, image, font and audio.
- [ ] Verify an RPA archive and a compiled-only .rpyc game.
- [ ] Verify save/load and persistent data after process death, with two games isolated.
- [ ] Verify Back, pause/resume, rotation and returning to Ramen's library.
- [ ] Record failures and compatibility evidence; keep status DETECTED_UNSUPPORTED until these tests pass.

## External research repository

The original investigation log, Android loader notes, compatibility matrix and license checklist remain in [HelterFall/Renpy](https://github.com/HelterFall/Renpy). This Ramen document is the product-integration summary; it does not replace the full source research.


## Ramen host bridge added

The integration branch now contains a first host-side contract, separate from the Ren'Py engine payload:

- RenPyGamePaths validates the imported record, resolves either a base folder containing game/ or a directly imported game/ directory, and creates files/renpy-data/<game-id>/saves and logs.
- RenPyRuntimeAvailabilityProbe refuses to report ready unless the adapted org.renpy.android.PythonSDLActivity is declared with host protocol metadata v2, `librenpython.so` (which includes SDL2), `private.mp3`, common Ren'Py assets, and a `renpy-runtime.json` descriptor for Ren'Py 8.5.3 / protocol 2 are present.
- RenPyRuntimeLauncher passes these Intent extras to that Activity: io.github.helterfall.ramen.extra.RENPY_GAME_ID, io.github.helterfall.ramen.extra.RENPY_GAME_DIR, io.github.helterfall.ramen.extra.RENPY_SAVES_ROOT and io.github.helterfall.ramen.extra.RENPY_LOG_DIR.
- The library card stays disabled while any part of the actual Runtime package is missing. No placeholder Activity is registered. The native-library contract is one `librenpython.so` per ABI; SDL2 is not a second shared object in the official 8.5.3 RAPT package.

### Temporary shutdown of APK packaging and playback

Until the Ren'Py runtime importer and device launch flow are fully verified, APK packaging is deliberately disabled. Android CI now runs Python patcher tests, Gradle unit tests and lint only; it no longer builds the Ren'Py donor APK, assembles the Ramen APK, or uploads an APK artifact. The Ren'Py availability probe also keeps the Play path disabled. No app/device launch is part of this phase.

Re-enable packaging only after the runtime import checks, Android compilation/tests/lint, and a real emulator/device smoke test all pass. The smoke test must confirm that a minimal imported title renders, Back/quit returns to the Ramen library, the main process remains alive, and saves/logs stay under the selected game's data directory.

### Embedded process boundary and failed-start handling

The Ramen host uses Android protocol v2. org.renpy.android.PythonSDLActivity runs in the dedicated application process :renpy, while the Ramen library remains in the default process. This is intentional: Ren'Py 8.5.3 calls System.exit(0) during Android shutdown to avoid unsafe Python interpreter finalization (Ren'Py issue #5280). Keeping that behavior inside the isolated Runtime process protects the Ramen process.

The runtime build preparation patches the exact 8.5.3 SDK sources before RAPT compiles the private archive: apply_ramen_android_paths.py patches SDK renpy.py (game/save/log paths plus the early launch-abort guard), and patch_renpy_embedded_host.py patches renpy/bootstrap.py and renpy/display/core.py. When RENPY_RAMEN_EMBEDDED_HOST=1, task-removal calls become Activity.finish(); the existing System.exit(0) remains process-local. The importer does not rewrite bytecode after packaging: it validates that private.mp3 contains the patched main.py and the compiled renpy/bootstrap.pyc and renpy/display/core.pyc hooks.

The Java host sets ANDROID_PRIVATE before any validation because the official native entry point changes directory to it before starting Python. Runtime extraction now returns a boolean, and preparePython() catches preparation exceptions, sets the launch-abort flag, reports the error and closes only the Ren'Py Activity. A successful unpack must also leave main.py, renpy/bootstrap.py and renpy/display/core.py on disk.

The runtime availability probe checks the protocol descriptor, Activity metadata, and the isolated process name. This is still a source-level protection and requires Android device/emulator testing to verify process/task restoration and real game startup.

### Required matching RAPT host changes

The official 8.5.3 PythonSDLActivity.preparePython() must read the four Intent extras, validate their canonical paths, set ANDROID_PRIVATE before any operation that can fail, and call its existing nativeSetEnv() before Python bootstrap:

| Environment variable | Source |
|---|---|
| RENPY_RAMEN_GAME_DIR | absolute RENPY_GAME_DIR Intent extra |
| RENPY_RAMEN_SAVES_ROOT | absolute RENPY_SAVES_ROOT Intent extra |
| RENPY_RAMEN_LOG_DIR | absolute RENPY_LOG_DIR Intent extra |

The activity declaration must include android:process=\":renpy\" and metadata io.github.helterfall.ramen.RENPY_HOST_PROTOCOL = 2. The embedded host must set RENPY_RAMEN_EMBEDDED_HOST=1 before Python starts. The APK must package renpy-runtime.json with at least:

~~~json
{"renpyVersion":"8.5.3","hostProtocol":2}
~~~

tools/renpy-8.5.3/apply_ramen_android_paths.py patches only the exact stock 8.5.3 renpy.py Git blob 2b2afe0ebcfcbba25dba246b45b074e839202528. It adds distributor hooks for the imported game path, per-game saves (also used by config.savedir for persistent data), and per-game logs. It refuses a different source revision and makes a backup for in-place use. Run its Python unit tests with:

~~~sh
python3 -m unittest discover -s tools/renpy-8.5.3 -p 'test_*.py'
~~~

**Runtime payload status (2026-11-08):** `tools/renpy-8.5.3/build_runtime.sh` downloads and checksum-verifies the matching official 8.5.3 SDK/RAPT packages, builds a temporary sample APK with the official Android packaging pipeline, and imports only the engine payload into Ramen. The temporary APK is a build-time donor only; it is not installed or shipped. The resulting universal payload provides `librenpython.so` for arm64-v8a, armeabi-v7a and x86_64, plus `private.mp3` and common engine assets. The official host source and ELF exports confirm SDL2 is included in `librenpython.so`, so the adapted host loads only `renpython`.


### SDK source patching and private-runtime validation

Before RAPT runs, build_runtime.sh applies both source patchers to the version-pinned SDK tree. The path patcher fingerprints renpy.py; the embedded-host patcher fingerprints renpy/bootstrap.py and renpy/display/core.py. The stock source SHA checks make the build fail closed if the SDK archive changes unexpectedly.

RAPT compiles those Ren'Py modules and moves their bytecode into private.mp3. Therefore import_runtime_from_apk.py validates the packaged main.py hooks and the bytecode strings in renpy/bootstrap.pyc and renpy/display/core.pyc; it deliberately does not search for or rewrite .py source files that RAPT has already compiled away.

APK packaging and Ren'Py playback are currently disabled while this work is verified. Android CI runs Python patcher tests, Gradle unit tests, and lint only. The runtime build script is not invoked by CI in this phase.

### RAPT Java host handoff patch

The upstream renpy-build PythonSDLActivity source currently tracked at blob ae9ef69291f56d3aa727129ebba243b42912f4c1 (last changed in the upstream history in November 2024) exposes nativeSetEnv() and calls preparePython() before Python bootstrap. A second fingerprint-guarded patcher now inserts Ramen Intent validation and the three RENPY_RAMEN_* native environment values immediately after private runtime unpacking and before the standard ANDROID_* environment is set.

Apply both source patchers to the matching SDK/host checkout:

~~~sh
python3 tools/renpy-8.5.3/apply_ramen_android_paths.py /path/to/sdk/renpy.py --in-place
python3 tools/renpy-8.5.3/patch_python_sdl_activity.py /path/to/rapt/PythonSDLActivity.java --in-place
~~~

The Java patcher validates that the selected game directory is inside files/games/<game-id>, and that saves/log paths are exactly inside files/renpy-data/<game-id>. It is a source-level handoff helper. The current Ramen host Activity is checked in and mirrors the official RAPT 8.5.3 `getLibraries()` contract (`renpython` only); the imported `librenpython.so` contains the SDL2 JNI implementation. The source file fingerprint is a source-revision guard, not proof that a game has successfully launched on a device.
