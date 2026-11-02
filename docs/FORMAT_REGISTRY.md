# Binary and Container Format Registry

This registry is an evidence catalogue. It is not a claim that Ramen can decode every listed format or run a game that uses it.

## Evidence levels

| Level | Meaning |
|---|---|
| `VERIFIED_SIGNATURE` | A known byte signature identifies the format family. |
| `STRUCTURE_VALIDATED` | Structural fields and bounds have been validated against repeatable byte-level evidence. |
| `PARSER_IMPLEMENTED` | A bounded parser exists and has automated synthetic tests. |
| `OBSERVED_EXTENSION_ONLY` | An extension was seen, but its general byte layout or meaning is not established. |

Evidence must only move forward when new bytes, documentation, and repeatable tests support the change. Parser availability is not runtime compatibility.

## Current signature-backed entries

| ID | Signature / marker | Current inspector result | Notes |
|---|---|---|---|
| PE image | `MZ` followed by PE signature at the DOS header offset | Machine, PE32/PE32+, section count, DLL flag, standard imported DLL names | Bounded PE32/PE32+ import-directory parsing is implemented; delay-load imports and transitive resolution are not. |
| ELF image | `7F 45 4C 46` | ELF signature | ELF header fields are not parsed yet. |
| ZIP | `PK 03 04`, `PK 05 06`, or `PK 07 08` | ZIP container | This signature probe does not replace the bounded ZIP extractor. |
| RIFF | `RIFF` | RIFF container | RIFF subtype is not yet inspected by the generic inspector. |
| Ogg | `OggS` | Ogg stream | Generic inspector reports the signature only. The VPK parser validates page boundaries and sequence metadata but does not check page CRCs. |
| PNG | `89 50 4E 47 0D 0A 1A 0A` | PNG image | Signature only. |
| JPEG | `FF D8 FF` | JPEG image | Start marker only. |

Implementation: `app/src/main/java/io/github/helterfall/ramen/inspection/BinaryArtifactInspector.kt`.

## Parsed proprietary resource families

### GPK / GTB image resources

**Evidence:** the supplied `bg0.gtb` and `bg0.gpk` pair was parsed as 163 resources: 153 JPEG and 10 BMP blocks. The sample archive is 67,588,981 bytes; its index is 2,990 bytes.

The observed GTB index layout is:

1. A little-endian 32-bit resource count.
2. One little-endian 32-bit name-offset table with that many entries.
3. One little-endian 32-bit GPK block-offset table with that many entries.
4. A NUL-terminated ASCII name pool. Name offsets are relative to the beginning of this pool.

The first GPK block starts at offset zero. Resource offsets are strictly increasing; the next resource offset (or archive EOF for the final item) bounds each block.

Each observed GPK block begins with a 64-byte header. The first 32 bytes identify `JPEG` or `BMP`, followed by a `PACKTYPE` marker:

- JPEG uses `PACKTYPE=5`. The header stores the payload length, width, and height. The parser checks that the declared payload length equals the indexed block payload length and that the payload starts with the JPEG marker.
- BMP uses `PACKTYPE=0`. Payloads may include a standard `BM` file header or begin directly with a DIB header. The parser checks DIB header bounds, the palette and pixel offsets, dimensions, planes, bit depth, and supported compression metadata. BI_RLE8 (8-bit) and BI_RLE4 (4-bit) use bounded RLE decoding in the Android preview fallback; encoded/absolute runs, end-of-line, delta, and end-of-bitmap markers are validated. Unsupported LZ/XOR variants are not inferred.

The parser returns bounded payload offsets and sizes. `AndroidGpkImageDecoder` decodes a selected payload with Android `BitmapFactory`, checks dimensions against parsed metadata, and chooses `inSampleSize` to keep the decoded bitmap at or below a configurable pixel budget (8 million by default). `ResourceInspectorActivity` provides a simple preview panel and a scrollable resource list. CI uses synthetic test data—not the supplied game files.

Implementation: `GpkGtbArchiveParser` in `ResourceArchiveParsers.kt`.

### VPK / VTB audio resources

**Evidence:** the supplied `aa.vtb` and `aa.vpk` pair contains 1,809 audio blocks. The VPK archive is 87,626,372 bytes; the VTB index is 21,720 bytes (1,810 fixed-size rows). The final VTB row in this sample points exactly to archive EOF and is treated as a terminal index sentinel, not an audio resource.

The observed VTB index is a sequence of 12-byte records:

- 8 printable ASCII bytes for the resource name.
- A little-endian 32-bit VPK block offset.

