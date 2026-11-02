# Proposed Architecture

This document describes boundaries and responsibilities. It is not a commitment to a particular native-engine embedding technique before a prototype proves it works.

## High-level layers

```text
Compose UI
   |
Application layer
   |-- Library use cases
   |-- Import use cases
   |-- Launch coordination
   |
Core
   |-- Game model
   |-- Source abstraction
   |-- File index / inspection
   |-- Binary artifact inspector
   |-- Container format registry
   |-- Engine registry
   |-- Runtime contracts
   |
Adapters
   |-- HTML5 / WebView runtime
   |-- Future native-engine adapters
   |
Platform
   |-- Storage Access Framework
   |-- Room
   |-- Android lifecycle
   |-- App-private storage
```

## Suggested module layout

Start with a conventional Android project. Split Gradle modules only when a boundary becomes useful; avoid creating empty modules just to match a diagram.

Potential future modules:

- `app`: Android entry point, navigation, Compose screens, dependency wiring.
- `core:model`: game metadata, source references, engine IDs, compatibility states.
- `core:import`: folder/archive inspection and safe extraction.
- `core:inspection`: bounded read-only binary inspection and evidence-aware format registry.
- `core:runtime-api`: contracts shared by the runtime adapters.
- `data:library`: Room entities, DAOs, and repositories.
- `runtime:html5`: WebView-based HTML5 player.
- `runtime:<engine>`: a future adapter only after feasibility and licence review.

## Import and source model

Do not make a raw filesystem path the permanent identity of a game. Android may provide a content URI or a persisted document-tree permission.

Represent a source through an abstraction that can:
- expose a stable app-side ID;
- enumerate allowed entries;
- open a read-only stream for an entry;
- report whether access is still available;
- optionally refer to an app-private imported copy.

Use the Storage Access Framework for user-selected folders and files. Consider copying supported archives into app-private storage only after validating the import and explaining storage usage.

## Inspection pipeline

1. Receive a user-selected source.
2. Enumerate entries with limits on entry count, nesting, and total uncompressed size.
3. Normalize relative paths and reject traversal, absolute paths, and unsafe archive entries.
4. Build a lightweight file index.
5. Run engine detectors against known marker files and directory patterns.
6. Return a detection result with engine ID, confidence, and evidence.
7. Ask the engine adapter to inspect metadata only when its format is understood.
8. Determine launchability separately from detection.

A detector should be deterministic and testable against small fixture directories.

### Binary artifact inspection

Binary inspection is a separate read-only step after safe import. It records byte signatures and bounded executable metadata without launching the file. The current implementation recognizes common PE/ELF, ZIP, RIFF, Ogg, PNG, and JPEG signatures and reads a small subset of PE headers. It does not parse proprietary containers or execute binaries.

Keep three concepts separate:
- **Observed:** a filename, extension, or directory pattern seen in a sample.
- **Identified:** a byte signature or validated header matches a known format.
- **Parsed:** a bounded parser has validated structural fields against fixtures.

An extension must never be promoted to an identified format by itself. Candidate archive parsers should use a read-only random-access source, check offset arithmetic and length bounds, and return explicit `MATCH`, `NO_MATCH`, or `MALFORMED` results.

## Runtime boundary

An engine adapter should describe:
- engine identifier and human-readable name;
- version / format information it can recognize;
- detection and inspection behavior;
- required files and known limitations;
- launch strategy and lifecycle capabilities;
- save-data location and ownership rules.

A runtime may need an Android Activity, a dedicated View, a native rendering surface, or a separate process. The core must not assume every engine can be launched by a single synchronous `launch(path)` call.

Keep engine-specific lifecycle and rendering behind the adapter. The application layer coordinates launch, pause, resume, stop, orientation, and return-to-library behavior.

For the first HTML5 prototype, evaluate a WebView-backed runtime. Do not add a JavaScript-to-native bridge unless a concrete feature requires it and its input boundary can be tightly restricted.

## HTML5 runtime security

HTML5 games need JavaScript, but imported games are untrusted code.

- Do not use broad `file://` access as a shortcut.
- Evaluate an app-controlled local origin using WebViewAssetLoader or a carefully scoped content-serving layer.
- Keep file and content access disabled unless a tested requirement makes one necessary.
- Do not expose privileged Android methods to game JavaScript.
- Validate navigation and external URLs; ask before leaving the local game.
- Decide explicitly whether network access is disabled by default.
- Clear or isolate WebView storage between games where appropriate.
- Test malformed content and unexpected navigation.

