# Inventory Report JSON Schema

**Schema version:** 1  
**Writer:** `DirectoryInventoryJsonWriter`

Ramen can serialize a bounded `DirectoryInventoryReport` as UTF-8 JSON. The report describes metadata collected from imported files; it does not include file contents.

## Top-level object

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | integer | Report schema version. Current value: `1`. |
| `rootName` | string | Name of the inspected directory. |
| `discoveredEntries` | integer | Number of file entries observed by the bounded scan. When the file limit is reached this can be at most `maxFiles + 1`, which is enough to indicate truncation. |
| `totalBytesObserved` | integer | Sum of file sizes included in the selected report entries. |
| `totalBytesHashed` | integer | Sum of sizes for entries whose SHA-256 calculation completed. |
| `truncated` | boolean | Whether file-count/depth limits or traversal errors prevented a complete inventory. |
| `warnings` | string array | Report-level traversal warnings. |
| `entries` | array | Deterministically ordered file records. |

## File entry

| Field | Type | Meaning |
|---|---|---|
| `relativePath` | string | Path relative to the imported game root, using `/` separators. |
| `sizeBytes` | integer | Observed file size in bytes. |
| `sha256` | string or null | Lowercase SHA-256 hex digest, or `null` when hashing was skipped or failed. |
| `artifact` | object or null | Binary inspection report, or `null` when the file could not be inspected. |
| `warnings` | string array | Per-file errors or reasons metadata/hash fields are unavailable. |

## Artifact object

| Field | Type | Meaning |
|---|---|---|
| `fileName` | string | Basename of the inspected file. |
| `fileSizeBytes` | integer | Size read from the open byte source. |
| `extensionHint` | string or null | Lowercase extension hint; it is not proof of format. |
| `kind` | string | One of the current `ArtifactKind` enum names. |
| `signature` | string or null | Recognized signature label or short hex prefix. |
| `evidence` | string | Human-readable explanation for the classification. |
| `peMetadata` | object or null | PE metadata when a PE image was identified. |
| `warnings` | string array | Parser warnings. |

## PE metadata object

| Field | Type | Meaning |
|---|---|---|
| `machineCode` | integer | COFF machine value. |
| `machineLabel` | string | Human-readable architecture label. |
| `optionalHeaderMagic` | integer or null | PE32 / PE32+ optional-header magic. |
| `optionalHeaderLabel` | string or null | Human-readable optional-header type. |
| `sectionCount` | integer | Number of PE sections reported by the COFF header. |
| `isDll` | boolean | Whether the PE characteristics mark the image as a DLL. |
| `importedLibraries` | string array | Names in the standard PE import directory; not a resolved dependency graph. |
| `importDirectoryRva` | integer or null | Import-directory RVA when present. |
| `importDirectorySize` | integer or null | Import-directory size when present. |

## Stability and compatibility

- Consumers must read `schemaVersion` before interpreting fields.
- Unknown fields should be ignored by forward-compatible consumers.
- A missing or null SHA-256 means the file was not successfully hashed; it must not be interpreted as an empty-file hash.
- PE imported library names are declarations in the standard import directory. They do not prove that the DLL is present or usable on Android.
- Extension hints are not format identifications.
- The report contains no file payloads, credentials, or execution capability, but it may include local relative filenames. Review before sharing.

A breaking change to field meaning or structure requires a new schema version. Additive fields may be introduced while retaining the current version if old consumers can safely ignore them.
