# Engine Research Track

**Status:** active research track, not a compatibility claim  
**Started:** 2026-11-02

## Goal

Move Ramen toward difficult native and proprietary visual-novel engines by building reusable inspection infrastructure before selecting a runtime. This track does not launch unknown Windows executables. It establishes a repeatable, read-only way to inventory executable metadata, inspect proprietary resource indexes, and preserve byte-level evidence.

The work remains engine-agnostic until the evidence supports a specific adapter.

## What has been implemented

- `BinaryArtifactInspector` performs bounded, read-only inspection of common binary signatures.
- PE inspection reads the DOS header's PE offset, validates the PE signature, and reports the COFF machine type, optional-header kind, section count, and DLL flag. The bounded import-directory parser reports DLL names for PE32 and PE32+.
- Known generic signatures include PE, ELF, ZIP, RIFF, Ogg, PNG, and JPEG.
- `ReadOnlyRandomAccessSource` provides positional reads; `FileRandomAccessSource` uses positional `FileChannel` access.
- `Sha256FileHasher` computes incremental file fingerprints.
- `DirectoryInventoryScanner` traverses imported files deterministically under file-count, depth, and hash budgets.
- `DirectoryInventoryJsonWriter` streams versioned JSON reports to a caller-owned output.
- `GpkGtbArchiveParser` validates the observed paired image index/archive layout, JPEG and BMP block headers, and bounded resource payload ranges.
- `VpkVtbArchiveParser` validates the observed fixed-record audio index, 108-byte VPK prefix, PCM-style descriptor, and Ogg page boundaries and sequence metadata.
- `ReadOnlyRangeStreamer` copies a validated resource range to a caller-owned output stream with a fixed-size buffer (64 KiB by default, capped at 1 MiB). It does not close or flush the destination.
- `ReadOnlySourceRangeInputStream` exposes one bounded range as an InputStream without owning the underlying source.
- `AndroidGpkImageDecoder` reads image bounds before decoding and chooses an Android BitmapFactory sample size to stay within an 8-million-pixel default budget.
- `VpkOggCacheMaterializer` stages one parsed Ogg range into a uniquely named cache file. It has no fixed per-resource size ceiling and checks available storage while streaming; it does not instantiate or control a media player.
- `ResourceInspectorActivity` provides a user-facing file-pair picker, GPK/GTB image list and preview, VPK/VTB audio list, and a foreground Android `MediaPlayer` with basic play/pause/stop controls. Selected source documents are copied to private cache with per-chunk available-storage checks and a protected free-space reserve; there is no fixed 512 MiB per-file cap.
- Synthetic unit tests cover both proprietary parser families, optional terminal index sentinel handling, and malformed resource boundaries.

## Evidence from supplied samples

The user supplied six sample files for inspection. They were examined as bytes; `gss.exe` was not executed.

### Image pair: `bg0.gtb` + `bg0.gpk`

- `bg0.gtb` is 2,990 bytes and `bg0.gpk` is 67,588,981 bytes.
- The GTB contains a little-endian count of 163, followed by 163 name offsets, 163 archive offsets, and a NUL-terminated ASCII name pool.
- All 163 names are distinct. Resource offsets are strictly increasing and begin at zero.
- GPK blocks have a 64-byte header and are bounded by the next index offset (or EOF).
- 153 payloads are JPEG with `PACKTYPE=5`; their header lengths match their indexed payload ranges.
- 10 payloads are BMP with `PACKTYPE=0`; the internal BMP file size matches each indexed payload length. All ten sample bitmaps are 800 × 600.
- All 163 sample image payloads passed an external image-file validation during research. No game assets were added to the repository.

### Audio pair: `aa.vtb` + `aa.vpk`

- `aa.vtb` is 21,720 bytes, which forms 1,810 records of 12 bytes each; `aa.vpk` is 87,626,372 bytes.
- Each record is an 8-byte printable ASCII name plus a little-endian 32-bit offset.
- The final record points to archive EOF and is a terminal index sentinel in this sample. It is not a playable audio block. The other 1,809 entries point to actual blocks.
- Each block starts with a repeated 64-byte text preamble, followed by a 44-byte RIFF/WAVE PCM-style descriptor. The Ogg stream starts at block offset +108.
- The descriptor's PCM `data` length is not the size of the compressed Ogg payload. VTB offsets establish physical block boundaries.
- The 1,809 Ogg payloads contain 23,592 pages. Their page boundaries, serial numbers, sequence numbers, and BOS/EOS flags are consistent. A separate sample inspection also verified page CRCs; the app parser currently does not compute CRCs.

### XTX sample

The supplied 535-byte `codecerror.xtx` is CRLF-delimited key/value text describing one error-window configuration. This does not establish that all `.xtx` files share the same format.

### Executable

The supplied `gss.exe` is a 626,688-byte PE32 x86 image. Its standard import table names `ogg.dll`, `vorbis.dll`, `DDRAW.dll`, `DSOUND.dll`, and `WINMM.dll`. These are declared dependencies, not proof that the executable is compatible with Android or that a complete engine has been identified. The imported DLL files themselves were not supplied as part of the six inspected samples.

### What is still unknown

The complete read-only census for one imported sample directory is now available as [`ramen-inventory-d9c1071b.json`](../ramen-inventory-d9c1071b.json). It records 775 files across 19 extension hints and 2,595,371,906 bytes. The scan completed without truncation or warnings, and all file entries have SHA-256 fingerprints.