Security references:
- Android WebView unsafe file inclusion: https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion
- Android WebView JavaScript interface guidance: https://support.google.com/faqs/answer/9095419

## Persistence

Room stores app-owned information:
- game ID and title;
- source reference / imported-copy location;
- detected engine and detector evidence;
- user-selected cover and presentation settings;
- last opened time and app-level preferences.

Game saves should remain in an engine-specific area. Do not assume save files are portable between engine versions.

## Error model

Prefer actionable categories:
- source permission revoked;
- archive invalid or rejected;
- engine unknown;
- engine recognized but no runtime available;
- missing required files;
- runtime failed to initialize;
- game content failed during execution;
- save data unavailable or incompatible.

Do not hide engine errors behind a generic "unsupported format" message.


## Scenario runtime prototype

The experimental engine-neutral scenario runtime is deliberately split into four layers:

1. Parser — converts the documented Ramen DSL into a validated ScenarioProject. It rejects unknown commands, invalid scene references, duplicate IDs, traversal-like resource keys, and inputs beyond configured bounds.
2. Interpreter — ScenarioSession executes commands and exposes immutable ScenarioFrame snapshots. Dialogue and grouped choices yield control to the UI. Conditional and unconditional jumps stay inside the validated project. A per-yield command budget prevents non-yielding automatic-jump cycles.
3. Renderer — the current demo Activity renders dialogue and choices with Compose. Background and character identifiers are currently visible placeholders, not resolved images.
4. Persistence — ScenarioSaveCodec encodes a versioned snapshot; the bundled demo writes a local save file. A future production runtime should add save slots, atomic replacement, migration policy, and lifecycle tests.

The DSL is Ramen's own test format. It is not evidence of compatibility with any external engine. A future adapter must translate an independently understood source format into the common runtime model or implement its own interpreter behind the runtime boundary. Resource resolution and audio are separate interfaces and are not implemented by this first demo.

## Target-engine adapter boundary

The current Scenario DSL is an engine-neutral test fixture only. It must not become the assumed source format for unrelated games. A future adapter for a particular engine family needs independently validated implementations or mappings for:
- source-file identification and loading;
- script records, command decoding, and control-flow semantics;
- resource identifiers and archive lookup;
- text encoding, layout, and font behavior;
- graphics composition and transitions;
- music, voice, and sound-effect playback;
- game variables, save/load state, and version compatibility;
- pause/resume, orientation, input, and Android lifecycle.

The shared runtime model may be reused where semantics genuinely match, but a target adapter must preserve engine-specific behavior rather than silently translating unknown commands into the demo DSL. Compatibility is established only by reproducible end-to-end tests against an appropriate original or licensed fixture.

### Implemented adapter-probe boundary

The prototype API currently lives in `runtime/engine`:

- `EngineSource` exposes a bounded immutable entry snapshot plus caller-owned read streams and positional read-only sources.
- `DirectoryGameSource` implements that contract for an imported directory. It sorts entries, excludes symbolic links, checks canonical containment, rejects path traversal, enforces file/depth/node limits, and detects files changed after the snapshot.
- `EngineAdapter` is a format-family probe contract. Results distinguish `NoMatch`, `Match` with evidence/confidence, and `Malformed` with a reason.
- `EngineAdapterRegistry` aggregates independent probes. It selects a result only when exactly one adapter matches; multiple matches remain ambiguous even when their confidence differs.

This is the discovery/inspection part of the adapter boundary, not a game launcher. No adapter for the inventoried proprietary sample is registered. The API deliberately does not define a universal runtime frame, command vocabulary, renderer, or save format yet: those contracts must follow evidence about the actual engine rather than being copied from the Ramen demo DSL. See [Engine Adapter API](ENGINE_ADAPTER_API.md).


### First binary resource probe

`BinarySignatureProbe` is the first concrete adapter in `runtime/engine`. It uses positional reads to inspect exactly eight bytes and returns one of the synthetic fixture resource types through `EngineEvidence.resourceType`. Individual files with short or unknown headers return `Malformed`; whole-directory inspection collects recognized resources and keeps other entries as diagnostics. It does not use extensions, decode payloads, or interpret proprietary game scripts.
