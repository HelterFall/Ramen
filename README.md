# Ramen

**Ramen** is an Android-first player and library for visual novels. The goal is to make one focused app that can identify, organize, and run visual novels through engine-specific runtime adapters.

> **Project status:** Android library, safe import, and a restricted HTML5 runtime are implemented as early prototypes. A new native/proprietary engine research track has started with read-only PE and binary-signature inspection. No proprietary Windows engine is currently supported.

## Product idea

Instead of trying to emulate every kind of desktop software, Ramen focuses on one category: visual novels. The app should provide one library and one consistent mobile experience while delegating game execution to the runtime that understands each game's engine.

The long-term ambition is broad compatibility. It is **not** a promise that every game or file format will work.

## Current foundation

- Kotlin and Jetpack Compose Android app shell.
- Dark first-run library screen.
- Version-pinned Gradle Wrapper with distribution checksum verification.
- GitHub Actions workflow for debug build, unit tests, lint, and APK artifact upload.
- User-selected ZIP and folder import into app-private storage, with bounded copying and path-traversal checks; standalone HTML file import, including extraction of supported embedded media.
- Initial engine-marker detection that clearly distinguishes detection from playability.
- A restricted HTML5 WebView runtime using app-controlled local resource loading, per-game browser profiles, and an offline-only policy.
- A read-only binary inspector for PE/ELF and common file signatures, including standard PE32/PE32+ imported DLL names.
- A bounded deterministic directory inventory with per-file size, SHA-256 fingerprint, binary metadata, and explicit scan/hash limits.
- No broad storage permissions. Native/proprietary engine runtime integrations are not available yet.

## Initial MVP

1. Android app shell with a game library.
2. Import a full game folder or ZIP archive; standalone .html/.htm files can also be added as single-page projects.
3. Inspect files and identify likely engine signatures.
4. Show a clear status: supported, detected but unsupported, or unknown.
5. Run a small, deliberately selected set of HTML5 visual-novel projects in an isolated WebView-based runtime.
6. Persist game metadata and app-side state.

The first milestone is a reliable end-to-end path for a few test projects, not a large compatibility list.

## Proposed technology

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Persistence:** Room (planned; not integrated yet)
- **Android file access:** Storage Access Framework (planned; not integrated yet)
- **HTML5 runtime:** constrained WebView support for local projects.
- **Native engine research:** begin with executable metadata, file signatures, and container-format evidence; C/C++ and Android NDK may be appropriate for some future adapters.

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
- [Engine research track](docs/ENGINE_RESEARCH.md)
- [Binary/container format registry](docs/FORMAT_REGISTRY.md)
- [Development log / session handoff](docs/DEVELOPMENT_LOG.md)
- [Roadmap](docs/ROADMAP.md)
- [Android build setup](docs/BUILD_SETUP.md)
- [ZIP import security and behavior](docs/ZIP_IMPORT.md)
- [Folder import security and behavior](docs/FOLDER_IMPORT.md)
- [HTML5 runtime security and behavior](docs/HTML5_RUNTIME.md)
- [Contributing](CONTRIBUTING.md)

## Build

Use the committed Gradle Wrapper with `./gradlew`. See [Android Build Setup](docs/BUILD_SETUP.md) for the pinned toolchain and local build commands.

## Current next step

Next, add a safe report/export path for directory inventories and connect the inventory to user-selected sources where their access model supports positional reads. Investigate proprietary containers only when byte-level samples and repeatable evidence are available. Do not claim compatibility from filenames alone and do not add third-party game assets to the repository.

## Naming

Ramen is the project name. The app's final icon, visual identity, and release branding can evolve as the prototype develops.
