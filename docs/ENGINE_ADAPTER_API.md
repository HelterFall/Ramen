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

Evidence may name a source-relative file. Extension-only evidence should be described as such and must not be reported as a high-confidence format match.

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