The inventory broadens the list of observed file families, but it does **not** establish the grammar, encoding, command set, or runtime semantics of any scenario/control data. The JSON stores metadata and short signature observations, not file payloads. A filename, extension, or short byte prefix is not sufficient evidence for a parser. The user-facing report export flow is implemented; SAF positional access and physical-device validation remain separate tasks.

## Current boundaries

- Inspection is read-only and never executes a selected binary.
- File extensions are hints, not format detection.
- The GPK/GTB and VPK/VTB parsers themselves return bounded payload ranges. Separate adapters decode selected images and hand selected Ogg payloads to Android `MediaPlayer`; none of these components launches the original engine or a complete game.
- Ogg page CRCs are not checked by the app parser.
- The resource inspector and foreground media-player preview exist, but physical-device image/audio validation has not been performed. Android codec support may vary. The inspector stops/releases its preview player when it leaves the foreground and does not provide a background audio service or seek bar.
- The supplied game samples are not stored in the repository; tests use small synthetic fixtures.
- SAF/content-URI positional random access is not implemented. The inventory-report save/share flow is implemented; reports contain metadata only. The separate resource inspector UI works by copying selected files into private cache.
- A detected file format does not imply that Ramen can run the associated game.

## Next technical steps

1. Keep the completed inventory as a metadata baseline; do not treat it as a script-format specification.
2. The initial engine-neutral scenario model, parser, interpreter, save codec, and playable text/placeholder demo are now implemented in the Ramen branch.
3. Add a resource-provider contract and connect the original demo to local image resources; add audio cue commands as a separate adapter.
4. Test save/restore, lifecycle, and responsive layout on physical Android devices.
5. Keep any future legacy-engine adapter separate and require suitable byte-level evidence, a safe test fixture, and a documented compatibility boundary before making claims.

## Decision log

- 2026-11-02: Prioritize difficult native/proprietary engine research rather than treating Ren'Py as the only next runtime.
- 2026-11-02: Start with executable and container inspection, not execution or emulation.
- 2026-11-02: Record the visible extension families as unverified until sample bytes are available.
- 2026-11-02: Promote the observed GPK/GTB and VPK/VTB pairs to implemented parser entries only after paired-index structure and payload boundaries were validated against the supplied samples.
- 2026-11-02: Keep the README format summary limited to the six examined samples; classify other roles only after a complete directory inventory and representative byte-level evidence.


## Full directory inventory — 2026-11-02

- Report: [`ramen-inventory-d9c1071b.json`](../ramen-inventory-d9c1071b.json)
- 775 file entries and 19 distinct extension hints.
- 2,595,371,906 bytes observed; the same total was hashed.
- `truncated` is false; report-level and per-file warnings are empty; every entry has SHA-256 and artifact metadata.
- The report is metadata-only. It does not contain file payloads and cannot establish scenario syntax or engine behavior.
- The next runtime prototype will use original, age-appropriate fixtures. Unknown proprietary formats remain unknown until independently suitable evidence exists.


## Scenario runtime handoff — 2026-11-02

The branch now contains an engine-neutral runtime prototype under the scenario runtime package:
- ScenarioModel.kt: scene, command, choice, character, frame, and save-state models.
- ScenarioParser.kt: bounded parser for Ramen's own line-oriented DSL.
- ScenarioSession.kt: deterministic command interpreter with a cap on automatic transitions.
- ScenarioSaveCodec.kt: versioned, bounded save-state encoding/decoding.
- ScenarioDemoContent.kt and ScenarioDemoActivity.kt: original bundled demo and UI.

This prototype proves the control-flow path for dialogue and branching. It does not decode proprietary script files and does not yet resolve image/audio resource keys; its stage uses labelled visual placeholders. The next work item is a real resource-provider contract and device validation.

## Cross-check of observed extension families — 2026-11-02

A public community reverse-engineering project, VNTools, documents several related Japanese visual-novel engine families (System-NNN, DDSystem, and BlackCyc) and reports overlapping format families:
- GTB + GPK as paired image archives (called DWQ by that project);
- VTB + VPK as voice/sound archives;
- WGQ as BGM resources;
- XTX / FXF as script-related variants;
- SPT as a script format used by some newer BlackCyc titles.

Reference: https://github.com/abunomaru/VNTools

This is an independent community reference, not an official specification and not proof of the exact engine/version in the inventoried directory. The combination is a useful research lead only. The individual VAW files in the inventory must not be conflated with the community project's use of VAW as a label for the VTB/VPK archive family. Keep extension-only evidence separate from byte-validated structures.

## Explicit status of the bundled scenario demo

The ScenarioDemoContent DSL and ScenarioParser are Ramen's own test language. They do not parse SPT, XTX, FXF, or a GSS command stream. ScenarioSession demonstrates generic control flow; the demo's background/character UI uses placeholders. Existing GPK/GTB and VPK/VTB readers are independent resource-preview tools and are not yet connected to a target-engine runtime.

The target executable is a PE32 x86 image whose declared imports include Windows multimedia/DirectDraw and Ogg/Vorbis libraries. This supports the conclusion that the original program expects a native Windows environment, but it does not identify every engine semantic or prove a specific adapter architecture.

Next research gate: establish the engine family/version from multiple independent sources, define the adapter boundary for script commands/resources/rendering/audio/save state/lifecycle, and do not claim target compatibility until an appropriate end-to-end fixture validates it.