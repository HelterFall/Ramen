# Contributing to Ramen

Thanks for helping improve Ramen.

## Before opening a change

- Read [Project Scope](docs/PROJECT_SCOPE.md) and [Architecture](docs/ARCHITECTURE.md).
- Keep changes focused on visual-novel library, import, runtime, or compatibility work.
- Discuss a new engine integration before adding large native dependencies.
- Do not claim compatibility without a reproducible test.

## Code guidelines

- Prefer Kotlin for Android application code.
- Keep engine-specific behavior behind runtime adapter boundaries.
- Keep import and detection logic deterministic and independently testable.
- Handle permission revocation, malformed input, and runtime failures explicitly.
- Avoid adding dependencies without explaining why they are needed.
- Do not add dynamic loading of arbitrary runtime code in the MVP.

## Test data

- Use original fixtures or assets with clear licences.
- Do not commit commercial game files, copyrighted scripts, extracted assets, save files, or private user data.
- Keep fixtures small and document their source and licence.
- A test report should include device / Android version, Ramen commit, engine version when known, and exact steps to reproduce.

## Pull requests

A useful PR description includes:
1. What changed and why.
2. Which milestone or issue it addresses.
3. How it was tested.
4. Compatibility changes and known limitations.
5. Licence or dependency changes, when applicable.

## Security issues

Do not publish exploitable details about a vulnerability in an issue before maintainers have had a chance to assess it. Report concerns privately where the repository's GitHub security reporting feature is available.
