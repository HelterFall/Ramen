# Engine Research Track

**Status:** active research track, not a compatibility claim  
**Started:** 2026-11-02

## Goal

Move Ramen toward difficult native and proprietary visual-novel engines by first building reusable inspection infrastructure. The current step is not to launch an unknown Windows executable. It is to establish a repeatable, read-only way to inventory executable metadata, identify known binary signatures, record unknown container candidates, and preserve evidence for later format research.

The work must remain engine-agnostic until the evidence supports a specific adapter.

## What has been implemented

- `BinaryArtifactInspector` performs bounded, read-only inspection of common binary signatures.
- PE inspection reads the DOS header's PE offset, validates the PE signature, and reports the COFF machine type, optional-header kind, section count, and DLL flag. A bounded PE32/PE32+ import-directory parser also reports imported DLL names.
- Known signatures include PE, ELF, ZIP, RIFF, Ogg, PNG, and JPEG.
- `ContainerFormatRegistry` separates verified signature-backed formats from extension-only observations.
- Unit tests cover PE32/x86, PE32+/ARM64 DLL metadata, PE32/PE32+ import tables, invalid import RVAs, ZIP/Ogg signatures, unknown extensions, malformed MZ input, and evidence-level separation.
- Added `ReadOnlyRandomAccessSource` with a positional `FileChannel` implementation.
- Added incremental SHA-256 fingerprinting with a fixed-size buffer.
- Added a container-probe contract with explicit `MATCH`, `NO_MATCH`, and `MALFORMED` results and a fixed-signature probe.

## Evidence from the initial directory listing

The user supplied screenshots of a game-data directory. The visible names included:

- Directories such as `init`, `dwq`, `cdvaw`, `wgq`, `vaw`, and `spt`.
- Files with paired-looking extensions `.gpk/.gtb` and `.vpk/.vtb`.
- Multiple `.xtx` files under an initialization directory.
- An executable named `gss.exe` and libraries including `ddraw.dll`, `ogg.dll`, and `vorbis.dll`.

These are observations from a directory listing only. No file bytes, PE import table, archive directory, index, compression method, or format header have been examined. The apparent pairs may be related, but that is not established. DLL names are clues about dependencies, not proof of the engine architecture or rendering path.

The repository therefore records the extension families as **unverified observations**, not as decoded or supported formats.

## Research workflow

1. **Inventory without execution.** Record filenames, sizes, directory relationships, hashes, and byte signatures. Do not launch imported executables during inspection.
2. **Inspect executable metadata.** Identify PE/ELF architecture and headers. The bounded PE import-directory reader is implemented; version-resource metadata, delay-load imports, and a transitive dependency graph remain future work.
3. **Identify containers by bytes.** Record magic bytes, header fields, endianness, declared lengths, index offsets, compression markers, and checksums only when visible and validated.
4. **Model relationships as hypotheses.** Keep observations separate from confirmed structure. A pair of extensions does not prove an index/data relationship.
5. **Build neutral fixtures.** Use tiny original or appropriately licensed sample files to test parsers and malformed-input handling.
6. **Specify an adapter only after the format is understood.** Runtime work begins after there is enough evidence to describe script execution, asset access, rendering, audio, save state, and lifecycle requirements.

## Current boundaries

- Inspection is read-only and never executes a selected binary.
- File extensions are hints, not format detection.
- Unknown data remains unknown; the tool must not guess a proprietary format from a filename.
- Parser work must be bounded against malformed lengths, offsets, nesting, and allocation sizes.
- No commercial game files or private user data are committed as fixtures.
- A detected file format does not imply that Ramen can run the associated game.

## Next technical steps

1. Extend PE inspection with a bounded import-directory parser and report imported DLL names.
2. Add SHA-256 and a stable per-file inventory record, computed incrementally.
3. Add a generic read-only random-access abstraction so later parsers do not depend directly on `File`.
4. Add a container-probe interface with explicit parse outcomes: `MATCH`, `NO_MATCH`, and `MALFORMED`.
5. Add tiny synthetic fixtures for verified signatures and malformed headers.
6. Only then investigate proprietary archive candidates with byte-level samples and a written format hypothesis.

## Decision log

- 2026-11-02: Prioritize difficult native/proprietary engine research rather than treating Ren'Py as the only next runtime.
- 2026-11-02: Start with executable and container inspection, not execution or emulation.
- 2026-11-02: Record the visible `.gpk/.gtb`, `.vpk/.vtb`, and `.xtx` families as unverified observations until their bytes have been inspected.
