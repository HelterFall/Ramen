# Project Scope

## Vision

Ramen is a mobile-first home for visual novels: one library, clear compatibility information, and a consistent reading / playing experience across multiple game engines.

Ramen is not intended to be a general-purpose PC emulator, operating-system compatibility layer, or universal archive viewer.

## Users and core journey

A user should be able to:

1. Choose a folder or archive containing a game.
2. Let Ramen inspect the available files.
3. See the detected engine and an honest compatibility status.
4. Launch the game when a compatible runtime exists.
5. Return to the library without losing app-side metadata.

## MVP platform

- Android first.
- Kotlin + Jetpack Compose are the proposed app foundation.
- Portrait and landscape layouts are both relevant.
- Minimum Android version will be selected after the first prototype and dependency review.

iOS, desktop, cloud sync, storefront features, and online game catalogues are outside the MVP.

## MVP features

### Library
- Add and remove game entries.
- Store title, optional cover, source location, detected engine, and last-opened time.
- Show compatibility status and useful failure messages.
- Search and basic sorting.

### Import and inspection
- Import a directory or archive through Android's Storage Access Framework.
- Keep source access explicit and revocable.
- Inspect file signatures and known engine markers; do not infer engine support from an extension alone.
- Handle malformed archives and invalid paths safely.
- Do not silently download missing game files.

### Runtime
- First target: a constrained HTML5 runtime for local, self-contained test projects.
- Test TyranoScript exports as a candidate, not as a blanket guarantee.
- Keep the UI independent from engine-specific rendering and lifecycle details.
- Do not expose unrestricted Android APIs to game scripts.

### Persistence
- Store library metadata and app preferences locally.
- Keep game save data separate from the app's own database.
- Document which save formats are engine-owned and which are app-owned.

## MVP acceptance criteria

The MVP is ready for a first private test when:

- A clean Android build can be installed on a test device.
- The user can import a folder and a ZIP archive through the system picker.
- Import rejects traversal paths and handles malformed archives without crashing.
- The scanner can classify the original test fixtures as HTML5 candidate, unsupported known engine, or unknown.
- A bundled or imported original HTML5 test project opens in the runtime.
- JavaScript-based game content works without granting it broad filesystem or Android API access.
- The user can close a game and return to the library.
- Library metadata survives app restart.
- Unsupported games are clearly labelled as unsupported.

## Non-goals for the MVP

- Running arbitrary Windows EXE files.
- Supporting every game engine or every version of an engine.
- Converting one engine's game files into another engine's format.
- Downloading copyrighted games or redistributing commercial game data.
- Running arbitrary third-party runtime plugins downloaded at execution time.
- Cloud accounts, community uploads, achievements, or an online store.

## Product rules

1. **Honest compatibility:** "Detected" must never mean "works".
2. **Test before claiming:** each supported engine/version gets a compatibility fixture and test notes.
3. **Import safely:** archives and game files are untrusted input.
4. **Respect licences:** review runtime, dependency, and sample-content licences before shipping.
5. **Keep the core small:** engine-specific code belongs behind adapter boundaries.
