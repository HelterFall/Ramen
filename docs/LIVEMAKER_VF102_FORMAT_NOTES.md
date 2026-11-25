# LiveMaker VF v102 format notes

## Scope

Ramen's initial LiveMaker parser supports the VF v102 directory and bounded extraction for the ordinary and zlib compression modes. It does not implement the `.lsb` virtual machine, scene playback, or the LiveMaker Pro file-encryption layer.

The layout and transform details are cross-checked against the published `pylivemaker` `livemaker.archive` source:
https://pylivemaker.readthedocs.io/en/latest/_modules/livemaker/archive.html

## Container location

A standalone VF archive starts at byte offset 0.

For a Windows executable beginning with `MZ`, the parser reads the final six bytes:
- four-byte unsigned little-endian absolute offset to the VF archive;
- ASCII trailer `lv`.

The VF header is checked against the source bounds before parsing continues.

## VF directory

All multi-byte integer fields are unsigned little-endian values.

| Field | Layout |
|---|---|
| Signature | 2 bytes: `vf` |
| Version | UInt32; this parser accepts exactly 102 |
| Entry count | UInt32 |
| Names | `count` entries, each prefixed by a UInt32 byte length |
| Offsets | `count + 1` pairs of UInt32 low/high words |
| Compression | `count` one-byte compression identifiers |
| Unknown values | `count` UInt32 values |
| Checksums | `count` UInt32 values |
| Encryption flags | `count` one-byte values |
| Payloads | Data ranges described by consecutive offsets |

Filenames use CP932 (Windows Shift-JIS). For LiveMaker 3 archives, name bytes are XOR-transformed with one continuous keystream. The keystream starts at state zero; for each byte, state becomes `(state * 5 + seed) mod 2^32`, where seed is `0x75D6EE39`; the low byte of each new state is XORed with the corresponding filename byte.

Offset low words use their own continuous keystream. High words use a separate stream and the canonical high-word marker defined by `LMObfuscator.transform_int_high` in pylivemaker. The parser reconstructs the high word and rejects offsets outside the signed 64-bit file-address range.

The size of entry `i` is `offset[i + 1] - offset[i]`. Offsets are relative to the beginning of the VF archive, so payload reads use `archiveOffset + entryOffset`.

## Compression values

| Value | pylivemaker name | Ramen initial parser |
|---:|---|---|
| 0 | `ZLIB` | Supported; streamed through zlib Inflater |
| 1 | `NONE` | Supported; copied as stored |
| 2 | `ENCRYPTED` | Recognized, but decoding is not implemented |
| 3 | `ENCRYPTED_ZLIB` | Recognized, but decoding is not implemented |

The per-entry `encrypt_flag` byte is distinct from the compression identifier. Checksums are retained as metadata but are not yet verified by `copyEntryTo`.

## Ramen integration status

- `LiveMakerArchiveParser` parses a bounded VF v102 directory from a standalone archive or an EXE with the `lv` trailer.
- `copyEntryTo` streams flag 0 and flag 1 entries with an output-size ceiling.
- `safeRelativePath` rejects absolute and traversal paths before extraction.
- `VisualNovelDetector` recognizes a structurally valid LiveMaker container in an imported game directory and reports `PARSER_AVAILABLE`.
- Existing ZIP and SAF folder importers already invoke the detector after staging the copied game.
- A compiled `.lsb` parser and an initial host-driven VM subset are now present; see [LiveMaker LSB format notes](LIVEMAKER_LSB_FORMAT_NOTES.md). They do not yet provide the complete command semantics or an Android scene player.

Automated tests build synthetic VF v102 fixtures and exercise standalone archives, embedded EXEs, CP932 names, stored data, zlib data, invalid trailers, path traversal, and detector recognition. These fixtures validate the implementation against the documented layout; they do not replace testing against a real, appropriately licensed sample.