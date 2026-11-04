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
- Sample output reports 389,500 bytes (`0x5F17C`), an XOR-`0xFF`-demasked `SPTHEADER0` signature at `0x30`, and first DWORD `0x14`.
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


## 2026-11-04 — Canonical handoff and import storage-guard CI fix

### Project handoff
- Added root [HANDOFF.md](../HANDOFF.md) as the single entry point for new sessions. It contains repository map, architecture, feature statuses, branch/PR topology, CI findings, SPT observations, open questions, and continuation priorities.
- Updated root README to point to HANDOFF first and clarify that System-NNN is in an unmerged prototype PR.

### CI failure diagnosis and resolution
- Historical PR #27 run #230 failed `HtmlInlineResourceExtractorTest.leavesUnsupportedDataUrisUntouched` with IOException at the extractor call.
- The temporary rewritten HTML file is written inside `gameDirectory`, but the older code passed `StorageSpaceGuard(assetsDirectory)` for that output stream.
- For an unsupported data URI, `assetsDirectory` is not created. Its `usableSpace` check returned zero, so the guard rejected the temporary HTML rewrite.
- The current code uses `StorageSpaceGuard(gameDirectory)` for the rewrite file. The separate extracted payload stream still uses `StorageSpaceGuard(assetsDirectory)`, after that directory is created.
- Later CI run #265 [41671391772](https://github.com/HelterFall/Ramen/actions/runs/41671391772) passed build, unit tests, lint, and debug APK upload.
- Run #265 tested code snapshot `648c4d6052dcd3a95d0ca9f14c210392c33eed70`. Subsequent changes to the branch in this session were documentation-only (`HANDOFF.md`, `README.md`, this log).

### Next step
- Recheck Actions after any code change. Do not treat a documentation-only commit as proof of a new code test; record the exact tested commit SHA.


## 2026-11-04 — Build inclusion audit and current Actions verification

### Branch contents confirmed from source
- The checked main head (305f3f25...) has no ResourceInspectorActivity registration or Resource Inspector shortcut, and no System-NNN player Activity / launch path.
- PR #25 branch has ResourceInspectorActivity registered and a global shortcut; it does not register SystemNnnPlayerActivity. Its Script v1 player is a synthetic fixture path, not the proprietary System-NNN player.
- PR #26 branch has the Resource Inspector and Black Cyc resource preview, but no SystemNnnPlayerActivity. Its SPT boundary remains opaque rather than a decoded game runtime.
- PR #27 registers ResourceInspectorActivity and SystemNnnPlayerActivity. The Inspector shortcut is always rendered on the home screen, and the per-game resource-inspection action opens the Inspector. The System-NNN launch path is wired when detection returns system-nnn-spt / PARSER_AVAILABLE.
- Therefore the PR #27 debug APK includes and visibly exposes both Resource Inspector and experimental System-NNN player. No debug-only gate/product flavor currently excludes them.

### Decoder wording / evidence warning
- UI wording in GameCard says System-NNN launch with text/resource pointer decoding is available.
- Detector evidence text still says the playback interpreter is not connected yet.
- The current decoder compares table value with gtbResourceCount, then reads text entries sequentially. This differs from the PR description's value >= 0x32B64 dialogue-offset rule. It does not yet prove correct absolute text pointer resolution.
- The decoder does not currently implement verified choice/branch/audio commands; audio lookup uses sequential dialogueStepIndex. Treat this as provisional, not a fully decoded SPT VM.
- Full details are now in [BUILD_MATRIX.md](BUILD_MATRIX.md).

### Actions comparison
- PR #25 run #219: success on branch head b388d449..., debug APK artifact uploaded.
- PR #26 run #224: success on branch head e6daf72f..., debug APK artifact uploaded.
- PR #27 run #265: success on intermediate code snapshot 648c4d605...
- PR #27 run #270: success on exact PR head 4a400535562667d5720f17beef3aa16d46b10def. The job completed assembleDebug, testDebugUnitTest, lintDebug, and debug APK upload.
- Run #270 checked synthetic merge commit 247ad61 merging PR head 4a400535... into main 305f3f25...
- Artifact: ramen-debug-apk, ID 13250265749, 11,642,988 bytes, SHA-256 616d1c121cb966627f3dcc39efb45429c1047e068e3eb988c1ce671362096615, expires 2026-11-10.
- The five commits between the previously validated code snapshot #265 and #270 changed documentation only (HANDOFF.md, README.md, DEVELOPMENT_LOG.md); no app or test source changed.

### CI delivery boundary
- Android CI builds only assembleDebug, runs unit tests and lint, and uploads a debug APK with seven-day retention.
- It does not run assembleRelease, perform release signing, or publish to Google Play.
- A green PR check is a build/test result for the synthetic merge ref; it is not device acceptance and does not prove full game compatibility.

### Durable continuation rule
- Before every APK build, record branch, exact commit SHA, build type, Actions URL (if applicable), artifact digest, and intended feature set.
- Use [HANDOFF.md](../HANDOFF.md) and [BUILD_MATRIX.md](BUILD_MATRIX.md) to choose the correct source. Do not assume that main and PR #27 produce equivalent apps.


## 2026-11-04 — Promote the #278-tested source state into main

