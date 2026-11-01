# Ramen

**Ramen** is an Android-first player and library for visual novels. The goal is to make one focused app that can identify, organize, and run visual novels through engine-specific runtime adapters.

> **Project status:** planning / architecture validation. No game engine is currently supported by this repository.

## Product idea

Instead of trying to emulate every kind of desktop software, Ramen focuses on one category: visual novels. The app should provide one library and one consistent mobile experience while delegating game execution to the runtime that understands each game's engine.

The long-term ambition is broad compatibility. It is **not** a promise that every game or file format will work.

## Initial MVP

1. Android app shell with a game library.
2. Import a game folder or archive using Android's system file picker.
3. Inspect files and identify likely engine signatures.
4. Show a clear status: supported, detected but unsupported, or unknown.
5. Run a small, deliberately selected set of HTML5 visual-novel projects in an isolated WebView-based runtime.
6. Persist game metadata and app-side state.

The first milestone is a reliable end-to-end path for a few test projects, not a large compatibility list.

## Proposed technology

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Persistence:** Room
- **Android file access:** Storage Access Framework
- **First runtime candidate:** HTML5 content, including suitable TyranoScript exports
- **Native engine integrations:** evaluated separately; C/C++ and Android NDK may be appropriate for some engines

These are starting decisions, not a claim that every engine can be embedded through the same interface.

## Compatibility principles

- File extensions alone do not identify a game engine.
- Detection and successful execution are separate states.
- Every compatibility claim must be backed by a reproducible test.
- Runtime adapters should be isolated from the library and importer.
- Imported games are untrusted content. File handling and WebView configuration must be designed accordingly.
- Engine code and game content must be reviewed for their licences before redistribution.

## Documentation

- [Project scope](docs/PROJECT_SCOPE.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Engine compatibility](docs/ENGINE_COMPATIBILITY.md)
- [Roadmap](docs/ROADMAP.md)
- [Contributing](CONTRIBUTING.md)

## Current next step

Validate the HTML5 runtime path with a tiny original test game, then create the Android project skeleton. Do not add third-party game assets to the repository.

## Naming

Ramen is the project name. The app's final package ID, icon, visual identity, and release branding are not decided yet.
