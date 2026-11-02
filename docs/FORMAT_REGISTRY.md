# Binary and Container Format Registry

This registry is an evidence catalogue. It is not a claim that Ramen can decode every listed format.

## Evidence levels

| Level | Meaning |
|---|---|
| `VERIFIED_SIGNATURE` | A known byte signature or validated header identifies the format family. |
| `OBSERVED_EXTENSION_ONLY` | The extension was seen in a directory listing, but the byte layout and semantics are unknown. |
| `STRUCTURE_VALIDATED` | A parser has validated documented structural fields and bounds against fixtures. |
| `PARSER_IMPLEMENTED` | A bounded parser exists and has automated tests. |
| `SUPPORTED_RUNTIME` | A compatible runtime can consume the format and passes the declared device test suite. |

Evidence must only move forward when new bytes, documentation, or repeatable tests support the change.

## Current signature-backed entries

| ID | Signature / marker | Current inspector result | Notes |
|---|---|---|---|
| PE image | `MZ` followed by PE signature at the DOS header offset | Machine, PE32/PE32+, section count, DLL flag, standard imported DLL names | Bounded PE32/PE32+ import-directory parsing is implemented; delay-load imports and transitive resolution are not. |
| ELF image | `7F 45 4C 46` | ELF signature | ELF header fields are not parsed yet. |
| ZIP | `PK 03 04`, `PK 05 06`, or `PK 07 08` | ZIP container | This signature probe does not replace the bounded ZIP extractor. |
| RIFF | `RIFF` | RIFF container | RIFF subtype is not yet inspected. |
| Ogg | `OggS` | Ogg stream | Page structure and codec are not parsed by this inspector. |
| PNG | `89 50 4E 47 0D 0A 1A 0A` | PNG image | Signature only. |
| JPEG | `FF D8 FF` | JPEG image | Start marker only. |

Implementation: `app/src/main/java/io/github/helterfall/ramen/inspection/BinaryArtifactInspector.kt`.

## Unverified extension observations

The following were visible together in a user-provided directory listing. No sample bytes have been inspected.

| Candidate ID | Observed extensions | Evidence | Unknowns |
|---|---|---|---|
| `observed-gpk-gtb` | `.gpk`, `.gtb` | Both extensions appeared as repeated pairs in one resource directory. | Whether these are archive/data/index pairs, their endianness, compression, record layout, or whether the extensions share one format. |
| `observed-vpk-vtb` | `.vpk`, `.vtb` | Both extensions appeared as repeated pairs in another resource directory. | Same unknowns; do not assume the pair has the same structure as GPK/GTB. |
| `observed-xtx` | `.xtx` | Multiple files appeared in an initialization directory. | Whether these are text, bytecode, configuration, indexes, or binary resources. |

Implementation: `app/src/main/java/io/github/helterfall/ramen/inspection/ContainerFormatRegistry.kt`.

These observations are not a decoder specification. File names and file sizes can help prioritize later inspection, but they cannot establish a binary format.

## Registry entry template

When adding a candidate, record:

- Stable ID and display label.
- Extensions (hints only).
- Evidence level.
- Exact magic bytes or validated header fields, if known.
- Endianness and integer widths, if established.
- Header size and version fields, if established.
- Index/data relationship, if demonstrated by a fixture.
- Compression and checksum behavior, if established.
- Parser implementation and test fixture references.
- Malformed-input limits and failure behavior.
- Licence/provenance notes for documentation and samples.

## Parser contract

Future probes should return an explicit result rather than a boolean:

- `MATCH`: a recognized structure was parsed within all bounds.
- `NO_MATCH`: the bytes do not match this format.
- `MALFORMED`: the signature matches or partially matches, but a structural invariant is invalid.

A probe must not allocate based only on an untrusted declared length. All offset arithmetic must be overflow-safe and checked against the available source length. Unknown formats must remain inspectable without being executed. The current `FixedSignatureProbe` validates only a magic value; it does not claim that the complete container structure is valid.
