# Ramen Test ResourceContainer v1

## Scope

This document specifies the **synthetic test format used by Ramen's PackArchiveReader**. The only externally requested marker was the eight-byte ASCII magic `IF PACKT`; all remaining layout details below are a new, explicit Ramen v1 test-format decision. This is not an assertion about an unrelated proprietary archive format.

V1 is a read-only, uncompressed container. Its index stores relative paths, absolute payload offsets, and payload sizes. Payload bytes are returned unchanged.

## Fixed header

All integer fields are little-endian.

| Offset | Size | Field | V1 rule |
|---:|---:|---|---|
| 0 | 8 bytes | Magic | ASCII `IF PACKT` |
| 8 | 4 bytes | Version | Unsigned 32-bit integer; must equal 1 |
| 12 | 4 bytes | Entry count | Unsigned 32-bit integer |
| 16 | 4 bytes | Index byte length | Unsigned 32-bit integer |

The fixed header is 20 bytes. The index immediately follows it.

## Index records

Exactly `entryCount` records are stored consecutively inside the declared index area.

| Field | Size | Meaning |
|---|---:|---|
| Path byte length | 2 bytes | Unsigned 16-bit UTF-8 byte count; non-zero |
| Path | N bytes | Strict UTF-8 relative path |
| Payload offset | 8 bytes | Unsigned 64-bit absolute offset from archive start |
| Payload size | 8 bytes | Unsigned 64-bit number of payload bytes |

The record area must consume the declared index byte length exactly. Extra bytes, incomplete records, invalid UTF-8, duplicate paths, and unsafe paths are malformed. Offset/size values larger than Kotlin's non-negative `Long` range are rejected.

## Payload ranges

The payload data region begins at `dataStart = 20 + indexByteLength`.

Every payload must satisfy:

- `offset >= dataStart`;
- `offset <= archiveSize`;
- `size <= archiveSize - offset`;
- non-empty payload ranges do not overlap another payload range.

An empty resource may have size zero. Payload offsets are absolute and are not relative to the data region or index record.

V1 does not specify compression, checksums, encryption, timestamps, alignment, or file metadata beyond path/offset/size. A reader must not invent decompression behavior.

## Reader behavior

`PackArchiveReader`:

- Reads only the fixed header and bounded index during `openArchive()`.
- Defaults to no more than 100,000 entries, a 16 MiB index, and 1,024 path bytes per entry. Absolute implementation ceilings are 1,000,000 entries, 64 MiB index, and 4,096 path bytes.
- Uses `ReadOnlyRandomAccessSource` positional reads and does not own/close the source.
- Exposes `openFile(path)` as a bounded InputStream for incremental payload reads.
- Exposes `extractFile(path)` as a convenience for one resource up to 64 MiB by default. Larger resources must use the stream API.
- Does not copy the complete archive or stage payloads to disk.
- Reports malformed headers/indexes/ranges as `PackArchiveException`, an `IOException`.

`BinarySignatureProbe` first matches the eight-byte magic, then validates the full v1 index. A correct magic with an invalid structure is reported as `EngineProbeResult.Malformed`.

## Synthetic fixture coverage

Tests cover valid extraction, stream range bounds, empty containers, the ByteArray size cap, wrong magic/version, truncated index, unsafe/duplicate paths, offsets outside the archive, overlapping payload ranges, and malformed-index integration with the binary probe.
