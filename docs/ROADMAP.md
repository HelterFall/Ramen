# Roadmap

The roadmap is milestone-based. Dates are intentionally omitted until the first prototype reveals the actual scope.

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
- [ ] Select the next engine based on demand, feasibility, maintenance, and licence fit.

**Exit condition:** the next engine has a documented technical plan before implementation begins.

## M5 — Quality and release preparation
- [ ] Add automated importer and detector tests.
- [ ] Add runtime regression fixtures.
- [ ] Test low-memory devices and app lifecycle.
- [ ] Review privacy, storage, and network behavior.
- [ ] Complete third-party notices and licence compliance.
- [ ] Prepare release notes and supported-game documentation.

**Exit condition:** every advertised feature has a reproducible test and documented limitations.