Each observed VPK block consists of a 64-byte text preamble, a 44-byte RIFF/WAVE PCM-style descriptor, and an Ogg stream beginning at byte 108. The preamble itself includes the text `CUT THIS 108BYTE THEN REMAKE OGG`. The descriptor contains PCM parameters and a `data` size; that size is not used as the physical compressed Ogg block length. Physical block boundaries come from the VTB offsets.

The parser checks the preamble, RIFF/WAVE fields, PCM parameters, Ogg page capture/version/lacing, serial number, sequence continuity, and BOS/EOS flags. `VpkOggCacheMaterializer` copies a selected Ogg range to a uniquely named app-cache file using storage-aware streaming; it has no fixed per-resource size ceiling and preserves a free-space reserve. `ResourceInspectorActivity` hands that file to Android `MediaPlayer` and exposes basic play/pause/stop controls. This is asset preview only; Android codec support can vary by device and the UI has no seek bar or background playback service. The parser does not currently calculate Ogg page CRCs or decode audio itself.

A separate inspection of the supplied sample found 23,592 Ogg pages with valid page boundaries and matching CRCs. This sample-level check is not yet part of the app parser.

Implementation: `VpkVtbArchiveParser` in `ResourceArchiveParsers.kt`.

### XTX sample

The supplied `codecerror.xtx` file (535 bytes) is readable CRLF-delimited key/value text describing one error-window configuration, including resource names, button sizes, and coordinates. This proves only that this particular sample is text configuration. Other `.xtx` files may use a different syntax or be binary, so the extension remains an observation rather than a generally parsed format.

## File roles that still need inspection

The six supplied samples do not provide a complete inventory of the game directory. We therefore cannot yet state which additional extensions are present or what their exact byte layouts mean.

The next inventory should look for these broad roles without assuming an extension is authoritative:

| Role | Why it matters | Current evidence |
|---|---|---|
| Scenario / control data | Determines story flow, events, choices, and engine commands; likely essential to a playable runtime. | Not established by the supplied byte samples. |
| Configuration and metadata | May describe resolution, language, resource lists, input, or engine settings. | One XTX configuration sample only. |
| Save / persistent state | Needed to preserve progress and restore a session. | Not inspected. |
| Fonts and text rendering resources | Needed to reproduce the original text layout and glyph coverage. | Not inspected. |
| Video and standalone media | May be used for openings, cut-scenes, music, or effects. | Not inspected outside the Ogg payloads inside VPK. |
| Lookup tables and auxiliary binaries | May map IDs, flags, names, or resource references. | Not inspected. |

Do not add parsers for these roles based only on filenames. First collect paths, sizes, hashes, and byte signatures from the complete directory; then inspect representative samples and record what is confirmed, unknown, or malformed.

## Other executable evidence

The supplied `gss.exe` is a 626,688-byte PE32 x86 image. Its standard import table includes `ogg.dll`, `vorbis.dll`, `DDRAW.dll`, `DSOUND.dll`, and `WINMM.dll`. It was inspected as data and was not executed. Import names are dependency declarations, not proof that the game can run on Android.

## Parser contract and limitations

Future and existing probes use explicit outcomes where applicable:

- `MATCH`: a recognized structure was parsed within all bounds.
- `NO_MATCH`: the bytes do not match this format.
- `MALFORMED`: the signature matches or partially matches, but a structural invariant is invalid.

A parser must not allocate based only on an untrusted declared length. Offset arithmetic must be overflow-safe and checked against the available source length. Unknown formats remain inspectable without being executed.

The GPK/GTB and VPK/VTB parsers operate on `ReadOnlyRandomAccessSource`, do not load full payloads, and return resource ranges. GTB display names are decoded as Shift-JIS when valid; invalid, empty, or duplicate labels receive stable fallback names and do not block access to validated physical block ranges. `ReadOnlyRangeStreamer` can copy any such range to a caller-owned output stream using a fixed-size buffer without closing or flushing it. A basic resource browser, image preview, and foreground audio-preview lifecycle now exist. Import-time `VisualNovelDetector` also runs `BinarySignatureProbe` against a bounded directory snapshot. A validated Script v1 or IF PACKT v1 fixture appears as “Обнаружен тестовый движок v1” with resource inspection available; that status does not enable game launch. A full game runtime and physical-device validation are still pending. No supplied proprietary game files are committed to the repository.

Imports and archive staging use storage-aware streaming with a protected free-space reserve rather than arbitrary fixed per-file size ceilings. This does not make storage unlimited: operations can still stop when the destination device lacks sufficient free space.

The generic directory inventory and versioned JSON export are separate APIs; the user-facing save/share flow is still pending. See [Inventory Report JSON Schema](INVENTORY_REPORT_SCHEMA.md).
