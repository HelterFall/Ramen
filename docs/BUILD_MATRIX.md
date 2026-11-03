# Ramen — build matrix and feature inclusion audit

**Audit date:** 2026-11-04 (post-merge refresh)  
**Purpose:** Prevent building or handing off the wrong branch/APK. This file supplements the canonical [HANDOFF](../HANDOFF.md).  
**Canonical source:** `main`, merge commit `026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5` (PR #27 merged).

## 1. Which branch contains which feature?

This is based on direct inspection of each branch's AndroidManifest.xml, RamenApp.kt, and VisualNovelDetector.kt, not only PR titles.

| Source | Resource Inspector | System-NNN player | Meaning |
|---|---|---|---|
| main — 026ef6cd... | Included and visibly reachable | Included, with conditional game-card launch | Current integration; exact source state promoted from PR #27 and validated by run #278 before merge |
| PR #25 — research/gpk-vpk-readonly-parsers | Activity registered; global shortcut present | No SystemNnnPlayerActivity | Resource parsers/inspector; also a synthetic Script v1 player for its own fixture, not real System-NNN |
| PR #26 — feature/blackcyc-resource-preview | Activity registered; global shortcut present | No SystemNnnPlayerActivity | Black Cyc resource preview; SPT remains opaque/unsupported |
| PR #27 — merged into main | Included in main | Included in main | Source branch merged by commit 026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5; do not use the old branch for new work |

### Resource Inspector: included in current main

In PR #27:
- ResourceInspectorActivity is registered in app/src/main/AndroidManifest.xml (exported=false).
- RamenApp() renders ResourceInspectorShortcut on the main screen without a build-type or feature-flag check.
- Every imported game card has a Просмотреть GPK/GTB и VPK/VTB action that opens the inspector with that game's private relative directory.
- The inspector parses matching GPK/GTB or VPK/VTB pairs, previews decoded images, and offers audio preview.

Therefore **every APK built from PR #27 contains the Resource Inspector and exposes it in the UI**. This is not an accidentally packaged unused class; it is wired as an app feature. There is currently no debug-only gate or separate product flavor to remove it from a release.

### System-NNN launch is wired, but decoder is provisional

- SystemNnnPlayerActivity is registered in the manifest.
- VisualNovelDetector returns engineId=system-nnn-spt and PARSER_AVAILABLE if the SPT loader passes its current structural checks.
- RamenApp shows the Launch button for that status and opens SystemNnnPlayerActivity.
- The Activity loads the SPT and locates paired resources before starting the ViewModel.

This proves that the launch path is connected in the branch. It does **not** prove that the proprietary SPT interpreter is correct or that a complete game can be played.

Specific implementation gaps:
- SystemNnnCommandDecoderImpl expects 1299 UInt32 little-endian entries at 0xC5BA.
- Its current rule is value < gtbResourceCount → resource update; otherwise it selects the next text pool entry sequentially.
- This is not the rule described in the PR text (value >= 0x32B64 as dialogue offset). It also does not resolve the text using the current table value as an absolute offset.
- Voice lookup uses a sequential dialogue-step index into VTB. This needs validation against actual sample data.
- The command decoder currently returns scene update, dialogue, skip, or end. Choice/branch/audio opcode semantics are not established or dispatched by this table decoder.
- GameCard currently tells users that System-NNN launch with text/resource pointer decoding is available, while VisualNovelDetector evidence still says the playback interpreter is not connected. These messages are inconsistent and overstate readiness.

Until the sample's table semantics and actual scene order are verified, describe it as **experimental System-NNN launch / provisional decoder**, not full game compatibility.

## 2. What the GitHub Actions builds actually test

Workflow: .github/workflows/android-ci.yml

Triggers:
- push to main;
- pull request targeting main;
- manual workflow dispatch.

The workflow runs:

    ./gradlew --no-daemon --stacktrace assembleDebug testDebugUnitTest lintDebug

On success it uploads ramen-debug-apk with a seven-day retention.

It does **not**:
- run assembleRelease;
- sign a production release;
- upload to Google Play;
- publish a release;
- remove the Inspector or experimental player from the debug APK.

A successful Actions run means the specified debug build, unit tests, and lint passed for the checked source tree. It is not a physical-device acceptance test.

For pull requests, actions/checkout checks out GitHub's synthetic merge ref refs/pull/<number>/merge. Run #278 completed successfully for the exact PR #27 source head 75675e3302439bc31f5bc1961bc82b1233ec34b7 before merge. After merge, push-run #279 started directly on main commit 026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5; always consult the latest main run.

## 3. Recent build comparison

| PR / run | Branch head tested | Result | Artifact |
|---|---|---|---|
| PR #25 / [run #219](https://github.com/HelterFall/Ramen/actions/runs/41503698179) | b388d449... | Success: assemble, unit tests, lint, APK upload | ramen-debug-apk, about 11.67 MB |
| PR #26 / [run #224](https://github.com/HelterFall/Ramen/actions/runs/41580168158) | e6daf72f... | Success: assemble, unit tests, lint, APK upload | ramen-debug-apk, about 11.54 MB |
| PR #27 / [run #265](https://github.com/HelterFall/Ramen/actions/runs/41671391772) | 648c4d605... | Success: assemble, unit tests, lint, APK upload | ramen-debug-apk, about 11.64 MB |
| PR #27 / [run #270](https://github.com/HelterFall/Ramen/actions/runs/41673052449) | 4a400535... | Success: assemble, unit tests, lint, APK upload | ramen-debug-apk, historical artifact |\n| PR #27 / [run #278](https://github.com/HelterFall/Ramen/actions/runs/41674773614) | **75675e3302439bc31f5bc1961bc82b1233ec34b7** | **Success**: assemble, unit tests, lint, APK upload | ramen-debug-apk, 11,642,991 bytes |\n| main / [run #279](https://github.com/HelterFall/Ramen/actions/runs/41676851845) | **026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5** | Started after merge; in progress at last check | Push validation for canonical main |

Run #270 artifact metadata:
- Artifact ID: 13250265749
- SHA-256: 616d1c121cb966627f3dcc39efb45429c1047e068e3eb988c1ce671362096615
- Created: 2026-11-03 16:24:39 UTC
- Expiry: 2026-11-10 16:24:37 UTC
- Workflow: Android CI, run #270

Run #278 artifact metadata:
- Artifact ID: 13250256957
- SHA-256: 3e86f99170c5636246a142e353408f062a078e6309ce5af0196ae65d89edac67
- Size: 11,642,991 bytes
- Created: 2026-11-03 16:33:33 UTC
- Expiry: 2026-11-10 16:33:30 UTC
- Workflow: Android CI, run #278
- Tested source: `feat/system-nnn-spt-loader` at `75675e3302439bc31f5bc1961bc82b1233ec34b7`

The code from this run was promoted into `main` by merge commit `026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5`. Run #279 is the post-merge push validation; consult Actions for its latest state.

Compare between PR #27 run #265 code snapshot 648c4d605... and current head 4a400535... shows only documentation files changed: root HANDOFF.md, root README.md, and docs/DEVELOPMENT_LOG.md. No app source or test source changed between those two heads. Run #270 then passed against the exact current PR head.

## 4. Branch / PR separation — current status

PR #27 has been merged into main. The old branch `feat/system-nnn-spt-loader` is the historical source branch and should not receive new development commits.

PR #25 (`research/gpk-vpk-readonly-parsers`) and PR #26 (`feature/blackcyc-resource-preview`) remain separate and unmerged. Their work overlaps with files now present in main. Neither is an automatic follow-up merge: compare unique changes against main first, inspect tests, and avoid duplicating or reverting the parser/Inspector implementations already integrated by PR #27.

PR #25 contains a large `ramen-inventory-d9c1071b.json`; review it for private paths and local filenames before considering any parts of that PR.

## 5. Safe build-selection protocol

Before generating an APK, record:
1. the intended source (normally main; use PR #25/#26 only for isolated comparison or targeted cherry-picks);
2. the branch name;
3. the exact commit SHA;
4. the build type (debug or release);
5. the Actions run URL if CI-produced;
6. which experimental features are deliberately included.

For the current integrated app with Inspector and experimental System-NNN, the intended source is main. Record its latest exact commit SHA before building; do not build from the old feat/system-nnn-spt-loader branch.

The pre-integration baseline is historical commit 305f3f25c09c7f2d13c9d0ad7027f9124dcf7884, not current main. Use it only when deliberately reproducing the old baseline; ordinary development and current builds should use main.

A local clean debug validation should run:
- assembleDebug;
- testDebugUnitTest;
- lintDebug.

Record the APK's SHA-256 alongside the branch and commit. Do not call ramen-debug-apk a production release.

## 6. Decision needed before a production release

Decide whether Resource Inspector should be:
- a permanent user-facing app feature;
- a development/debug-only tool;
- a separate tool not shipped in normal app builds.

If debug-only is desired, implement a dedicated product flavor/build config and UI gating, then verify the release manifest and APK do not contain or expose the Inspector. Make a separate decision for the unfinished System-NNN player.

**Current audit recommendation:** use main as the canonical development base. Resource Inspector remains included by the user's explicit choice. Do not call the app a release candidate until SPT semantics and real-device playback are validated; CI #278 is a debug build/test/lint result, not release or device acceptance.
