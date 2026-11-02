# Roadmap

The roadmap is milestone-based. Dates are intentionally omitted until the first prototype reveals the actual scope.

## Current research track — Native and proprietary engines

The current direction is to build reusable runtime foundations from confirmed evidence, without guessing proprietary formats from extensions. A complete metadata-only inventory has been captured for one representative directory; the next step is an original, age-appropriate scenario fixture and a small engine-neutral interpreter. See [Engine Research](ENGINE_RESEARCH.md), [Format Registry](FORMAT_REGISTRY.md), and the chronological [Development Log](DEVELOPMENT_LOG.md).

### M1.5 — Binary inspection foundation
- [x] Add bounded, read-only PE header inspection.
- [x] Recognize initial common signatures (PE, ELF, ZIP, RIFF, Ogg, PNG, JPEG).
- [x] Separate verified format signatures from extension-only observations.
- [x] Add tests and a durable evidence / handoff log.
- [x] Parse standard PE32/PE32+ import tables and report imported DLL names.
- [x] Add streaming SHA-256 file fingerprints.
- [x] Add a generic read-only positional source abstraction with a FileChannel implementation.
- [x] Add container-probe contracts with `MATCH`, `NO_MATCH`, and `MALFORMED` outcomes.
- [x] Create synthetic fixtures for PE import headers, random-access boundaries, hashes, and signature probes.
- [x] Add bounded read-only parsers for the observed GPK/GTB image and VPK/VTB audio archive pairs, with synthetic malformed-input tests.

**Exit condition:** Ramen can produce reproducible, read-only executable metadata, streaming file fingerprints, and explicit format-probe evidence, while unknown proprietary formats remain explicitly unknown.

### Remaining M1.5 integration work
- [x] Add a bounded directory inventory combining relative paths, file sizes, SHA-256 fingerprints, and binary inspection reports.
- [x] Connect the random-access source to the PE binary inspector.
- [x] Add streaming, versioned JSON serialization for inventory reports.
- [x] Add a per-game user-facing JSON report save flow through the Android document picker.
- [x] Produce a complete read-only inventory of a representative game folder and preserve the metadata-only JSON report; unknown formats remain explicitly unclassified.
- [x] Add bounded fixed-buffer streaming for individual resource payload ranges returned by the proprietary archive parsers.
- [x] Add a bounded BitmapFactory adapter for GPK images and storage-aware Ogg cache materialization without a fixed per-resource size cap.
- [x] Add a resource inspector screen for browsing GPK/GTB assets and previewing images.
- [x] Add a basic foreground MediaPlayer flow for selected Ogg resources with play/pause/stop controls.
- [ ] Validate image rendering and Ogg playback on physical Android devices and supported OS versions.
- [ ] Add a positional source implementation for Android Storage Access Framework documents where supported.

## M1.6 — Runtime foundation using an original test project
- [x] Define a small, documented, engine-neutral scenario model.
- [x] Add an original, age-appropriate fixture with scenes, dialogue, choices, branches, and resource keys.
- [x] Implement a bounded parser and interpreter with explicit commands and automatic-transition limits.
- [x] Add a versioned save-state codec and local save/load controls in the demo Activity.
- [x] Add deterministic regression tests for parsing, branching, save/restore, and automatic-transition cycle limits.
- [x] Make the playable demo accessible from the main library screen.
- [x] Define the Ramen test ResourceContainer v1 layout and add a bounded read-only PackArchiveReader with lazy positional payload reads.
- [x] Validate ResourceContainer indexes from BinarySignatureProbe and report malformed structures through the adapter probe result.
- [ ] Add a unified ResourceManager/provider that can resolve resources across the test pack and the existing GPK/GTB and VPK/VTB readers.
- [ ] Replace placeholder scene panels with a resource provider for local images and character art.
- [ ] Add audio cue commands and a resource-backed audio adapter.
- [ ] Validate Android lifecycle, layout, and save/restore on physical devices.

**Exit condition:** an original fixture can be launched, completed through multiple branches, saved, and resumed with real local image/audio resources on Android. The current milestone is an executable prototype with placeholder visuals.

## M1.7 — Target-engine adapter research
- [x] Define a bounded, read-only EngineSource contract for imported files, with stream and positional access.
- [x] Add a directory-backed source with deterministic snapshots, traversal/symlink rejection, and file/depth/node bounds.
- [x] Add an EngineAdapter probe contract and registry that preserves malformed and ambiguous outcomes without arbitrary priority.
- [x] Add a byte-only BinarySignatureProbe for the exact Ramen test-fixture headers, with typed resource evidence and malformed-header tests.
- [ ] Confirm the likely engine family/version from corroborating executable and file-format evidence.
- [ ] Implement a target-family probe only after its markers and version discriminators are validated.
- [ ] Specify runtime session, command, rendering/audio, save-state, and lifecycle contracts from the confirmed engine semantics.
- [ ] Assess technical and distribution/licensing feasibility before target-specific implementation.
- [x] Keep the engine-neutral demo DSL explicitly separate from the sample engine's source formats.

**Exit condition:** a versioned adapter specification and end-to-end test plan exist before target-specific compatibility is claimed.

## M0 — Project foundation
- [ ] Agree on product scope and Android-first target.
- [ ] Choose a project licence after considering intended distribution and future runtime licences.
- [ ] Create the Android Studio / Gradle project.
- [ ] Add formatting, lint, and CI build checks.
- [ ] Add an original, tiny HTML5 fixture.

**Exit condition:** a clean checkout builds an empty Android app in CI.

## M1 — Import and identification
- [ ] Add folder and file selection through the Storage Access Framework.
- [ ] Implement read-only source abstraction.
- [ ] Add safe ZIP inspection / extraction.
- [ ] Build a deterministic file index.
- [ ] Add detector results with evidence and confidence.
- [ ] Show supported / recognized-but-unsupported / unknown states.

**Exit condition:** users can import fixtures and see accurate engine detection without launching them.

## M2 — First playable runtime
- [ ] Prototype the HTML5 renderer.
- [ ] Serve imported assets through a scoped local origin.
- [ ] Establish navigation and network policy.
- [ ] Test audio, orientation, viewport, and lifecycle.
- [ ] Verify save/load for the selected fixture.

**Exit condition:** at least one original HTML5 test project launches and passes the defined test checklist.

## M3 — Library experience
- [ ] Build the Compose library screen.
- [ ] Persist metadata with Room.
- [ ] Add search, sort, and remove.
- [ ] Add clear runtime errors and a return-to-library flow.

**Exit condition:** the core import-to-play journey works after app restarts.

## M4 — Compatibility expansion
- [ ] Review HTML5 test coverage and limitations.
- [ ] Evaluate TyranoScript projects.
- [ ] Produce a Ren'Py integration feasibility report.
- [ ] Produce a KiriKiri integration feasibility and licence report.
- [ ] Research native/proprietary engine families using the binary inspection foundation.
- [ ] Select each runtime target based on format evidence, technical feasibility, maintenance, and licence fit.

**Exit condition:** the next engine has a documented technical plan before implementation begins.

## M5 — Quality and release preparation
- [ ] Add automated importer and detector tests.
- [ ] Add runtime regression fixtures.
- [ ] Test low-memory devices and app lifecycle.
- [ ] Review privacy, storage, and network behavior.
- [ ] Complete third-party notices and licence compliance.
- [ ] Prepare release notes and supported-game documentation.

**Exit condition:** every advertised feature has a reproducible test and documented limitations.
