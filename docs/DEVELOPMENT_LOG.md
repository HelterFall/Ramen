# Development Log

This file is the durable hand-off log for work across ChatGPT sessions and contributors. Each entry records what changed, what is only a hypothesis, and what should happen next.

## 2026-11-02 — Binary inspection and format-registry foundation

### Goal
Begin the native/proprietary engine research track with generic, read-only executable and container inspection. The aim is to make Ramen capable of collecting evidence about difficult engine families before designing a runtime.

### Changed in this PR
- Added `BinaryArtifactInspector` under the `inspection` package.
- Added bounded PE header inspection: DOS PE offset, PE signature, COFF machine, optional-header kind, section count, and DLL flag.
- Added first-byte signature recognition for ELF, ZIP, RIFF, Ogg, PNG, and JPEG.
- Added `ContainerFormatRegistry` with separate verified-signature and extension-only evidence lists.
- Added unit tests for PE32/x86, PE32+/ARM64 DLL, ZIP/Ogg, unknown extension, malformed MZ, and registry evidence separation.
- Added engine-research and format-registry documents.
- Updated README, architecture, compatibility notes, and roadmap to make this the current development track.

### Evidence captured
Screenshots supplied in chat showed repeated `.gpk/.gtb` and `.vpk/.vtb` filenames, `.xtx` files, an executable, and several DLL names. These are filename-level observations only. No binary bytes were available in the repository, so no proprietary format has been identified or decoded.

### Explicitly not implemented
- No EXE execution, emulation, or runtime launch.
- No proprietary archive parser or decompressor.
- No script VM.
- No PE import-table or version-resource parsing yet.
- No claim of support for the observed extension families.

### CI follow-up
- First CI compile failed because the unknown-signature report called a ByteArray formatter on the result of `take(8)`, which is a List. The report now formats the bounded ByteArray prefix directly. The corrected run passed `assembleDebug`, `testDebugUnitTest`, and `lintDebug`; PR #19 was merged.

### Next steps
1. Implement a bounded PE import-directory reader and list imported DLLs.
2. Add an incremental SHA-256 inventory.
3. Introduce a generic read-only random-access source interface.
4. Add a container-probe interface with `MATCH`, `NO_MATCH`, and `MALFORMED` outcomes.
5. Add synthetic test fixtures for malformed headers and integer-overflow boundaries.

### Handoff note
Start from the latest `main` and read this log before making the next change. The detailed evidence and unknowns are in [Engine Research](ENGINE_RESEARCH.md) and [Format Registry](FORMAT_REGISTRY.md).


## 2026-11-02 — Bounded PE import-directory inspection

### Goal
Extend the read-only PE inspector to identify linked DLL names without executing a Windows binary. This helps later runtime feasibility research by showing the native dependency surface.

