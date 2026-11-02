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
