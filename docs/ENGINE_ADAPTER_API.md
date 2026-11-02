# Engine Adapter API

## Purpose

The API creates a controlled boundary between imported game files and engine-specific format research. It is a discovery/inspection contract, not a claim that a proprietary runtime is already supported.

## Read-only source

`EngineSource` provides:

- a stable app-side source ID and display name;
- a deterministic list of `EngineSourceEntry` values (relative path and size);
- caller-owned `InputStream` access for sequential readers;
- caller-owned `ReadOnlyRandomAccessSource` access for bounded binary parsers;
- truncation and warning metadata.

`DirectoryGameSource` is the first implementation. It is intended for an already imported app-private directory, not arbitrary filesystem paths. It captures the file list at construction, sorts paths, omits symbolic links, verifies canonical containment, and limits file count, recursion depth, and visited nodes. Files added after the snapshot are invisible. Files that disappear or change size are rejected when opened.

Adapters must use only the supplied relative paths and read-only access methods. A stream or positional source returned by the source belongs to the caller and must be closed.

## Probe contract

Each `EngineAdapter` has a stable descriptor and implements `probe(source)`.

The result is one of:

- `NoMatch`: the inspected evidence does not identify this adapter's family.
- `Match`: a candidate family/format matched, with confidence, optional version, and explicit evidence.
- `Malformed`: a relevant candidate was found but its structure is incomplete or invalid. This must not be silently converted into `NoMatch`.

Evidence may name a source-relative file. Extension-only evidence should be described as such and must not be reported as a high-confidence format match. `EngineEvidence.resourceType` can carry a stable typed resource identifier through the registry.

## Registry behavior

`EngineAdapterRegistry` runs each registered adapter and returns all matches plus malformed candidates.

- Exactly one match is exposed as `uniqueMatch`.
- Two or more matches are reported as ambiguous.
- The registry does not choose the highest confidence as an implicit winner. A version-specific discriminator must be implemented and tested inside the relevant adapter before ambiguity can be resolved.
- Malformed results remain visible even when another adapter matched.
- Adapters are evaluated in stable ID order, so diagnostics remain deterministic.
- An `IOException` from one probe is converted to a malformed result for that adapter; other adapters still run. Unexpected programming/runtime exceptions are not swallowed.

Adapter IDs must be unique and stable.

## What is deliberately not defined yet

This API does not define a common game runtime/session, universal script command set, render-frame schema, graphics/audio pipeline, or save format. Those are engine semantics, not generic file-access details. Defining them before the target format and command behavior are corroborated would risk repeating the mistake of treating the Ramen Scenario DSL as the target engine.

Once a specific engine family/version is identified, the next design step is to document its source scripts, command decoding, control flow, text encoding, resource resolution, rendering/audio behavior, save compatibility, and lifecycle. Only then should a runtime-session contract be added.

## Current project status

- The neutral Scenario DSL remains an independent test fixture.
- GPK/GTB image and VPK/VTB audio parsers remain read-only archive tools.
- No proprietary target-engine adapter is registered.
- No SPT/XTX/FXF parser or target-engine launcher is implemented.
- The community VNTools catalogue is a research lead, not authoritative proof of the sample's engine or version.

## Tests

The synthetic tests cover:

- deterministic file snapshot ordering;
- stream and positional reads;
- traversal rejection;
- source changes and newly added files;
- adapter ID uniqueness;
- no-match, unique-match, malformed, and ambiguous registry outcomes;
- isolated I/O failure and stable adapter ordering.


## BinarySignatureProbe — synthetic fixture adapter

The first concrete adapter is `BinarySignatureProbe`. It reads exactly eight bytes from each candidate entry using `EngineSource.openRandomAccess`; it does not consult the extension or filename.

| Exact first 8 bytes | Resource type |
|---|---|
| `EB FF FF FF FE FF 99 99` | `script` |
| ASCII `IF PACKT` | `resource-container` |
| ASCII `JPEG    ` | `graphic-archive` |
| ASCII `BMP     ` | `graphic-archive` |

