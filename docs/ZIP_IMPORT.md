# ZIP Import

## Current behavior

The import flow accepts a user-selected ZIP archive through Android's Storage Access Framework. It extracts game files into Ramen's app-private storage, creates a library record, and scans a limited set of file paths for known engine markers.

No broad storage permission is requested. The original archive is not retained after import.

## Safety limits

The ZIP extractor rejects:
- absolute paths;
- Windows drive paths;
- backslashes and control characters in paths;
- parent-directory traversal;
- duplicate paths (case-insensitive);
- paths nested beyond the configured depth;
- archives with more than 10,000 entries;
- individual files larger than 512 MiB;
- expanded archives larger than 1 GiB;
- archives that contain no files.

Extraction happens in a temporary app-private directory. The directory is renamed into the library only after extraction and inspection both succeed. Failed imports clean up the temporary directory.

## Detection is not compatibility

The scanner can label likely HTML5, TyranoScript, Ren'Py, and KiriKiri content. At this stage those are detection results only: every recognized engine is marked `DETECTED_UNSUPPORTED` because no playback runtime is integrated yet.

An unrecognized archive remains importable and appears as `UNKNOWN`; it is not discarded merely because Ramen cannot identify its engine.

## Current limitations

- This milestone imports ZIP archives, not arbitrary folder trees.
- The scanner uses file markers and is intentionally conservative.
- Game launch, save migration, and extraction of nested archives are not implemented.
- Library metadata is currently stored in app-private SharedPreferences. Room migration can follow when the data model stabilizes.

## Test coverage

Unit tests cover normal extraction, path traversal rejection, expanded-size limits, case-insensitive duplicate paths, and representative engine markers.