### Branch consolidation
- The user chose to make the complete source state represented by successful Actions run #278 the new canonical project state, rather than copying the APK artifact.
- PR [#27](https://github.com/HelterFall/Ramen/pull/27), from `feat/system-nnn-spt-loader`, was merged into `main` using a merge commit.
- Merge commit: `026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5`.
- Exact source head tested by run #278: `75675e3302439bc31f5bc1961bc82b1233ec34b7`.
- The merge brings the Resource Inspector, GPK/GTB and VPK/VTB parsers, experimental System-NNN loader/player path, import improvements, unit tests, and handoff documentation into main.
- The Resource Inspector remains included intentionally, at the user's request. No debug-only gate was added.

### CI
- [Run #278](https://github.com/HelterFall/Ramen/actions/runs/41674773614) completed successfully: assembleDebug, testDebugUnitTest, lintDebug, debug APK upload.
- Artifact: `ramen-debug-apk`, ID `13250256957`, 11,642,991 bytes, SHA-256 `3e86f99170c5636246a142e353408f062a078e6309ce5af0196ae65d89edac67`, expires 2026-11-10.
- The push to main started [run #279](https://github.com/HelterFall/Ramen/actions/runs/41676851845) on merge commit `026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5` and completed successfully: assembleDebug, testDebugUnitTest, lintDebug, and debug APK upload all passed.
- Artifact: `ramen-debug-apk`, ID `13251016631`, 11,642,990 bytes, SHA-256 `f4d34352b983b46e583cd776e9dc3825d340632fafcfe28edfc96b2690701f99`, expires 2026-11-10 16:45:07 UTC.

### Branch status after consolidation
- `main` is now the canonical working branch. New feature branches should be created from main.
- `feat/system-nnn-spt-loader` is historical after PR #27 merge.
- PR #25 and #26 remain unmerged and divergent. Their unique changes must be audited against main before any further integration.
- System-NNN remains experimental; the decoder's assumptions and complete game playback are not proven by CI.


## 2026-11-04 — System-NNN audit roadmap and launch tracking

### Goal
Create a durable, evidence-based checklist for taking the experimental System-NNN path from file recognition to a verified Android device run, and preserve a chronological work log across sessions.

### Changed
- Added [System-NNN Launch Roadmap](SYSTEM_NNN_LAUNCH_ROADMAP.md).
- Recorded the checked repository head `302efb471e929b5136ad7a306e6c793c87f52a81` and the known successful post-merge CI run #279.
- Recorded the current evidence limits for SPT interpretation, WGQ image/audio classification, VPK/VTB audio variants, voice mapping, BGM startup, and script selection.
- Added ordered milestones: baseline, resource inventory/classification, SPT structure proof, command semantics, runtime integration, and Android device acceptance.
- Linked the roadmap from the root handoff.

### Validation boundary
- Documentation-only change. No application source or tests were modified.
- No Android build was run for this documentation change.
- This entry does not claim that the target game has launched or that the experimental interpreter is complete.

### Next action
Start with a reproducible inventory of the selected test package. Use actual header bytes to classify WGQ and verify resource pairs before changing the parsers.


## 2026-11-04 — Inventory screenshots and resource-selection bug

### User-provided folder layout
The screenshots explicitly show the root as `Download/Gore Screaming Show/`. The following are sibling folders:
- `dwq/` — 12 objects; visible matching GPK/GTB pairs: bg0, ev0, sc0, sm0, sys0, ta0.
- `cdvaw/` — 166 objects; visible VPK/VTB pairs aa through ff and the beginning of gg.vpk.
- `wgq/` — 111 objects; visible individual .wgq files such as gss01_1.wgq, gss01_2.wgq, gss02_1.wgq, gss02_2.wgq, gss02a_1.wgq, gss02a_2.wgq, etc.
- `spt/` — 30 objects.
- `vaw/` — 2 objects.
- `init/` — 31 objects.
Other root folders shown: `!eng files/`, `movie/`, `nya/`, and `sav/`. Root files shown include gss.exe, ddraw.dll, ogg.dll, vorbis.dll, Neuton.ttf and sjis_ext.bin.
The `dwq`, `cdvaw`, and `wgq` folders are at the same root level. Do not treat them as nested.

### Code re-check against that layout
- `SystemNnnGpkBitmapRepository.forGameDirectory()` checks the root `wgq/` first. If it exists and contains at least one .wgq file, it returns a WGQ-backed image repository (or an indexed GTB/WGQ pair inside that same folder). It only searches the tree for ordinary GPK/GTB if the root `wgq/` folder is absent.
- `ResourceArchiveParsers.findBlackCycWgqFiles()` only filters by the `.wgq` extension; it does not inspect file headers to establish that the candidates are images.
- In the user's layout, `wgq/` exists alongside `dwq/`, where the visible GPK/GTB pairs are located. Thus the current resource-selection logic can bypass the visible GPK/GTB catalog. This is a code-confirmed selection flaw; whether it causes the exact launch failure still requires reading sample headers and collecting the runtime error.
- Current VPK/VTB parser supports one specific 12-byte-index / 64-byte preamble / 44-byte RIFF descriptor / Ogg-at-offset-108 layout. It does not prove coverage of the other audio layouts documented for the family.
- SPT loader and command decoder remain experimental; no new SPT semantics were inferred from folder names.

### What was not changed
- No app code or tests were modified.
- No parser fixes were pushed.
- This is screenshot-based directory inventory and source-code revalidation only.

### Next user input
Open `Download/Gore Screaming Show/spt/` and send a screenshot of the filenames and sizes. The directory contains 30 objects, and we need to identify the candidate scripts before altering the SPT loader or choosing the startup script.


## 2026-11-04 — SPT directory screenshot inventory

User screenshots show the path `Download/Gore Screaming Show/spt/` in the breadcrumb on all three images.

Visible SPT files and sizes:
`_staff.spt` 5.88 KB; `01.spt` 390 KB; `02.spt` 327 KB; `03.spt` 812 KB; `04.spt` 268 KB; `05.spt` 237 KB; `06.spt` 52.37 KB; `07.spt` 245 KB; `08.spt` 90.73 KB; `082.spt` 449 KB; `akane.spt` 1.54 MB; `aoi.spt` 1.30 MB; `backspt.spt` 147 KB; `day.spt` 23.49 KB; `kiika.spt` 1.52 MB; `op1.spt` 90.12 KB; `sys.spt` 1.88 KB; `yamiko.spt` 1.11 MB; `yuka.spt` 1.00 MB; `yuka2.spt` 184 KB.

Visible sidecar/configuration files:
`bgmlist.xtx` 1.51 KB; `cglist01.xtx` 5.43 KB; `charaname.xtx` 0.96 KB; `command.xtx` 4 B; `function.xtx` 79 B; `namecol.xtx` 0 B; `project.xtx` 109 B; `scene.dat` 1.16 KB; `selist.xtx` 5.27 KB; `var.fxf` 3.47 KB.

The folder UI reports 30 objects. The list above is the set of readable names from the screenshots; it may not include rows hidden between screenshots or outside the captured area.

Key observation: 01.spt is shown as 390 KB, close to the previously analysed sample reported as 389,500 bytes. This is a strong candidate match, but no checksum or binary comparison has established identity.

The presence of multiple numbered and named SPT files means the runtime must not assume that the whole game is represented by 01.spt alone. The small XTX/DAT files may hold project tables or configuration, but their roles are not proven from filenames alone.

No code changed. Next diagnostic sample should be spt/01.spt plus the small sidecar files project.xtx, command.xtx, bgmlist.xtx, cglist01.xtx, charaname.xtx, selist.xtx, and scene.dat. Start with headers, tables and references; do not infer command meanings from names.


## 2026-11-04 — Real SPT sample and sidecar structural analysis

Inspected uploaded `hui.zip`; it contains all eight requested files. `01.spt` is exactly 389,500 bytes. After XOR 0xFF, its signature at 0x30 is `SPTHEADER0`. Header descriptors are (1299, 0xC5BA), (1124, 0x167F2), (69, 0xC390), and (2, 0). This exactly matches the previously reported sample, proving that the old measurements correspond to the user's current 01.spt.

The first bytes at 0xC5BA after XOR are `33 33 02 00 00 00 FF FF FF FF ...`. This does not prove that the table is a sequence of image IDs or dialogue offsets. At 0x32B20 there are pointer-like values and repeated `text` labels, suggesting a structured text area, but command semantics remain unproven.

`project.xtx` is a 20-line text list of scenario/module names (including sys, backspt, 01–08, 082, character-named scripts and _staff), confirming that the project is multi-script. `bgmlist.xtx`, `cglist01.xtx`, `charaname.xtx`, `selist.xtx` are Shift-JIS/compatible CRLF text tables; structural line counts: 46, 540, 85 (84 nonempty), and 195 (190 nonempty), respectively. Their user-facing strings were not copied into the log. `command.xtx` is only 4 bytes/two blank CRLF lines. `scene.dat` is 1,164 bytes; a naive LE uint32 view gives 291 words, first word equals file size, but format is not yet established.

No app code changed. Do not implement the earlier sequential-text fallback as confirmed behavior. Next: map all four SPT tables and their internal references; correlate with XTX tables and scene.dat; avoid hard-coded offsets across different scripts. A second compact sample such as sys.spt or day.spt will help distinguish shared format from script-specific layout.


## 2026-11-04 — Full SPT package analysis (`hui(1).zip`)

### Archive integrity and contents
- SHA-256: `20a06b948bfb7d5c0fb5b2fc6a02396161c29e8959d1743599f81de3fea93509`.
- ZIP size: 1,969,339 bytes; expanded file size: 9,815,703 bytes.
- CRC integrity check passed.
- Contains folder `hui/`, 30 files: 20 SPT scripts and 10 sidecar/configuration files.
- This is the complete uploaded SPT-folder subset, not the large DWQ/VAW/WGQ game resources.

### Project/module mapping
- `project.xtx` contains 20 module names.
- Its 20 names exactly match the basename set of all 20 supplied `.spt` files.
- The project list order starts with `sys`, `backspt`, then `01`; this is not proof of execution order or entrypoint.
- Current Ramen `SystemNnnPlayerActivity` still selects `01.spt` by filename preference and does not load `project.xtx` or resolve inter-module links.

### SPT header cross-sample results
All 20 files share XOR-`0xFF` demasking, `SPTHEADER0` at offset `0x30`, and first LE DWORD 20. Four count/offset descriptor pairs are present at header positions 0x10/0x14, 0x18/0x1C, 0x20/0x24, 0x28/0x2C. Their values vary substantially. The per-file matrix and raw values are recorded in [SYSTEM_NNN_LAUNCH_ROADMAP.md](SYSTEM_NNN_LAUNCH_ROADMAP.md).

Current loader constraints accept only 12/20 scripts:
- 4 with primary count zero: `_staff`, `backspt`, `day`, `sys`;
- 4 below fixed size boundary 0x32B65: `06`, `08`, `op1`, `yuka2`.
These are not proven corrupt; they are part of the module list. Parser must distinguish empty primary descriptor from malformed file.

For `01.spt`, main region begins with mixed two-byte marker patterns such as `0x3333`, `0x6666`, `0x2222`, and `0x5556`. Reading the region as a plain list of 1,299 UInt32 image/dialogue IDs is not validated. Exact record width and grammar remain unknown.

Text markers `text\0` were found at six offsets in `01.spt` near 0x32B34 and one offset in `02.spt` near 0x28350; no exact marker in the other 18 SPTs. The fixed 0x32B64 text start is therefore not a proven universal boundary.

### Independent text-candidate heuristic
Reviewed the public VNTools `SPTScript` implementation. It XOR-demasks SPT and heuristically scans for NUL/control-byte plus Shift-JIS patterns; it does not decode the SPT VM instruction grammar. A local, non-outputting reproduction counted 4,568 candidate strings across 20 files:
`01` 131; `_staff` 2; `02` 229; `03` 560; `04` 280; `05` 186; `06` 27; `07` 120; `08` 40; `082` 381; `akane` 656; `aoi` 454; `backspt` 66; `day` 13; `kiika` 473; `op1` 48; `sys` 0; `yamiko` 432; `yuka` 396; `yuka2` 74.
These are heuristic candidates, not confirmed dialogue lines. No scenario text was copied into the report. The same file's current Ramen NUL splitter yields 2,502 Shift-JIS-decodable segments after its fixed start, which demonstrates that "decodes as Shift-JIS" is not enough to establish a real dialogue string.

### Sidecar observations
- `project.xtx`: 20 module entries, exact one-to-one match to SPT set.
- `bgmlist.xtx`: 46 CRLF lines; `cglist01.xtx`: 540; `charaname.xtx`: 85; `selist.xtx`: 195; `function.xtx`: 3.
- `command.xtx`: two blank CRLF lines; `namecol.xtx`: empty.
- `var.fxf`: 3,468 bytes; XOR 0xFF makes its beginning ASCII-like.
- `scene.dat`: 1,164 bytes, first LE DWORD equals total file size; remainder remains structurally unresolved.
- Text contents were not copied into the work log; only structural counts and sizes were recorded.

### Conclusions and next steps
1. Do not apply the fixed 0x32B20/0x32B64 boundaries to every module.
2. Do not interpret main-table values as simple UInt32 image/dialogue IDs without evidence.
3. Build a read-only diagnostic for all four descriptors and candidate references per script.
4. Correlate `project.xtx`, `scene.dat`, and SPT cross-references to discover module entrypoints and transitions.
5. Use VNTools-style text candidates only as an offset-finding hint, not as an interpreter.
6. Return to WGQ/DWQ selection after examining one actual resource header.

### What was not changed
- No Kotlin source or test file changed.
- No CI was run for this documentation-only analysis.
- No script text was exported or reproduced.
- No entrypoint or playable runtime has been proven.


## 2026-11-04 — Multi-archive resource selection audit

Cross-checked the user's root folder screenshots with current runtime code:
- `dwq/` contains six visible GPK/GTB pairs: bg0, ev0, sc0, sm0, sys0, ta0.
- `cdvaw/` contains 166 objects; visible portion shows VPK/VTB pairs aa through ff and the start of gg.vpk.
- `wgq/` contains 111 standalone .wgq files.
- root `vaw/` contains two objects.

Confirmed in `SystemNnnGpkBitmapRepository.forGameDirectory()`: root `wgq/` is handled first. If any .wgq files exist, the function returns a WGQ-backed image repository or a GTB/WGQ pair found inside wgq/. It searches ordinary GPK/GTB pairs in the rest of the game tree only when root wgq/ is absent. Therefore the displayed sibling `dwq/` directory can be bypassed.

A second limitation: the bitmap repository opens only one GPK/GTB pair. The user's sample has six pairs, so picking the first pair cannot be assumed to cover all scene resource references.

Confirmed in `SystemNnnPlayerActivity.prepareAudioRepository()`: it scans VTB catalogs, pairs each with same-basename VAW/VPK in the same folder, and returns the first nonempty repository. It does not aggregate multiple VPK/VTB banks. The screenshot shows at least six such pairs in cdvaw/. Sequential voice-index assignment is still unverified.

Public VNTools format notes classify WGQ as BGM with a 64-byte header and Ogg payload, whereas the current Ramen image resolver treats standalone WGQ as DWQ images. No actual WGQ header from this game has been inspected yet, so this is a high-priority discriminator test rather than a final classification of all 111 files.

No code changed. Next work: prove archive-to-resource mapping using index metadata and SPT references; then implement multiple image/audio banks and a separate BGM path without relying on sorted filename order.


## 2026-11-04 — SPT address tables and two string pools validated across all modules

### Cross-reference
Reviewed an independent NNNLib reverse-engineering reference and parser from another title:
- https://github.com/flamecho/pretrans-extractor/blob/main/references/engines/system-nnn.md
- https://github.com/flamecho/pretrans-extractor/blob/main/scripts/cloverlib_extract.py
- https://github.com/flamecho/pretrans-extractor/blob/main/scripts/cloverlib2_extract.py

The source is for a different game, so it was treated as a hypothesis and tested against every supplied SPT rather than copied uncritically.

### Local verification against the user's complete package
- Every SPT has the same XOR-0xFF transform, 0x20 first header word, and SPTHEADER0 at 0x30.
- First two header descriptors are two indexed pools. For each non-empty pool, searching for the aligned first logical address `base + count` yields exactly one candidate UInt32 address table with a strictly increasing sequence.
- Each table is followed by exactly `count` NUL-delimited records, each next record aligned to 4 bytes.
- For all 36 non-empty pools across 20 modules, every difference between adjacent logical addresses exactly matches the previous record's 4-byte-aligned size expressed in 4-byte cells. Zero mismatches.
- Pool 1 is nonempty in 16 files; it is legitimately empty in sys, backspt, day, and _staff. Pool 2 is nonempty and parsed for all 20.
- In all 16 files with Pool 1, a 16-byte service stub follows Pool 1; it contains the repeated command pairs (1, 0x6666), (2, 0x5555), and (1, 0x4444), and Pool 2's address table starts exactly 16 bytes after Pool 1's end.
- Pool 2 ends at exact EOF for all 20 files.
- `01.spt` offsets: Pool 1 address table 0x316E8; Pool 1 begins 0x32B34; Pool 1 ends 0x59FB8; stub 16 bytes; Pool 2 address table 0x59FC8; Pool 2 begins 0x5B158; EOF 0x5F17C.
- Therefore header bases 0xC5BA/0x167F2 are logical address bases, not physical file offsets. The old loader's fixed 0x32B64 was after six leading `text` records in 01.spt, not a universal pool start.
- `project.xtx` has an exact one-to-one filename match with all 20 SPT modules. Independent reference describes project.xtx as module load order; treat that as a strong cross-version lead, not yet proof of runtime execution order for this title.
- Pool 2 contains standalone module-name references: sys.spt references op1, 01–08, 082, akane, aoi, kiika, day; 01 references yuka; 03 references yamiko and _staff; multiple character scripts reference _staff; yuka references yuka2. These are proven data references; opcode behavior not yet decoded.

### scene.dat / var.fxf
- `scene.dat` has file size 1164, version 1, and 69 variable-length records, each `type + valueCount + valueCount UInt32 values`. The final record ends exactly at EOF. The third descriptor in 01.spt also has count 69, but no field-level mapping has been established; keep it a possible relation only.
- `var.fxf`, after XOR 0xFF, is a 200-line, six-column CP932 table of state/variable names. This supports classifying FXF/scene.dat as the variables/UI data layer, but exact semantics are not yet mapped.

### Correction to previous assumptions
- The first SPT descriptor's count is not a count of simple UInt32 scene commands. It is the number of records in Pool 1; the physical UInt32 address table is elsewhere in the file.
- Current Kotlin loader is structurally wrong: it treats descriptor base as a file offset, hardcodes the text-pool start, rejects empty Pool 1 modules, and splits all trailing NUL strings without using the declared count/address table.
- No Kotlin code changed and no CI/device test was run in this analysis session.

### Next
Implement the dynamically located Pool 1/Pool 2 tables and aligned string-record reader in Kotlin, test empty Pool 1 modules, then investigate descriptor 3 / scene.dat / var.fxf without guessing their relationship. Only after that, map instruction opcodes and module transitions.


## 2026-11-04 — Implement indexed SPT pool parser and validate CI

### Implementation
- Reworked `SystemNnnScriptLoader.kt` to discover the physical UInt32 address tables from the logical header bases for Pool 1 and Pool 2.
- Validates 4-byte alignment, strictly increasing logical addresses, exact NUL record counts and aligned record strides.
- Validates the 16-byte inter-pool marker block for modules with a non-empty Pool 1.
- Supports the four real modules with an empty first pool.
- Retains original record bytes, logical addresses and a decode-error flag for non-Shift-JIS rows.
- Exposes Pool 1 and Pool 2 separately; retains the existing `textPoolOffset` property as a compatibility view.
- Preserves descriptors 3 and 4 as untyped metadata.

### Runtime safety
- Replaced the old speculative UInt32/sequential-dialogue implementation in `SystemNnnCommandDecoderImpl` with an explicit IOException until opcode semantics are decoded.
- `SystemNnnPlayerActivity` passes a null command decoder for now; it no longer routes the guessed decoder into the experimental player.
- This intentionally prevents false dialogue/background output. It does not make the target game playable yet.

### Tests and CI
- Rebuilt SPT loader fixtures to contain real indexed-pool structure.
- Added tests for both pools, invalid Shift-JIS raw-byte retention, empty Pool 1, all 20 recorded descriptor shapes, invalid logical addresses, bad signatures and header probing.
- Updated VisualNovelDetector SPT fixtures. CI #297 initially failed two detector tests because the old fixtures only had the signature and hard-coded trailing text, not the newly required address tables. Replaced those with valid minimal two-pool fixtures.
- CI #298 succeeded: `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and debug APK artifact upload all passed.
- Actions run: https://github.com/HelterFall/Ramen/actions/runs/41846795259.
- Artifact `ramen-debug-apk`: 11,647,447 bytes.
- No physical-device validation was performed.

### Remaining limits
- The SPT VM opcode/operand grammar is not yet implemented.
- Module transitions are detected as references, not executed.
- Multi-archive GPK/GTB and VPK/VTB resolving and WGQ content classification remain outstanding.
- No source-game binaries were added to the repo; real file evidence stays in the investigation records, while CI uses synthetic fixtures.

### Next
Map instruction boundaries and opcode/operand semantics using the real SPT samples, correlate module links with `project.xtx`, and only then implement the VM runtime.


## 2026-11-04 — Final SPT pool validation and CI #301

### Extra code changes after CI #298
- Parser now requires Pool 2 to end exactly at physical EOF. This invariant was observed in all 20 supplied SPT modules.
- VisualNovelDetector diagnostic now counts records from both indexed pools instead of only Pool 1, so empty-first-pool modules report their parsed secondary records correctly.
- Added a negative test that appends a trailing byte after Pool 2 and expects the loader to reject it.
- Updated both detector tests to verify that their valid minimal SPT fixture reports two records across the two pools.

### Final CI result
- [Android CI run #301](https://github.com/HelterFall/Ramen/actions/runs/41847591081) completed successfully.
- `assembleDebug`: success.
- `testDebugUnitTest`: success.
- `lintDebug`: success.
- Debug APK artifact upload: success.
- Log reports `BUILD SUCCESSFUL in 1m 23s`.
- Artifact `ramen-debug-apk`: 11,647,505 bytes.
- CI #301 validates the latest code including EOF validation and the updated detector. Earlier CI #298 passed the preceding implementation.

### Limitations remain
- No physical-device installation/launch was performed.
- No actual game scene playback is claimed.
- The SPT command/VM instruction grammar is still unknown; the speculative UInt32/sequential-text decoder remains disabled.
- WGQ/DWQ classification, multiple GPK/GTB archives, multiple VPK/VTB banks, voice mapping, and BGM integration are outstanding.

### Next
Create a read-only command map for SPT code regions and their references to both logical string pools and other modules. Identify opcode/operand layout across multiple scenarios before implementing VM execution.


## 2026-11-04 — Initial candidate-command map across all SPT modules

Scanned the candidate code region in all 20 user-supplied SPT files: from physical offset 0x40 to the first physical pool address table (Pool 2 table for scripts with empty Pool 1). Viewed 4-byte-aligned cells as `UInt16 parameter + UInt16 candidate opcode`, using the candidate marker set from independent NNNLib notes: 0x6666, 0x5555, 0x5556, 0x3333, 0x2222, 0x4444, 0x8000, 0x9000, 0xA000 and 0xA500.

Important universal pattern:
- In all 20 modules, every `(parameter=7, secondUInt16=0x6666)` cell is immediately followed by a cell whose second UInt16 is `0x3333`.
- The number of these adjacent pairs exactly equals the total number of `0x3333` cells in each file.
- This holds in ordinary modules and in sys/day/_staff/backspt.

Partial correlation:
- `(parameter=1, secondUInt16=0x2222)` count equals Pool 1 entryCount in 9 of the 16 scripts with nonempty Pool 1: 01, 03, 06, 08, 082, kiika, op1, yuka, yuka2.
- The seven nonempty Pool 1 exceptions are 02, 04, 05, 07, akane, aoi, yamiko. Empty Pool 1 modules also have zero such cells.
- This is not universal enough to be an implementation rule.

Candidate marker hits in the scanned 4-byte cells range from 26.7% to 58.1% by module. In 01.spt, 17,078 of 50,602 cells (33.7%) have one of the candidate marker values; in sys.spt, 240 of 413 (58.1%). Therefore, the whole region cannot be treated as a proven flat instruction array. Some cells may be operands, embedded data, padding or other structures.

No opcode meanings were assigned, and the VM decoder remains disabled. This session made no interpreter code changes. Next diagnostic: export per-cell offsets/params/candidate markers internally, map repeated 0x6666/7 → 0x3333 groups, correlate 0x2222/1 with Pool 1 address tables, and compare across sys/backspt/numbered/character modules before implementing execution.

## 2026-11-05 — Контрольная точка после восстановления контекста

- Целевая новелла подтверждена пользователем: **Gore Screaming Show**, используемая сборка — очищенная, с вырезанным NSFW-контентом. Это обязательное условие состава тестового комплекта.
- Проверена актуальность PR #28: он остаётся открытым черновиком, base `main`, head `docs/system-nnn-launch-roadmap`, не слит.
- Последний кодовый CI — #301, успешно прошли сборка debug APK, unit tests, lint и публикация APK. Физическая проверка на Android не проводилась.
- PR #28 добавляет проверенный структурный разбор двух индексированных пулов SPT для всех 20 предоставленных модулей и отключает прежний декодер, который выдавал неподтверждённые диалоги/фоны.
- Команды/опкоды, VM, переходы сцен, точка входа, voice mapping и BGM пока не реализованы/не доказаны. Следующее исследование — read-only карта командных областей и ссылок; никаких догадок в исполняемый код.
- Не потерять пользовательские требования: сохранить Resource Inspector и рабочие GPK+GTB / VPK+VTB проверки; не возвращать искусственный лимит 512; не ломать импорт ZIP/папки; перед реальной проверкой выдавать APK из Actions. Пользователь тестирует на Android-телефоне и не работает с полноценной desktop-средой.
- PR #28 не сливать без отдельного решения пользователя. Успешный CI не равен подтверждённому запуску игры.

## 2026-11-05 — SPT command-area map across all 20 modules

### Reproducible input
- Analyzed user-provided `hui(1).zip`: 1,969,339 bytes, SHA-256 `20a06b948bfb7d5c0fb5b2fc6a02396161c29e8959d1743599f81de3fea93509`; ZIP CRC check passed.
- 20 SPT modules were demasked with XOR 0xFF. The command scan ended at the first physical address table located by the validated two-pool parser.
- Scan used 4-byte aligned cells, shown diagnostically as `[u16 parameter][u16 candidate marker]`. No strings/dialogue text were exported or copied.
- Independent System-NNN reference for a different title supports this 4-byte cell shape and the candidate marker set, but its game-specific semantics were not assumed for this title.

### Aggregate observations
- 1,172,604 aligned cells scanned.
- 395,677 cells had one of ten candidate marker values (33.74% overall); per-module density ranged 26.69%–58.11%.
- Marker totals: 0x6666=196,525; 0x3333=136,349; 0x2222=46,324; 0x5556=12,300; 0xA000=2,099; 0x8000=921; 0x9000=560; 0xA500=431; 0x5555=84; 0x4444=84.

### Exact adjacent-cell families found in every module
- `(7, 0x6666) -> (*, 0x3333)`: 136,349 pairs; equals every 0x3333 cell.
- `(6, 0x6666) -> (*, 0x2222)`: 46,324 pairs; equals every 0x2222 cell.
- `(2, 0x6666) -> (*, 0x5556)`: 12,300 pairs; equals every 0x5556 cell.
- `(1, 0x6666) -> (2, 0x5555)`: 84 pairs; equals every 0x5555 cell. Each is followed by a 0x4444 cell.
- Secondary observation: `(2, 0x6666) -> (*, 0xA000)` occurs 1,188 times; the second cell parameter is 123 in 594 cases and 125 in 594 cases. No meaning assigned.

### New Pool 1 correlation
For **all 20 modules**, including empty-Pool-1 modules:
- Pool 1 record count = count of 0x2222 cells with parameter 1, 2 or 3.
- Both totals are exactly **37,383** across the package.
- The remaining 8,941 0x2222 cells have parameters 4, 5 or 6 and are not included in this equality.

This is an unusually strong structural correlation and a lead for resolving Pool 1 references. It does not yet prove individual command-to-record mapping or command semantics.

### Code / CI state
- No Kotlin code or tests changed; this pass is read-only analysis/documentation.
- No new CI was run because the changes are documentation-only.
- Command semantics, operand widths, branching, resource/audio mapping and entrypoint remain unknown. The VM decoder stays disabled.
- Detailed per-module offsets and counts are in [SYSTEM_NNN_COMMAND_MAP_NOTES.md](SYSTEM_NNN_COMMAND_MAP_NOTES.md).

### Next
At the individual-record level, compare the data words after each `0x2222` pair with Pool 1 logical addresses and indexes across all 20 modules. Keep this as a read-only diagnostic until a deterministic mapping is proven.

## 2026-11-05 — Pool 1 direct-index mapping and scanner

### Byte-level result
- For each of the 16 modules with non-empty Pool 1, every observed `[6,0x6666] [kind=1..3,0x2222] [u32 index]` sequence yields one index in exact ascending order: `0..Pool1Count-1`.
- All 37,383 Pool 1 records are covered once; no missing indices, duplicates or descents. Empty Pool 1 modules have zero references of these kinds.
- Kind distribution: 1 = 37,008; 2 = 125; 3 = 250. Kind semantics remain unknown.
- See [SYSTEM_NNN_COMMAND_MAP_NOTES.md](SYSTEM_NNN_COMMAND_MAP_NOTES.md) for the per-module table and evidence boundaries.

### Kotlin changes
- Added `SystemNnnPool1Reference` and `SystemNnnPool1ReferenceScan`.
- Added bounded, read-only `SystemNnnScript.scanPool1References()`.
- Added synthetic tests for all three observed kinds, ascending mapping, mismatch reporting, unsupported kinds, wrong prefixes and truncated rows.
- The scan does not execute the VM, decode text, or change player behavior. The existing command decoder remains disabled.

### Validation status
- New source/test commits are pushed to the existing draft PR #28 branch.
- Actions for the new code must be checked; earlier CI #301 predates this scanner and does not validate it.
- No device test and no game-launch claim.

### Next
Determine whether reference kinds 1/2/3 distinguish record classes by structural metadata only, without rendering/extracting scenario text. In parallel, examine the next confirmed reference family without enabling execution.

## 2026-11-05 — CI #312 validates the Pool 1 reference scanner

- Android CI run [#312](https://github.com/HelterFall/Ramen/actions/runs/41853132716) completed successfully on code-bearing head `102e81f53c867d4d43a9dadb5d2d447dd6d98db4`.
- `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and debug APK artifact upload all passed.
- Artifact `ramen-debug-apk`: 11,649,256 bytes; SHA-256 `e5f9fd923b20ee8f2906d1b897ed198244304738b2ac7d4db060a5b838f782b7`.
- This run includes the Kotlin Pool 1 reference scanner and its synthetic unit tests. Later documentation-only updates do not change that code.
- No physical-device installation or game-playback test has been performed.

## 2026-11-05 — Compare reference kinds and map opaque 0x3333 / 0x5556 patterns

### Pool 1 reference kinds
- Verified all 37,383 references still map each Pool 1 index exactly once in ascending order.
- Kind totals: 1 = 37,008; 2 = 125; 3 = 250.
- All three kinds use the same parsed Pool 1 storage structure. Kinds 2/3 are limited to seven modules and are clustered in index order.
- Kind 3 records have a lower CRLF share (57.60%) and greater mean raw length (136.62 bytes) than kind 1 (77.25%, 117.86 bytes); kind 2 is similar to kind 1 (72.80%, 117.44 bytes). These observations do not establish UI semantics.

### Other marker families
- 0x3333: 136,349 exact prefix matches. A low-word N in 0..20 is followed by a candidate continuation at start+(N+4)*4; 0x6666 is found there in 122,992 cases (90.21%).
- 0x5556: 12,300 exact prefix matches. The immediately following aligned cell contains 0x6666 in 10,757 cases (87.46%). The third-cell raw word is often 3/4/5/7, with 341 occurrences of 0xA0000000.
- These are continuation-boundary candidates, not fully validated command sizes or semantics.

### Kotlin changes
- Added read-only SystemNnnOpaqueMarkerPattern and SystemNnnScript.scanOpaqueMarkerPatterns().
- The scanner requires the exact observed prefix, preserves raw UInt32 values, and returns candidate successor offsets without executing or skipping the data.
- Synthetic tests cover the 3333 candidate span, 5556 immediate successor, out-of-range N and wrong prefix.
- No dialogue/scenario text was exported or recorded.

### Validation
- CI run #319 was started for the code-bearing head 39b669e16f2bf70ca9d01705853388c57b04e37c; final result must be checked before this update is considered CI-verified.
- No physical-device test or VM execution is claimed.

### Next
Cross-reference candidate 0x3333/0x5556 payload words with third/fourth SPT header descriptors and the existing scene.dat structure. Keep any relation as metadata-only until it is exact across all modules.

## 2026-11-05 — Third/fourth descriptors and scene.dat cross-check

### scene.dat
- Re-parsed the supplied 1,164-byte scene.dat: header says size 1,164, version 1, record count 69.
- All 69 variable-length records parse exactly to EOF using [u32 type][u32 valueCount][valueCount × u32 values].
- Type counts: 10=4, 11=15, 12=14, 13=16, 14=2, 15=14, 18=2, 4=2. ValueCount distribution: 1=28, 2=10, 3=26, 4=1, 5=4. No record text/presentation values were exported.

### SPT descriptor 3
- Across all 20 SPT samples, DWORD at physical offset 0x40 exactly equals descriptor3.base + descriptor3.count.
- Descriptor 3 count is 69 only for 01.spt; the other 19 counts differ.
- A 4-byte-aligned strictly increasing UInt32 address table using descriptor3.base+count as first entry could not be found in any sample using the Pool 1/Pool 2 validation rule.
- Descriptor 3 therefore has a universal first-word relationship but is not yet understood as a third indexed string pool.

### SPT descriptor 4
- Base is zero in all 20 files; count ranges 1..20.
- Short candidate tables are too ambiguous to locate uniquely with the current method. No relationship to the 0x5555/0x4444 pattern was proven.

### Conclusion / next
- The 01.spt count match (69 == scene.dat record count 69) is one sample correlation only.
- A direct mapping from scene.dat types/value counts to 0x3333/0x5556 parameters was not established.
- Next: investigate the repeated DWORD at 0x40 and the second DWORD at 0x44 as a pair, and test any proposed target against the physical bytes before assigning descriptor 3 semantics.
## 2026-11-05 — WGQ image gating, partial-runtime status and CI #351

- Target remains *Gore Screaming Show*, cleaned Steam build only. No NSFW materials or extracted commercial game data were added.
- Added a bounded header-only probe for standalone WGQ files. The probe checks the supported Black Cyc packed-BMP header and basic BMP dimensions/ranges; full pixel decoding remains deferred until display.
- SystemNnnGpkBitmapRepository still supports indexed WGQ + GTB pairs and valid standalone Black Cyc WGQ images. If a WGQ folder contains no supported image candidates, it no longer blocks discovery of GPK/GTB pairs elsewhere (including a sibling dwq/ folder).
- Added a regression test with an OggS-like payload behind a 64-byte prefix and a valid sibling GPK/GTB pair. Existing valid standalone-WGQ coverage remains in place.
- Updated System-NNN compatibility wording: the player is experimental and only a subset of commands is implemented; unknown commands continue to fail explicitly.
- Android CI [#351](https://github.com/HelterFall/Ramen/actions/runs/41902266343) passed on f0287ab6411429aca0f0c7a62d07304aeaf88f29: assembleDebug, unit tests, lint and debug APK upload. Artifact ID 13359326663, 11,690,600 bytes, SHA-256 c82df8279fde8ac99fd3de8331aa5babad91bc19cc2e00580c639b307c8151a6.
- Remaining: validate WGQ classification against actual headers from the user's cleaned package; multi-GPK/GTB and multi-VPK/VTB routing; real SPT entrypoint and module transitions; voice/BGM mapping; physical Android playback.
- No change to main and no merge. CI is not a device-compatibility claim.


## 2026-11-05 — project.xtx catalog and fail-closed entrypoint handling

- Inspected only the user-provided `project.xtx` bytes and the fixed 0x40-byte SPT headers in the uploaded cleaned Steam package. No SPT payload, image, audio, or other commercial game data was copied into the repository.
- Confirmed a 20-line ASCII/CRLF module list. Every name maps one-to-one to a sibling SPT file; the ZIP archive ordering is different.
- The manifest has no explicit startup-module field. The current film-table interpretation yields possible film-0 starts in both `backspt.spt` and `01.spt`, so choosing either from order or filename would be speculative.
- Added `SystemNnnProjectCatalog` with bounded strict-ASCII parsing, identifier validation, case-insensitive duplicate detection, sibling-file resolution, and path/symlink checks. Tests cover ordering, case-insensitive resolution, duplicates, path traversal, non-ASCII bytes, and missing module files.
- Updated the player discovery path to consume `project.xtx` and to fail closed for multi-module projects until an entrypoint is proven. Removed the unconditional `01.spt` preference. A project with exactly one declared module, or a folder with exactly one valid SPT and no manifest, remains eligible for the current player path.
- No claim of module navigation or full game compatibility is made. Next: read-only trace across `sys`, `backspt`, `01`, and scene records.



## 2026-11-05 — read-only boot-path evidence from project SPTs

- Across the 20 SPT files listed by `project.xtx`, all headers validated as `SPTHEADER0` and all structural record streams parsed using the current bounded reader.
- Under the current table interpretation, `sys.spt` has no ordinary film-0 slot (table count 3, reserved count 3). The other 19 modules do have a film-0 slot and each slot points to a parsed record boundary.
- Both `backspt.spt` (first film target `0x884`) and `01.spt` (first film target `0x20C4`) are valid record-boundary candidates; the table does not uniquely select either as game startup.
- `sys.spt` secondary Pool 2 contains 17 records, including 14 strings equal to module names from `project.xtx` and three system/menu labels. Three `SYSTEM_FUNCTION` records (`0x3333/81`) refer to Pool 2 indices 0, 14, and 15.
- This is structural evidence of a distinct `sys` path and a module-name catalog. It is not yet a validated module dispatch or a startup rule. Strings that match module names are not automatically transitions.
- No game assets, SPT files, scene dialogue, or audio/image payloads were added to Git. The uploaded package was inspected read-only.