The type is exposed on `EngineEvidence.resourceType` and is carried through the registry to `EngineProbeMatch.evidence`.

Calling `probeFile(source, path)` returns `Malformed` for a file shorter than eight bytes, an unrecognized 8-byte header, a missing entry, or an I/O read failure. Once a known Script or IF PACKT marker is present, it returns `Match` and reports whether the synthetic body parser also validated the structure. A directory-level `probe(source)` scans the whole bounded source snapshot. It aggregates all recognized markers into one `Match`; unrelated or malformed files are retained as diagnostic evidence so they cannot hide valid resources. If the non-empty source contains no recognized marker, the aggregate result is `Malformed`. An empty source is an ordinary `NoMatch`.

These four signatures are the custom Ramen test-fixture specification only. In particular, the script prefix is not a decoded SPT format and does not prove compatibility with the inventoried game's script engine. Structural validation remains separate: a recognized marker can be Detected even when the Ramen-owned Script v1 or ResourceContainer v1 parser does not understand the body. Such a result is not launchable and does not decode or execute proprietary content.


## PackArchiveReader — Ramen test ResourceContainer v1

`BinarySignatureProbe` treats the eight-byte `IF PACKT` prefix as marker-level discovery evidence, then passes the same positional source to `PackArchiveReader.openArchive()`. A bad version or index is recorded in the match evidence as a body-validation failure; it does not erase the recognized marker or make the container launchable.

`PackArchiveReader` reads the 20-byte fixed header and at most the configured index limit (16 MiB by default, 64 MiB absolute ceiling). It validates record count, strict UTF-8 paths, path traversal, duplicate names, unsigned offset/size values, payload bounds, and overlapping ranges. It retains only the parsed index in memory.

- `openArchive()` returns the version and indexed `PackArchiveEntry(path, offset, size)` rows.
- `openFile(path)` returns a bounded `InputStream` backed by positional reads; closing it does not close the caller-owned source.
- `extractFile(path)` returns a `ByteArray` for resources up to 64 MiB by default. Larger resources must use `openFile()`.
- The reader is read-only and never extracts a whole archive to disk. No compression codec is specified by v1, so payload bytes are returned unchanged.

The complete byte layout is documented in [PACK_ARCHIVE_FORMAT.md](PACK_ARCHIVE_FORMAT.md). This format is a Ramen synthetic test fixture and is separate from the observed GPK/GTB and VPK/VTB archive parsers.


## ScriptCommandParser — Ramen synthetic Script v1

The eight-byte Script magic is only the first field of the synthetic test format. ScriptScenarioLoader validates the remaining version/encoding header, then uses ScriptCommandParser.parseNextCommand(stream) to decode one command at a time:

- 0x01: DisplayDialogueCommand(text);
- 0x02: ChangeBackgroundCommand(imagePath);
- 0x03: JumpToSceneCommand(targetAddress).

String operands are length-prefixed and decoded strictly using the header-selected UTF-8 or Shift-JIS charset. Jump addresses are absolute byte offsets from the start of the Script file and must point to a decoded command boundary. The loader maps instruction addresses to stable addr_<offset> scene IDs and translates the commands into the existing ScenarioProject model, which is executable by ScenarioSession.

EOF between commands is a normal end of stream. EOF inside an opcode operand or string payload, an unknown opcode, invalid text, an unsafe background path, or an unresolved jump address is malformed. BinarySignatureProbe runs this structural loader after matching the magic and includes its result in the evidence. If the Ramen test parser rejects the body, marker-level detection remains `Match`, but the file is not eligible for the experimental Script v1 player.

The exact synthetic v1 layout, limits, and opcode numbers are in docs/SCRIPT_COMMAND_FORMAT.md. These values are Ramen-owned test-fixture decisions; they are not inferred from the inventoried proprietary script files.
