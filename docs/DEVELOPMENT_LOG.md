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


## 2026-11-04 — Preserve System-NNN SPT sample observations

### Source and project-status clarification
- The user clarified that GPK/GTB and VPK/VTB are already readable in the current project. Treat those resource readers as implemented for the user's current sample set; do not describe them as wholly unimplemented. Exact variant coverage remains unspecified.
- Added [System-NNN SPT Format Notes](SYSTEM_NNN_SPT_FORMAT_NOTES.md) from the supplied `hu.txt` analysis script and `ho.txt` output.

### Recorded SPT observations
- Sample output reports 389,500 bytes (`0x5F17C`), an XOR-`0xFF`-demasked `SPTHEADER0` signature at `0x30), and first DWORD `0x14`.
- Candidate count/target pairs: 1299 at `0xC5BA`, 1124 at `0x167F2`, 69 at `0xC390`, and 2 at offset zero.
- A printable Shift-JIS run was found at `0x32B64` (`0x32BC9` end, 101 bytes, strict Shift-JIS decode succeeds).
- The byte marker `text\0` was found at `0x32B34`, `0x32B3C`, `0x32B44`, `0x32B4C`, `0x32B54`, and `0x32B5C`.
- Five DWORDs printed at `0x32B20`: `0x16752`, `0x16791`, `0x167A3`, `0x167C6`, `0x167D9`.

### Evidence limits and handoff
- The uploaded materials are the analysis script and its textual output, not the original SPT binary. Preserve the values as reported observations, not independently re-parsed binary facts.
- The 1299-entry descriptor is a strong lead, but the output does not prove UInt32 entry width or the current decoder's resource/dialogue classification rule.
- The five DWORDs and six `text\0` markers at `0x32B20`–`0x32B5C` need structural interpretation.
- The first Shift-JIS run does not prove that every NUL-delimited string from `0x32B64` to EOF belongs to one text pool.
- Reconcile these observations with `SystemNnnScriptLoader` and `SystemNnnCommandDecoderImpl` before claiming the SPT format is fully decoded.

### Next steps
1. Inspect bounded byte windows around `0xC390`, `0xC5BA`, `0x16752`, `0x167F2`, `0x32B20`, and `0x32B64` from the original SPT.
2. Validate the main-table entry width and target semantics against actual resources and in-game scene order.
3. Determine the roles of the 1124-entry and 69-entry descriptors, the marker records, and the DWORDs at `0x32B20`.
