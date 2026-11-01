# Folder Import

## Current behavior

The folder importer uses Android's Storage Access Framework folder picker. The user chooses one folder, Ramen receives a tree URI, obtains a temporary persisted read grant, and copies its contents into app-private storage.

The selected tree is copied; it is not modified. Ramen releases the persisted read grant after the copy attempt finishes.

## Safety model

- The app does not request broad filesystem permissions.
- File names are treated as individual path segments, not trusted paths.
- Absolute / relative path segments, path separators inside names, control characters, and drive-prefixed names are rejected.
- Duplicate relative paths are rejected case-insensitively.
- Repeated document URIs from a provider are rejected to prevent provider cycles.
- Maximum depth: 64 levels.
- Maximum entries: 10,000.
- Maximum file size: 512 MiB.
- Maximum total copied data: 1 GiB.
- Declared document sizes are treated as hints only; streaming byte limits remain authoritative.
- Data is copied to a temporary app-private directory and only renamed into the library after copy and engine inspection succeed.
- Failed imports clean up the temporary copy.

## Engine detection

The same detector used for ZIP imports inspects the copied content. Detection never means playback support. A recognized engine remains `DETECTED_UNSUPPORTED` until an actual runtime exists.

## Limitations

- Cloud providers may be slower or may fail while streaming a file; those errors are reported and the partial import is discarded.
- The initial implementation copies files rather than retaining a live link to the selected folder.
- Very large games may exceed the configured 1 GiB import limit.
- Folder import has unit coverage for safe path handling, nested copies, duplicates, and expanded-size limits, but physical-device testing across different document providers is still required.
