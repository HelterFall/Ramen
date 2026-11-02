# Roadmap

The roadmap is milestone-based. Dates are intentionally omitted until the first prototype reveals the actual scope.

## Current research track — Native and proprietary engines

The current direction is to build reusable inspection infrastructure before selecting or promising another native runtime. The immediate research task is to inventory the complete game folder and classify unknown file roles, not to add parsers based on extensions alone. See [Engine Research](ENGINE_RESEARCH.md), [Format Registry](FORMAT_REGISTRY.md), and the chronological [Development Log](DEVELOPMENT_LOG.md).

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
- [ ] Add a user-facing save/share flow for inspection results.
- [ ] Produce a complete read-only inventory of a representative game folder and document all observed extension/signature families, leaving unknown formats explicitly unclassified.
- [x] Add bounded fixed-buffer streaming for individual resource payload ranges returned by the proprietary archive parsers.
- [x] Add a bounded BitmapFactory adapter for GPK images and storage-aware Ogg cache materialization without a fixed per-resource size cap.
- [x] Add a resource inspector screen for browsing GPK/GTB assets and previewing images.
- [x] Add a basic foreground MediaPlayer flow for selected Ogg resources with play/pause/stop controls.
- [ ] Validate image rendering and Ogg playback on physical Android devices and supported OS versions.
- [ ] Add a positional source implementation for Android Storage Access Framework documents where supported.

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
