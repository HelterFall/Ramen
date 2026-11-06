# System-NNN runtime audit

## Current follow-up — 2026-11-07: visual compositor, audio lifecycle, and packed-image budget pass

**Current code snapshot:** `38b9c59c17daac7c12b989e9932f3c0ecf38de38`  
**Branch / PR:** `docs/system-nnn-launch-roadmap` / Draft PR #28  
**Validation:** GitHub Actions run [#678](https://github.com/HelterFall/Ramen/actions/runs/42289795365) passed. It ran only `./gradlew --no-daemon --stacktrace testDebugUnitTest`. No APK was assembled; lint and physical-device tests were not run.  
**Scope:** source review against the pinned System-NNN family reference plus JVM unit tests. The family reference is not an exact specification for every target-title parameter, and this pass does not claim visual or audible acceptance on the target game/device.

### Visual compositor changes

- Screen-buffer effects are now kept separately from ordinary image-layer effects. The reserved layer 31 path publishes screen effects to the viewport compositor instead of applying them to one character sprite.
- FADEIN/FADEOUT (10/11) now use a whole-viewport color overlay, with the native direction and color defaults.
- SHAKE (18) now moves the entire game viewport, not an individual sprite.
- FILL (54) now paints the entire viewport with the native channel defaults.
- SCROLL (39) now tiles the active image in both directions and wraps its offset at the viewport edges; the native loop flag defaults to off.
- CHANGE (40) now combines the specified second image layer with the current layer using an offscreen composition surface. The pair is only used when both layers exist, have different layer indices, and have matching dimensions; otherwise the runtime reports the malformed/missing dependency instead of hiding an image or showing an empty scene.
- MOVE (52) now uses SETEFFECTRECT's source and destination rectangles. Position interpolation uses integer rounding; image size remains at the source size until the last frame, matching the family reference.
- CTRANS (9) now uses shared native speed interpolation for alpha. Optional clipping and color-add modes remain unsupported and produce a warning.
- BEZIERMOVE (62) and BSPLINE2 (119) now use native control-point and speed-type operands; default frame duration/control points are covered by tests.
- OLDFILM (55) now uses the configured region, line count, width, color, lifetime, appearance probability, and transparency range. Its random sequence is deterministic on Android and is **not** an exact reproduction of the original Windows random generator.
- CHARABURE (132) now applies repeatable character jitter based on its parameters. Its random sequence is likewise an approximation, not a bit-for-bit match to the original runtime.
- Layer effect/rectangle configuration is persisted across command advances and can be applied when a bitmap is loaded later. Scene reset and layer-clear commands remove the related state.
- Added unit coverage for interpolation, two-layer weights, screen fades/colors, scrolling, movement rectangles, Bezier/B-spline paths, shake, deterministic jitter, and layer-configuration persistence. ViewModel tests cover screen-effect state publication/clearing and malformed CHANGE/MOVE cases.

### Current visual compatibility boundary

The viewport compositor now handles the listed effects at the code level, but **the actual target title still needs a visual device pass** to confirm command operands, timing, clipping, layer ordering, and blend output. The native reference belongs to the System-NNN family and may differ from the target executable.

The following are still partial or unsupported:
- CTRANS clipping and color-add options;
- exact Windows random behavior for OLDFILM and CHARABURE;
- effects outside the implemented/approximated subset, including native animation and DRAW behavior;
- exact target-specific interpretations not yet confirmed from the target executable.

Warnings are retained for known approximations and missing dependencies. A warning being absent is not proof that every target-specific parameter is correct.

### Packed image allocation guard

- Indexed packed DWQ blocks are now checked against a configurable byte budget before the decoder copies the complete block into a `ByteArray`. The default is 128 MiB.
- Standalone WGQ decoding receives the same configured budget and retains the existing 128 MiB hard ceiling.
- Added tests confirming an oversized indexed block is rejected before any source read and an oversized standalone WGQ file is rejected before the header/full-file read.
- This bounds the compressed input allocation; it does **not** eliminate the remaining peak-memory overlap. A valid in-budget packed image can still retain the compressed block, a decoded pixel array, and the Android `Bitmap` at the same time. Streaming or direct-to-bitmap decoding remains open.

### Audio resource and lifecycle changes

- BGM/SE name-list handling is covered with synthetic filename/display-name pairs. The filename occupies the even-position entry, matching the native `GetName(n*2)` usage; BGM command numbers map from one-based slots and SE indices remain zero-based.
- Added an asset-manager test that resolves BGM and SE names, stages a two-segment MML/WGQ playlist, materializes a loose `vaw/se` sound, and checks temporary-file cleanup.
- The player Activity now forwards `onPause` / `onResume` to the ViewModel, which pauses and resumes all active music, voice, and sound-effect players together.
- Added a playback gate for asynchronous `MediaPlayer.prepareAsync()`: a pause received before preparation completes prevents background start; a resume received before completion allows playback to start once preparation finishes. Unit tests cover these state transitions.
- The VOICE routing path itself was not changed. The user has observed that dialogue voices play normally alongside the text; no active voice-playback issue is being pursued.

### Audio compatibility boundary

The new tests establish the local filename-to-resource-to-temporary-file path using synthetic files. They do **not** prove that the actual target game's BGM and SE have been heard on Android. Remaining audio gaps:

- Confirm BGM and SE playback with the actual imported game on a physical Android device.
- Playlist segments advance through sequential Android MediaPlayer preparation, so gapless transitions are not guaranteed.
- An explicit Android Audio Focus policy is not implemented or verified.
- Target-specific MML/WGQ/VAW variants and native playback timing still require comparison with the actual game.

### Other runtime findings carried forward

1. **Audio:** synthetic tests now cover BGM/SE name-list mapping and MML/WGQ/VAW materialization, and the Activity lifecycle now pauses/resumes all audio channels, including while asynchronous preparation is pending. Audible restoration on the actual game/device is not confirmed. Music segment transitions are not guaranteed gapless and explicit Android Audio Focus is not implemented.
2. **Voice:** the user has observed that dialogue voices play normally alongside the text in novellas. No active voice-playback bug is reported, so numeric bank-routing investigation is not being treated as a current blocker unless a reproducible symptom appears.
3. **Movies:** the inventory contains an MPG file, but no connected Android movie event/player surface is implemented.
4. **Unimplemented commands:** DRAW and PRELOADDWQ remain no-ops.
5. **Memory/decoding:** packed DWQ decoding now rejects blocks larger than 128 MiB before allocating the input buffer. Within that bound it can still retain the compressed block, an intermediate pixel array, and the Bitmap simultaneously; streaming/direct-to-bitmap decoding remains desirable.
6. **Text/fonts:** XTX/MML use CP932/Windows-31J handling, while the SPT pool uses Shift-JIS; `sjis_ext.bin` is not integrated into the SPT decoder. MFT font compatibility is not established.
7. **Application modes:** save/load controls remain placeholders; native title/system menus and the complete gallery experience are not implemented.

### Recommended next order

1. Validate BGM and SE audibly on a physical Android device with the actual imported game, including pause/resume and interruption behavior. Only then decide whether gapless playback or Audio Focus changes are needed.
2. Perform a visual acceptance pass on the target game/device for compositor effects used by the actual sample.
3. Continue with movie support, DRAW/PRELOADDWQ, streaming/direct-to-bitmap image decoding, fonts, save/load, and native UI modes.

The earlier material below is the historical audit snapshot `f4eda6eb494e5ebebcc852dd18b68901657f07ad`. Its old visual-compositor and voice-routing statements should be read as historical findings, not as the current code snapshot. The PR remains open and Draft; `main` was not changed.

---


**Audit snapshot:** `f4eda6eb494e5ebebcc852dd18b68901657f07ad`  
**Development branch:** `docs/system-nnn-launch-roadmap`  
**PR:** #28 (must remain open and Draft)  
**Native family reference:** `tinyan/SystemNNN@78a05053c78b3b80065a9a209c6685a7c60a2e83`  
**Audit type:** static source review, repository structure review, and CI build verification. No game media or scenario text was inspected.

## Executive assessment

Ramen currently has an experimental System-NNN SPT interpreter and a useful set of bounded resource parsers. It is **not yet a full System-NNN engine replacement**. The current code can load selected SPT projects, execute a documented subset of control flow, render static image layers, and stage supported VAW/WGQ/VPK resources. The original engine has much broader screen effects, animation, movie playback, initialization/configuration, and application modes.

The highest-impact newly identified defect was installation-root selection after ZIP import. The ZIP extractor preserves the archive's leading directory, while the player previously passed the outer extraction folder to the asset manager. A nested project could therefore be found and started while its `spt/bgmlist.xtx`, `wgq/`, and `vaw/` resources were looked up one directory too high. The same recursive scan could also mix a nested secondary installation's banks into the main game's catalog.

**Both root-selection issues are fixed in this audit snapshot**:
- `SystemNnnPlayerActivity` now carries the selected project's actual install root into asset-manager construction.
- `SystemNnnAssetManager` prunes a nested directory that contains its own direct `spt/project.xtx`, while retaining the known `!eng files` alternate script directory.

This is a source-level correction and a successful debug build, not yet a device-playback confirmation.

## Evidence reviewed

### Ramen source

Reviewed the project and runtime boundaries, including:
- `SystemNnnScriptLoader` and `SystemNnnCommandStreamReader`;
- `SystemNnnCommandDecoderImpl` and the player ViewModel/screen;
- `SystemNnnPlayerActivity` project discovery and selected-scene routing;
- `SystemNnnAssetManager`, GPK/GTB and VPK/VTB repositories;
- loose VAW/WGQ parsing and materialization;
- Android bitmap decoding;
- ZIP/SAF import and storage guards;
- existing System-NNN format/command research notes.

### Native family reference

The pinned source revision contains separate modules for:
- the SPT callback/execution path;
- the image-layer/effect compositor;
- more than one hundred named effect IDs in `nyanEffectLib/effectlist.h`;
- MML/WGQ music sequence parsing and streaming;
- DirectShow movie playback in `nnnLib/commonPrintMovie.cpp`.

This reference is useful for family behavior, but it is **not proof of exact target-title semantics** for every opcode or operand. Target-specific conclusions must be checked against the supplied target executable/sample.

### Uploaded structure report

The text inventory in `main` reports:
- 28 directories and 920 files;
- approximately 2.46 GiB total;
- 84 VPK/VTB pairs;
- 6 GPK/GTB pairs;
- 348 loose VAW files;
- 80 SPT files;
- 80 WGQ files and 33 MML files;
- one MPG file at `movie/gss_demo.mpg` (282,923,008 bytes);
- a nested `hui/` installation with its own `spt`, `dwq`, `cdvaw`, `vaw`, and `wgq` directories.

The `hui.zip` Git blob is 13,105,251 bytes. The connected GitHub file reader can expose its metadata but returned no binary content for this ZIP; consequently, its central directory, SPT bytes, VTB entries, MML bytes, and media headers have **not** been independently examined in this audit. The structure report is evidence of names/sizes/layout, not evidence that those files parse or play.

## Findings by priority

### P1 — movie playback is absent

The structure contains a real MPG asset under `movie/`. The native family source has a dedicated movie mode using DirectShow, resolves movie names from the game's movie list/configuration, opens files beneath `movie/`, handles completion/skip, and returns to the calling mode.

The current System-NNN decoded-command set and player screen contain no movie/video event or video output surface. There is no Android movie backend connected to the SPT flow. A file being present in the imported folder is therefore not enough for Ramen to play it.

**Required work:** identify the target's movie-selection/launch path and metadata from the target sample; add a lifecycle-aware Android video surface/player; implement completion, stop, skip policy, scaling, audio mixing, and return-to-script behavior. Do not infer a movie opcode from the family reference alone.

### P1 — the effect renderer is only a small approximation of the native compositor

The native family list defines effect IDs from 0 through 140, including wipes, two-layer transitions, screen effects, color transforms, raster/noise, weather/natural effects, geometry, animation, and movement.

The current Compose renderer implements only a small subset, and several handlers are approximations:
- ID 9 (CTRANS) approximates alpha interpolation but does not reproduce the easing, clipping, additive color, and other parameters.
- IDs 10/11 (FADEIN/FADEOUT) are currently sprite-alpha approximations; the native effects use screen-level fade/fill behavior and color parameters.
- ID 18 (SHAKE) moves a sprite, whereas the native effect shifts the screen buffer and supports axis/loop parameters.
- ID 39 (SCROLL) translates a layer but does not reproduce native clipping/wrap semantics.
- ID 40 (CHANGE) is a two-layer compositor effect, not a one-layer fade. The current renderer's `1 - t` alpha mapping is incorrect.
- ID 52 (MOVE) is included in the ViewModel's supported-ID set but has no matching handler in the Compose renderer.
- IDs 55, 62, 119, and 132 are visual approximations; old-film parameters, easing/subframe behavior, or native random shake timing are not reproduced.

Because IDs 40 and 52 are currently in `SUPPORTED_NATIVE_VISUAL_EFFECTS`, the UI can fail to show the incomplete-effect warning for them. This must be corrected before describing those effects as supported.

**Required work:** build an effect registry from the exact target's effect IDs/parameter counts; implement transitions as compositor operations on one or more layers (not ad-hoc alpha branches); separate screen-level effects from per-layer effects; add visual fixtures for every implemented effect and parameter family. Until then, label the renderer experimental and report the exact effect IDs that are approximated or ignored.

### P1 — multi-bank voice resolution is not yet proven

The structure has 84 VPK/VTB audio banks. A named VOICE resource is resolved by searching bank entry names. If the same name appears in more than one bank, Ramen correctly refuses to guess and reports ambiguity.

The numeric fallback `materializeByIndex` is intentionally unavailable when more than one VTB bank exists, because the code has no verified global ordering across those banks. This avoids silently playing a random voice, but means that any dialogue relying on a global numeric voice index will not resolve until the target's bank-selection rule is known.

**Required work:** parse the target VTB catalog metadata and trace the target's voice callback/bank selection. Verify that each explicit Pool 2 VOICE name maps to exactly one entry, or add a target-validated bank/slot mapping. Do not concatenate 84 VTB indexes into an invented global table.

### P1 — resource-root mismatch after ZIP import — fixed in this snapshot

`SafeZipExtractor` preserves the ZIP entry path. With a single leading directory such as `hui/`, script discovery can find `hui/spt/project.xtx`, but the old player passed the outer extraction folder to the asset manager. That made direct `spt/bgmlist.xtx`, `wgq/`, and `vaw/se/` lookups point at the wrong directory. The same recursive asset scan could mix resources from a nested secondary game installation.

The current snapshot resolves the install root from the selected SPT module path and uses it for resource discovery. Recursive discovery skips a nested directory with its own direct `spt/project.xtx`, except the known `!eng files` alternate directory.

This is the most likely source-level explanation for the reported missing MML/WGQ and `vaw/se` paths when importing a wrapped ZIP. It still needs confirmation with the actual sample on Android.

### P2 — MML/WGQ playback is implemented, but gapless playback is not guaranteed

The current path reads `wgq/<track>.mml`, falls back to a single `wgq/<track>.wgq`, parses ordered WGQ tokens and finite/infinite bracket loops, stages Ogg payloads, and advances through them with Android MediaPlayer.

A segment completion creates/prepares the next MediaPlayer asynchronously. That is not a guarantee of gapless continuation. Native CWaveMusic streams a sequence through its own audio pipeline, so audible gaps or timing differences are possible.

The staged-segment lifetime bug that deleted files before a repeat/loop was fixed in commit `97f729d`; the current player retains files until the playlist stops, completes, or errors.

**Required work:** play real MML tracks on device; measure segment boundaries; if gaps are audible, replace sequential prepare/start with a queue-capable or low-level audio pipeline and validate native repeat semantics against target MML examples.

### P2 — SPT text decoding and legacy code-page handling differ

XTX/MML name lists now use Windows-31J/CP932 unless a UTF-8 BOM is present. The SPT string-pool reader still uses `Shift_JIS`. On Japanese Windows builds, CP932 is commonly used and includes extensions beyond strict Shift-JIS. The inventory also includes `sjis_ext.bin`, which is not currently integrated into the SPT text decoder.

This may affect uncommon glyphs or identifiers and can lead to an apparently valid but incorrect resource name.

**Required work:** compare raw SPT pool bytes from the actual sample against CP932, Shift-JIS, and the title's extension table; add strict decoding tests while preserving raw bytes and record boundaries.

### P2 — PRELOADDWQ and DRAW are not rendered

- PRELOADDWQ is decoded as a no-op. This preserves command progression but does not reproduce the native image preloading/cache behavior.
- SYSTEM_COMMAND DRAW is skipped as a bounded record. Its target-specific payload is not rendered.
- Animation-layer and other native effect systems are not represented by the current static sprite pipeline.

These omissions can affect timing, animation, and visual composition even when a later LOADDWQ successfully loads a static image.

### P2 — game initialization/configuration is only partially consumed

The source tree has substantial `init/` configuration and `nya/` sidecar data. The player currently consumes the project/module list and selected script-side tables needed for its current path, but it is not a complete port of the native initialization/configuration system. Movie setup, screen settings, system menus, listen lists, and other mode-specific settings are not generally wired into the player.

The native title/scene/gallery/movie modes are not equivalent to the current gameplay-only Compose screen. Save/load actions in the player UI are placeholders; existing save data is not a claim of importable Ramen save state.

### P2 — large packed image decode can have a high transient memory peak

The Android bitmap decoder applies an 8,000,000-pixel per-image limit, and the ViewModel limits active sprite layers to 24,000,000 pixels. Those are useful safeguards.

For packed DWQ resources, however, the current decoder reads the complete indexed block into a `ByteArray`, decodes an intermediate ARGB pixel array, and then creates a Bitmap. A large valid or malformed block can therefore create a substantial transient allocation before the active-layer pixel budget is applied. The largest GPK banks in the inventory are hundreds of MiB each.

**Required work:** add a much smaller explicit compressed-block limit or stream the packed decoder; measure peak memory on target devices; test graceful error handling when the OS refuses a bitmap allocation.

### P2 — startup work scales with all discovered banks

The manager validates every discovered GPK/GTB and VPK/VTB pair before playback. The parsers use bounded random-access reads and validate index/resource bounds, which is good, but the full inventory has 84 audio banks and several very large image archives. Startup time and Ogg-page validation cost should be measured on device. A future optimization could lazily open bank payloads while validating indexes up front, provided errors remain actionable and deterministic.

### P3 — save/load, visible scene browser, and native menus are incomplete

The scene.dat and scenevoice readers provide data/routing support, but the current Activity does not expose the complete native scene/gallery experience. Save/load controls show placeholder messages. Native title menus, system menus, settings, and every common mode are not implemented.

## Implemented safeguards and validated pieces

- SPT loading is bounded, validates the XOR-demasked header/signature, and retains raw text bytes.
- The command stream reader validates record lengths and record boundaries before dispatch.
- The target-specific research notes record validations for module transfers, local SUB/RETURN, SETFILM/ENDFILM, SELECT/CASE, IF/ELSIF/ELSE, and scene.dat routing. See [SPT format notes](SYSTEM_NNN_SPT_FORMAT_NOTES.md) and [command map notes](SYSTEM_NNN_COMMAND_MAP_NOTES.md).
- GPK/GTB parsing checks index offsets, resource ranges, and DWQ metadata; bitmap decoding applies pixel limits.
- VPK/VTB parsing checks record boundaries, terminal index behavior, RIFF/WAVE descriptors, and Ogg page boundaries.
- Loose audio parsing validates PACKTYPE and payload bounds; supported VAW payloads are staged to app cache.
- ZIP extraction rejects traversal/absolute paths, duplicate paths, excessive entry count, and excessive path depth; writes use the storage guard.
- The MML playlist repeat-file-lifetime bug was fixed in `97f729d`.
- The selected asset-root and nested-install contamination bugs were fixed in `f4eda6e`.

## Build/validation status

For commit `f4eda6eb494e5ebebcc852dd18b68901657f07ad`:
- GitHub Actions run #570: **assembleDebug succeeded** and the debug APK artifact was uploaded.
- Artifact: `ramen-debug-apk`, 11,903,278 bytes ZIP, SHA-256 `f003d605be540c725d6bd6b290a6b3d0b41e4c449ad9630ccdf661986650eec0`.
- Tests and lint were **not run** in this workflow.
- No physical-device playback or visual acceptance test was performed.

PR #28 remains open and Draft, targeting `main`. The audit did not modify `main` and did not add game media or scenario files to the development branch.

## What is needed to close the remaining evidence gaps

The current GitHub file reader exposes the structure report and ZIP metadata, but not the binary contents of `hui.zip`. To complete target-specific verification, the mini sample needs to be accessible as a browsable extracted directory (or the non-media SPT/GTB/VTB/MML files supplied individually) in the user-controlled repository. Then the next read-only pass can:
1. replay every SPT module and enumerate the actual SYSTEM_FUNCTION / SYSTEM_COMMAND IDs and operand shapes;
2. compare every decoded VOICE/SOUND/MUSIC reference with the actual VTB/VAW/WGQ catalogs;
3. validate MML loop patterns and WGQ header variants;
4. inspect GPK/GTB resource metadata and representative image headers without exporting image/media content;
5. inspect the MPG container metadata (codec, dimensions, duration) without reproducing the video;
6. produce a target-specific compatibility matrix rather than relying on family-level assumptions.

Until those checks and device playback pass, the correct product label is **experimental System-NNN player with partial command/resource support**, not full System-NNN game compatibility.