### Changed in this PR
- Added PE32 and PE32+ import data-directory location handling.
- Added bounded section-header parsing and RVA-to-file-offset mapping.
- Added import-descriptor traversal with a hard cap of 512 descriptors.
- Added bounded ASCII DLL-name reading (maximum 260 bytes).
- Reported imported library names and import-directory RVA/size as PE metadata.
- Added tests for PE32, PE32+, an unmapped DLL-name RVA, and malformed data handling.
- CI run [41392364823](https://github.com/HelterFall/Ramen/actions/runs/41392364823) passed build, unit tests, lint, and APK artifact upload; PR #20 was merged into `main`.

### Limits
- This reads only the standard PE import directory. It does not parse delay-load imports, bound imports, forwarded exports, ordinals, or recursively resolve DLL dependencies.
- A DLL name in the import table is a declared dependency, not proof the DLL exists or that the program can run on Android.
- The inspector remains read-only and never loads or executes the inspected file.

### Next steps
1. Add incremental SHA-256 file inventory.
2. Introduce a generic read-only random-access source abstraction.
3. Add container-probe outcomes: `MATCH`, `NO_MATCH`, and `MALFORMED`.
4. Add a user-visible inventory report after the core APIs stabilize.


## 2026-11-02 — Random-access sources, SHA-256, and container probes

### Goal
Provide reusable byte-level infrastructure for future container parsers, without coupling each parser to a mutable file cursor or loading entire game archives into memory.

### Changed in this PR
- Added `ReadOnlyRandomAccessSource` with positional reads and bounded `readFullyAt`.
- Added `FileRandomAccessSource` backed by positional `FileChannel` reads.
- Added `Sha256FileHasher` and `Sha256Fingerprint`; hashing uses a configurable fixed-size buffer (64 KiB by default, capped at 1 MiB).
- Added `ContainerProbe`, `ContainerProbeResult`, and explicit outcomes `MATCH`, `NO_MATCH`, and `MALFORMED`.
- Added `FixedSignatureProbe` as a minimal example that validates a signature only, not an entire container structure.
- Added tests for random reads at independent offsets, EOF bounds, known SHA-256 test vectors, empty files, and all three probe outcomes.
- Updated README, roadmap, engine research, and format registry documentation.

### Limits
- The random-access abstraction currently has a file-backed implementation; SAF/content-URI and in-memory implementations are not integrated.
- SHA-256 currently fingerprints an individual file; a directory inventory and UI/report export are not implemented.
- Fixed-signature probes prove only that bytes match at a given offset. They do not validate compression, indexes, record tables, or payload integrity.
- The PE inspector still accepts `File` directly; adapting it to the new source interface is the next refactor.

### Next steps
1. Build a bounded directory inventory combining paths, sizes, SHA-256, and binary reports.
2. Refactor PE inspection to consume `ReadOnlyRandomAccessSource`.
3. Add parser fixtures for container structure only when a format has repeatable byte-level evidence.
4. Extend PE inspection with version resources and delay-load imports.

### Handoff note
Read this entry plus the previous entries before continuing. The current branch adds generic infrastructure but does not decode any proprietary archive format.


## 2026-11-02 — Deterministic directory inventory and source-backed PE parser

### Goal
Connect the previously separate binary inspector, random-access source, and streaming hasher into one repeatable report for an imported game directory.

### Changed in this PR
- Refactored `BinaryArtifactInspector` so its PE and signature parsing uses `ReadOnlyRandomAccessSource`; the `File` entry point is now a convenience wrapper.
- Added `DirectoryInventoryScanner` with deterministic depth-first traversal and sorted child names.
- Added file-count (10,000), directory-depth (64), and total SHA-256 byte (8 GiB) defaults.
- Each inventory entry reports relative path, size, SHA-256 when within budget, binary signature/PE metadata, and per-file warnings.
- The scanner skips symbolic links, reports truncated scans, and does not execute or modify files.
- Added tests for deterministic path ordering, PE/PNG/Ogg metadata, hash-budget behavior, file-count truncation, and invalid roots.

### Validation
- Android CI run [41393805833](https://github.com/HelterFall/Ramen/actions/runs/41393805833) passed `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and debug APK artifact upload.

### Limits
- The scanner currently operates on imported app-private `File` directories.
- SAF/content-URI random access is not implemented; providers that cannot seek will need bounded staging to private storage.
- A versioned JSON exporter is now available (see [Inventory Report JSON Schema](INVENTORY_REPORT_SCHEMA.md)); there is not yet a user-facing inventory screen or save/share flow.
- The inventory identifies common signatures and PE dependencies; it does not decode proprietary game archives.

### Next steps
1. Add a user-facing save/share flow for the versioned JSON inventory report.
2. Add a SAF-backed positional source where supported, with bounded private staging as a fallback.
3. Consider PE version resources and delay-load import metadata.
4. Add container-specific probes only when byte-level evidence supports repeatable tests.

### Handoff note
The inventory and PE parser now share the same positional read abstraction. Continue from the latest `main` and read this log before extending the parser or adding proprietary format hypotheses.


## 2026-11-02 — Versioned JSON inventory export

### Goal
Make the binary inventory portable and comparable without requiring the original game files to be copied into the repository or attached to a chat.

### Changed in this PR
- Added `DirectoryInventoryJsonWriter` with schema version 1.
- The writer streams UTF-8 JSON to a caller-provided `OutputStream` or `Writer`; it does not build one large intermediate JSON string.
- Included root metadata, scan limits/truncation, per-file paths and sizes, SHA-256, signature classification, PE metadata/imported DLLs, and warnings.
- Added JSON escaping for quotes, backslashes, line breaks, tabs, and other control characters.
- The writer flushes but does not close the caller-owned output stream.
- Added tests for PE metadata serialization, null/unavailable values, escaping, and output-stream ownership.
- Added a separate schema document and updated README, roadmap, engine research, and format registry.

### CI follow-up
- The first CI run compiled the app but failed three JSON writer tests because the quote-emission helper did not write the quote character correctly.
- The writer now emits the quote code point explicitly. Follow-up CI run [41394618440](https://github.com/HelterFall/Ramen/actions/runs/41394618440) passed `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and APK artifact upload; PR #24 was merged.

### Limits
- The writer is a serialization component only; the app does not yet expose a save/share UI.
- Schema version is currently 1. Breaking field or semantic changes require an explicit versioning decision.
- Reports contain local relative paths and binary metadata; callers should review them before sharing.

### Next steps
1. Add a user-facing save/share flow using the Storage Access Framework.
2. Add a positional source implementation for SAF-selected documents where supported, or bounded staging when seeking is unavailable.
3. Continue PE metadata coverage and container probes only where evidence supports them.


## 2026-11-02 — GPK/GTB and VPK/VTB resource parser research

### Goal
Use the supplied paired archive samples to move two extension families from filename-level observations to bounded, byte-validated read-only parsers.

### Evidence established
- The supplied `bg0.gtb` index has a little-endian 32-bit count followed by name-offset and archive-offset tables, then a NUL-terminated ASCII name pool. It maps 163 unique resources in `bg0.gpk`.
- The GPK sample has 153 JPEG payloads with `PACKTYPE=5` and 10 BMP payloads with `PACKTYPE=0`. Each block starts with a 64-byte header. JPEG header lengths match the indexed payload size; BMP internal file lengths match the indexed BMP payload size.
- The supplied `aa.vtb` consists of 1,810 12-byte records. Each record contains an 8-byte ASCII name and a little-endian 32-bit VPK offset. The final record points to EOF and is treated as a terminal sentinel; 1,809 resource blocks remain.
- Each observed VPK block has a 64-byte text preamble, a 44-byte RIFF/WAVE PCM-style descriptor, then Ogg data at offset +108. VTB offsets, not the descriptor's PCM data length, bound the compressed Ogg payload.
- The supplied audio pair contains 23,592 Ogg pages. A separate sample analysis verified page boundaries, serial and sequence continuity, BOS/EOS flags, and page CRCs. The app parser currently checks page structure/sequence but does not compute CRCs.
- The supplied `codecerror.xtx` is a 535-byte CRLF-delimited key/value configuration sample. No general claim is made about every .xtx file.
- `gss.exe` was inspected as a 626,688-byte PE32 x86 image; its import table declares Ogg/Vorbis and DirectDraw/DirectSound/WinMM libraries. It was not executed.

### Changed in this PR
- Added `GpkGtbArchiveParser` and resource metadata models. It validates index tables, names, offsets, 64-byte block headers, JPEG size/signature fields, and BMP file/DIB bounds.
- Added `VpkVtbArchiveParser` and audio metadata models. It validates 12-byte index rows, an optional EOF sentinel, the 108-byte block prefix, PCM-style descriptor consistency, and bounded Ogg page sequence structure.
- Added synthetic tests for JPEG/BMP blocks, optional VTB terminal sentinel handling, and malformed payload/sequence bounds.
- Updated the format registry and research documentation to distinguish implemented parsers from runtime support.
- No supplied game files or copyrighted assets were committed.

### Limits
- Parsers return payload offsets and lengths; they do not decode images/audio, extract complete games, or launch the engine.
- Ogg page CRC validation is not part of the production parser yet.
- No user-facing archive browser or streaming export flow exists.
- The .xtx conclusion applies only to the supplied codecerror.xtx sample.

### Next steps
1. Add a bounded streaming copy API for individual parser-returned resource ranges.
2. Add end-to-end extraction tests using original synthetic fixtures.
3. Explore Android-side image/audio consumption and then evaluate the engine's script/runtime requirements.


### Follow-up — bounded payload range streaming
- Added `ReadOnlyRangeStreamer`, which copies a validated source range to a caller-owned `OutputStream` using a fixed 64 KiB buffer by default (maximum 1 MiB).
- Invalid/overflowing/out-of-bounds ranges are rejected before copying. The API does not close or flush the source or destination.
- Added tests for subrange copying, empty ranges at EOF, destination ownership, and invalid ranges.


### Follow-up — Android resource bridge APIs
- Added `ReadOnlySourceRangeInputStream`, a bounded InputStream view that does not close its caller-owned random-access source.
- Added `AndroidGpkImageDecoder`, which performs a bounds-only decode pass, compares actual dimensions with parsed metadata, then applies `inSampleSize` to cap decoded output at 8 million pixels by default.
- Added `VpkOggCacheMaterializer`, which stages an individual parsed Ogg payload into a unique app-cache file, with a 256 MiB default per-resource limit and cleanup of partial files on failure.
- Added JVM tests for range InputStream boundaries, source ownership, cache staging, and cache size rejection.
- No player lifecycle or UI was added. Android device image/audio behavior remains unverified.


### Follow-up — resource inspector UI and Android preview
- Added `ResourceInspectorActivity` and a library-screen entry point.
- The inspector opens exactly two documents, validates the expected extension pair and matching base filename, then copies each selected document to private cache before positional parsing. Each selected source file is limited to 512 MiB; no broad storage permission is requested.
- Added a GPK/GTB image list and image preview. The first image is decoded after parsing; selecting another row decodes it on the worker thread.
- Added a VPK/VTB audio list and a foreground `MediaPlayer` flow with basic play/pause/stop controls. The selected Ogg range is staged to cache first.
- The player and temporary cache files are released when the inspector leaves the foreground or is destroyed. This is an asset preview tool, not a game runtime.
- The device picker, image rendering, and Ogg decoder behavior have not yet been verified on a physical Android device. Android codec support can vary.


### Validation — Resource Inspector integration
- The first CI attempt found an unresolved import for `AndroidGpkImageDecoder`; the class lives in `io.github.helterfall.ramen.runtime.nativeaudio`. The import was corrected.
- Follow-up GitHub Actions run [41405096328](https://github.com/HelterFall/Ramen/actions/runs/41405096328) passed `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and debug APK artifact upload.
- CI validates compilation and synthetic unit tests. It does not validate SAF picker behavior, image rendering, or Ogg codec support on a physical Android device.


## 2026-11-02 — Remove fixed game-file size ceilings

- Replaced the ZIP extractor's default 512 MiB per-entry and 1 GiB expanded-total ceilings with per-chunk checks against currently available destination storage.
- Applied the same storage-aware policy to folder imports. Explicit limits remain injectable for tests or deployments that need stricter policies; default production import no longer rejects a game solely because a file crosses 512 MiB.
- Removed the resource inspector's 512 MiB staging-file cap and the Ogg cache materializer's 256 MiB per-resource cap. Both preserve a dynamic free-space reserve while copying.
- Removed the 512 MiB standalone HTML copy limit and the fixed 512 MiB per-resource, 512 MiB total-resource, and encoded-data-URI limits. HTML copying, rewritten HTML, and extracted base64 assets now use the same streaming storage guard.
- Added unit coverage showing that writes above the former 512 MiB threshold are accepted when storage is available, and rejected when they would consume the protected reserve.
- This changes import/storage capacity, not runtime compatibility: a successfully imported game still needs a compatible engine/runtime before it can launch.


## 2026-11-02 — Storage-aware imports, build fix, and file-format catalogue

### Validation
- GitHub Actions run [41412579423](https://github.com/HelterFall/Ramen/actions/runs/41412579423) completed successfully on commit `a57565c64c8fe02bbabf98f4d92c3398a49c21e4`.
- The successful run covers the Android CI workflow after fixing the VTB parser's local byte constant and importing `StorageGuardedOutputStream` into the resource inspector.

### Documentation refresh
- Updated the README with the six supplied sample files and a concise distinction between detected signatures, parsed archive pairs, and the one sample-only XTX observation.
- Updated the format registry to describe the VPK cache's storage-aware streaming accurately; it no longer claims a fixed 256 MiB resource cap.
- Added an explicit list of still-unclassified file roles. No extension or file layout is claimed without a representative byte sample.
- Updated the roadmap to prioritize a full, read-only directory inventory before expanding the parser catalogue.

### Current limits
- The six inspected samples are not a full game-directory inventory.
- The JSON inventory writer exists, but the app does not yet provide a user-facing save/share flow.
- Physical-device image and audio validation remains outstanding.
- The GPK/GTB and VPK/VTB tools preview individual resources; they do not implement the original engine or run a complete game.

### Next step
Create and review a complete read-only inventory report from a representative game folder. Use that report to decide which unknown file roles are important enough to investigate, and keep unverified formats explicitly unknown.


## 2026-11-02 — Per-game inventory report export

### Changed
- Added a `Save report (JSON)` action to each imported game card.
- The action uses Android's Create Document picker so the user chooses the destination.
- The inventory scan and JSON writing run on the existing background executor, not on the UI thread.
- The exported report contains paths, sizes, SHA-256 values where computed, recognized signatures/PE metadata, and explicit warnings. It does not contain game file payloads.
- The game directory is canonicalized and checked to remain inside the app's private `games` directory before scanning.
- The existing scanner bounds remain in force: 10,000 files, depth 64, and 8 GiB of total input hashed by default. A truncated scan and missing hashes are reported instead of being presented as complete.

### Limits
- This is a one-off diagnostic export, not a full analysis screen or runtime feature.
- Physical-device testing is still needed for the Android document picker and large-directory behavior.
