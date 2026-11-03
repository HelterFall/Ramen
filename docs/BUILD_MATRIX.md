# Ramen — build matrix and feature inclusion audit

**Audit date:** 2026-11-04  
**Purpose:** Prevent building or handing off the wrong branch/APK. This file supplements the canonical [HANDOFF](../HANDOFF.md).

## 1. Which branch contains which feature?

This is based on direct inspection of each branch's AndroidManifest.xml, RamenApp.kt, and VisualNovelDetector.kt, not only PR titles.

| Source | Resource Inspector | System-NNN player | Meaning |
|---|---|---|---|
| main — 305f3f25... | No Activity and no shortcut | No Activity or launch wiring | Baseline branch; only HTML5 player is registered |
| PR #25 — research/gpk-vpk-readonly-parsers | Activity registered; global shortcut present | No SystemNnnPlayerActivity | Resource parsers/inspector; also a synthetic Script v1 player for its own fixture, not real System-NNN |
| PR #26 — feature/blackcyc-resource-preview | Activity registered; global shortcut present | No SystemNnnPlayerActivity | Black Cyc resource preview; SPT remains opaque/unsupported |
| PR #27 — feat/system-nnn-spt-loader | Included and visibly reachable | Included, with conditional game-card launch | Inspector + experimental System-NNN loader/decoder/player |

### PR #27: inspector inclusion is explicit

In PR #27:
- ResourceInspectorActivity is registered in app/src/main/AndroidManifest.xml (exported=false).
- RamenApp() renders ResourceInspectorShortcut on the main screen without a build-type or feature-flag check.
- Every imported game card has a Просмотреть GPK/GTB и VPK/VTB action that opens the inspector with that game's private relative directory.
- The inspector parses matching GPK/GTB or VPK/VTB pairs, previews decoded images, and offers audio preview.

Therefore **every APK built from PR #27 contains the Resource Inspector and exposes it in the UI**. This is not an accidentally packaged unused class; it is wired as an app feature. There is currently no debug-only gate or separate product flavor to remove it from a release.

### PR #27: System-NNN launch is wired, but decoder is provisional

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

For pull requests, actions/checkout checks out GitHub's synthetic merge ref refs/pull/<number>/merge. This tests the PR merged with its current base, rather than just compiling a locally selected branch in isolation. Run #270's log explicitly shows synthetic commit 247ad61 merging PR head 4a400535... into main 305f3f25....

## 3. Recent build comparison

| PR / run | Branch head tested | Result | Artifact |
|---|---|---|---|
| PR #25 / [run #219](https://github.com/HelterFall/Ramen/actions/runs/41503698179) | b388d449... | Success: assemble, unit tests, lint, APK upload | ramen-debug-apk, about 11.67 MB |
| PR #26 / [run #224](https://github.com/HelterFall/Ramen/actions/runs/41580168158) | e6daf72f... | Success: assemble, unit tests, lint, APK upload | ramen-debug-apk, about 11.54 MB |
| PR #27 / [run #265](https://github.com/HelterFall/Ramen/actions/runs/41671391772) | 648c4d605... | Success: assemble, unit tests, lint, APK upload | ramen-debug-apk, about 11.64 MB |
| PR #27 / [run #270](https://github.com/HelterFall/Ramen/actions/runs/41673052449) | **4a400535... current PR head** | **Success**: assemble, unit tests, lint, APK upload | ramen-debug-apk, 11,642,988 bytes |

Run #270 artifact metadata:
- Artifact ID: 13250265749
- SHA-256: 616d1c121cb966627f3dcc39efb45429c1047e068e3eb988c1ce671362096615
- Created: 2026-11-03 16:24:39 UTC
- Expiry: 2026-11-10 16:24:37 UTC
- Workflow: Android CI, run #270

Compare between PR #27 run #265 code snapshot 648c4d605... and current head 4a400535... shows only documentation files changed: root HANDOFF.md, root README.md, and docs/DEVELOPMENT_LOG.md. No app source or test source changed between those two heads. Run #270 then passed against the exact current PR head.

## 4. Branch / PR separation

At audit time all three PRs were open and unmerged, each targeting main:

| PR | Branch | Head | PR size |
|---|---|---|---|
| #25 | research/gpk-vpk-readonly-parsers | b388d449... | 191 commits / 63 files |
| #26 | feature/blackcyc-resource-preview | e6daf72f... | 6 commits / 20 files |
| #27 | feat/system-nnn-spt-loader | 4a400535... | 48 commits / 45 files |

These branches are not interchangeable:
- #25 and #27 are divergent. #27 does not contain the 191-commit history of #25, even though it contains its own implementations of overlapping resource-parser files.
- #26 and #27 are also divergent. #27 does not contain every unique commit from #26.
- Their diffs overlap in manifest, importer, detector, resource parsers, resource inspector, and UI.

Do not merge #25, #26, and #27 in sequence without a deliberate branch reconciliation. Check per-file diffs and preserve fixes manually. PR #25 also includes a large ramen-inventory-d9c1071b.json; review it for private paths and local filenames before considering a merge.

## 5. Safe build-selection protocol

Before generating an APK, record:
1. the intended source (main, PR #25, #26, or #27);
2. the branch name;
3. the exact commit SHA;
4. the build type (debug or release);
5. the Actions run URL if CI-produced;
6. which experimental features are deliberately included.

For the current experimental app with Inspector and System-NNN, the intended source is PR #27 branch feat/system-nnn-spt-loader, not main.

For a baseline build without the Inspector and System-NNN, use main and verify its latest head. Do not assume a developer's current IDE branch is the intended source.

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

**Current audit recommendation:** keep PR #27 as the working research build for now, because it is the newest integration with SPT player wiring and a green CI on its exact head. Do not call it a release candidate until SPT semantics, device playback, and the intended Inspector inclusion are explicitly accepted.
