# Ramen

**Ramen** is an Android-first player and library for visual novels. The goal is to make one focused app that can identify, organize, and run visual novels through engine-specific runtime adapters.

> **Project status:** Android library, safe import, a restricted HTML5 runtime, read-only binary inspection, and bounded GPK/GTB + VPK/VTB resource previews are implemented as early prototypes. A complete metadata-only inventory has been recorded for one representative directory. No proprietary Windows engine runtime is currently supported.

## Product idea

Instead of trying to emulate every kind of desktop software, Ramen focuses on one category: visual novels. The app should provide one library and one consistent mobile experience while delegating game execution to the runtime that understands each game's engine.

The long-term ambition is broad compatibility. It is **not** a promise that every game or file format will work.

## Current foundation

- Kotlin and Jetpack Compose Android app shell.
- Dark first-run library screen.
- Version-pinned Gradle Wrapper with distribution checksum verification.
- GitHub Actions workflow for debug build, unit tests, lint, and APK artifact upload.
- User-selected ZIP and folder imports stream into app-private storage with path-traversal checks and live free-space protection; there is no fixed per-file size ceiling. Standalone HTML file import also supports extraction of recognized embedded media.
- Initial engine-marker detection that clearly distinguishes detection from playability.
- A restricted HTML5 WebView runtime using app-controlled local resource loading, per-game browser profiles, and an offline-only policy.
- A read-only binary inspector for PE/ELF and common file signatures, including standard PE32/PE32+ imported DLL names.
- A bounded deterministic directory inventory with per-file size, SHA-256 fingerprint, binary metadata, and explicit scan/hash limits.
- A streaming, versioned JSON inventory report with a per-game “Save report (JSON)” action in the library; it writes metadata only and never copies file payloads into the report.
- Bounded, read-only parsers for the observed paired GPK/GTB image and VPK/VTB audio resource archives, plus fixed-buffer streaming of individual payload ranges.
- A resource inspector screen can open matching archive pairs, browse image/audio metadata, preview GPK images through a bounded Android BitmapFactory decoder, and play selected Ogg resources through Android MediaPlayer with basic play/pause/stop controls. Selected archives are copied to app-private cache with storage-aware streaming and no fixed per-file size cap.
- No broad storage permissions. Native/proprietary engine runtime integrations are not available yet.

## File types and current evidence

The current research is based on six supplied sample files. The table below separates what has actually been inspected from what Ramen can do with it.

| File family | What the samples show | Ramen status |
|---|---|---|
| `.exe` / PE | `gss.exe` is a 32-bit x86 PE executable. Its import table names Ogg/Vorbis and Windows multimedia/DirectDraw libraries. | Read-only PE metadata and standard imported-library names; the executable is never launched. |
| `.dll` dependencies | DLL names appear in the executable's import table; the individual library files were not part of the inspected sample set. | Dependency names only. No DLL loading or compatibility claim. |
| `.gtb` + `.gpk` | A paired index and image archive containing JPEG and BMP payloads. | Bounded parser, resource list, and image preview are implemented. |
| `.vtb` + `.vpk` | A paired index and audio archive whose observed payloads are Ogg streams. | Bounded parser, resource list, and foreground audio preview are implemented. |
| `.xtx` | One `codecerror.xtx` sample is CRLF-delimited key/value text for an error-window configuration. | Sample-level observation only; other files with this extension are not assumed to share the same layout. |

The generic inspector also recognizes PE, ELF, ZIP, RIFF, Ogg, PNG, and JPEG signatures. A signature match is not the same as a full parser.

**Inventory status:** a complete read-only census of the imported sample directory is now available in [`ramen-inventory-d9c1071b.json`](ramen-inventory-d9c1071b.json). It contains 775 files, 19 extension hints, and 2,595,371,906 bytes observed and hashed. The scan was not truncated; every entry has a SHA-256 value and the report contains no warnings. This is a file census, not proof that every file format or engine behavior is understood. See the [engine research log](docs/ENGINE_RESEARCH.md) and [format registry](docs/FORMAT_REGISTRY.md) for confirmed facts and unknowns.

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
- [Inventory report JSON schema](docs/INVENTORY_REPORT_SCHEMA.md)
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

Move from file census to a small, original, age-appropriate runtime fixture. Define a minimal engine-neutral scenario model for scenes, dialogue, choices, branching, backgrounds, characters, audio cues, and persistent state; then implement and test its parser/interpreter separately from the Android UI. Reuse the existing resource-provider interfaces for original test assets. This is the runtime foundation work—not another inventory feature. Keep proprietary formats unclassified until there is suitable, repeatable evidence and an appropriate test project.

The inventory scan is bounded (up to 10,000 files, depth 64, and 8 GiB of SHA-256 input by default); the report marks truncated scans and records files whose hashes were skipped. The current archive tools preview individual assets; they do not run an engine. File extensions remain hints, not proof of format or compatibility. Do not add third-party game assets to the repository.

## Naming

Ramen is the project name. The app's final icon, visual identity, and release branding can evolve as the prototype develops.
