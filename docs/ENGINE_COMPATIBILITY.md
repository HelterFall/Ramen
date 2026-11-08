# Engine Compatibility

This document is a research matrix, not a list of currently supported engines.

## Status meanings

- **MVP candidate:** first implementation target; still requires a working prototype.
- **Research:** investigate feasibility, maintenance, and licensing.
- **Later:** deliberately outside the first implementation.
- **Supported:** only after repeatable tests pass on declared Android versions and devices.

At project start, **no engine is supported yet**.

## Candidate matrix

| Engine / format | Status | Reason / next validation |
|---|---|---|
| Self-contained HTML5 projects | MVP candidate | Test local assets, relative URLs, audio, orientation, and navigation inside a hardened WebView. |
| TyranoScript HTML5 exports | MVP candidate | TyranoScript is HTML5-based; validate representative exported projects and their dependencies rather than assuming every export works. |
| Ren'Py | Research / detected unsupported | Ramen recognizes source .rpy, compiled .rpyc/.rpymc signatures and recognized RPA 2/3 magic prefixes in a game payload. No Ren'Py runtime is integrated yet. The leading feasibility path is the official 8.5.3 Android host plus the path_to_gamedir() distributor hook; see [Ren'Py Android Runtime research](RENPY_ANDROID_RUNTIME.md). |
| KiriKiri / KAG | Research | Evaluate available mobile runtime projects, supported versions, formats, build requirements, and licence obligations. |
| ONScripter | Later / research | Consider after the first runtime and compatibility test system exist. |
| Native/proprietary Windows VN engines | Research | Start with PE architecture/import metadata and byte-level container identification. Each engine family requires independent format evidence and a feasibility report; no Windows executable is launched by the inspector. |
| Unity-based visual novels | Later | Unity is a general-purpose engine; version and build variations make this a separate, substantial compatibility project. |

## Native / proprietary engine research

The first tooling step is read-only inspection, not emulation. Ramen currently reports common executable and container signatures and a limited set of PE header fields. It does not execute Windows binaries, decode proprietary archives, or claim compatibility with any engine based on filenames. See [Engine Research](ENGINE_RESEARCH.md), [Format Registry](FORMAT_REGISTRY.md), and [Development Log](DEVELOPMENT_LOG.md) for the evidence record and next steps.

## Important distinctions

- A ZIP file is a container, not a game engine.
- A folder containing HTML files is not automatically a supported HTML5 game.
- A game can be recognized without being launchable.
- A runtime may support only some versions or features of an engine.
- A title-specific patch must be recorded as such; it must not be presented as general engine support.

## HTML5 / TyranoScript validation checklist

Use original, small test projects. Record:

- entry point detection (for example, `index.html`);
- relative asset paths and nested directories;
- JavaScript execution and browser API requirements;
- audio playback and user-gesture requirements;
- screen orientation and viewport scaling;
- save/load behavior (for example, local storage or IndexedDB);
- browser dialogs, external links, and downloads;
- network dependencies;
- performance and memory behavior;
- behavior after app pause, resume, and process recreation.

TyranoScript describes itself as an HTML5-based engine and lists browser and mobile distribution targets. That makes it a sensible candidate for a first validation, not a guarantee of compatibility with every exported project.

Reference: https://tyranoscript.com/usage/tutorial/about

## Ren'Py validation questions

Ren'Py's official Android documentation describes a packaging workflow using its Android Packaging Tool, producing an installable Android package for a game. Before integration, answer:

1. Is there a supported reusable runtime API for a host app?
2. Can a game folder be loaded without rebuilding its APK?
3. Which native libraries and Android components are required?
4. What are the consequences for app size, ABI coverage, updates, and save storage?
5. Which licence notices and source-distribution obligations apply?

Official reference: https://www.renpy.org/doc/html/android.html

## KiriKiri validation questions

Investigate:
- KiriKiri2 vs other KiriKiri generations;
- TJS2 and KAG coverage;
- XP3 and other package support;
- supported rendering and audio paths;
- Android ABI and API-level coverage;
- known incompatible games;
- whether the runtime can be linked as a library or must ship as a standalone app;
- GPL-3.0 and other dependency obligations, if using an existing implementation.

One project to evaluate is PocketKrKr. Its own project documentation describes a mobile-oriented KiriKiri2 runtime and states GPL-3.0 licensing. This is a research lead, not an endorsement or a decision to incorporate it.

Reference: https://krkr.gith.uk/en/

## Compatibility record template

For every tested title, record:
- game title and version;
- engine and engine version, if known;
- source format and archive layout;
- Ramen build / commit;
- Android version and device / ABI;
- launch result;
- tested features (audio, saves, orientation, pause/resume);
- known issues;
- whether a workaround or game-specific patch is required.

Never upload copyrighted game files or private user game data as test fixtures. Use original or appropriately licensed samples.
