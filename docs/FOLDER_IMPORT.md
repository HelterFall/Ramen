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
- No fixed per-file or total-byte ceiling.
- Each streamed write is checked against currently available storage.
- A dynamic free-space reserve is preserved (at least 16 MiB, up to 256 MiB or 5% of currently available space).
- Declared document sizes are treated as hints only; streaming byte limits remain authoritative.
- Data is copied to a temporary app-private directory and only renamed into the library after copy and engine inspection succeed.
- Failed imports clean up the temporary copy.

## Engine detection

The same detector used for ZIP imports inspects the copied content. Detection never means playback support. A recognized engine remains `DETECTED_UNSUPPORTED` until an actual runtime exists.

## Limitations

- Cloud providers may be slower or may fail while streaming a file; those errors are reported and the partial import is discarded.
- The initial implementation copies files rather than retaining a live link to the selected folder.
- Imports can still stop when the selected storage volume cannot safely hold more data; this is reported as insufficient free space rather than an arbitrary 512 MiB file-size error.
- Folder import has unit coverage for safe path handling, nested copies, duplicates, explicit size limits, and dynamic storage-reserve checks, but physical-device testing across different document providers is still required.
