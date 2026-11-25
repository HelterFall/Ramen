# HSP3.1 / DPMX implementation status

**Work branch:** `feat/hsp3-dpmx-runtime`  
**Base:** `main` at `56534a43c76f3567ea46d0dbeb7e8ad5f8e88975`  
**Status:** partial HSP3 command decoder, typed VM, Android PlayerActivity and touch gamepad are wired; not yet a complete Demonophobia player.

This note separates implemented code from format observations and unfinished work. No commercial game executable or asset was added to the repository.

## Implemented modules

| Module | Location | Current responsibility |
|---|---|---|
| DPMX container parser | `inspection/DpmxContainerParser.kt` | Read-only PE32 section scan, dynamic overlay offset, DPMX header/table validation, entry ranges and PE virtual-address mapping |
| DPMX decryptor and key resolver | `inspection/DpmxCipher.kt` | Stateful streaming decryption, known sample vectors, injectable PE/.data key derivation and bounded signature search |
| DPMX decrypted asset stream | `inspection/DpmxDecryptingInputStream.kt` | Decrypts one entry as an InputStream and owns/closes its dedicated positional source |
| HSP3 bytecode loader | `parser/Hsp3BytecodeLoader.kt` | HSPHED 96-byte base header, bounded CS/DS/OT/DI, 16/32-bit tokens, EXFLG flags, raw if/else branch word, Shift-JIS DS strings, little-endian doubles and word-based OT offsets |
| CS expression/argument decoder | `runtime/hsp3/Hsp3BytecodeDecoder.kt` | EXFLG_1 command heads, EXFLG_2 parameter boundaries/defaults, EXFLG_3 wide values, postfix integer/double/string/variable/sysvar/label expressions, core branch/loop commands and basic assignment/getkey decoding |
| Android player host | `runtime/hsp3/Hsp3PlayerActivity.kt` | Private-library executable lookup, background DPMX/start.ax preparation, Canvas presentation, bounded VM slices and Activity lifecycle |
| Touch gamepad | `runtime/hsp3/Hsp3PlayerActivity.kt` | OpenHSP stick bits for D-pad, Enter/Z, Esc/X and Space, held-key state exposed to stick/getkey |
| Canvas renderer | `runtime/hsp3/Hsp3CanvasRenderer.kt` | 800x600 default coordinate surface, screen/buffer IDs, gsel, picload, gcopy, boxf, mes, pos, color, font, gmode 0/2/3/5 and redraw presentation gate |
| VM intermediate instruction loop | `runtime/hsp3/Hsp3VirtualMachine.kt` | Typed scalar int/double/string values, postfix evaluation, arithmetic/comparison/bitwise operators, assignments, branches, gosub/return, repeat/loop, wait/yield, getkey/stick and graphics dispatch |
| Runtime adapter | `runtime/hsp3/Hsp3RuntimeAdapter.kt` | PE/DPMX preparation, start.ax key resolution/decryption, HSP3 load, on-demand decrypted assets and default OpenHSP decoder → VM wiring |
| Engine detection | `importer/VisualNovelDetector.kt` | Requires DPMX/start.ax/HSPHED validation plus successful CS command decoding before exposing the HSP3 launch status |

The project remains in its existing `:app` Gradle module. No new Gradle module was created, consistent with the repository handoff rules.

## DPMX parser behavior

The parser does not assume the sample's fixed `0x2b000` overlay location. It reads DOS/PE headers, checks PE32 magic, walks the PE section table, and computes:

`overlayOffset = max(PointerToRawData + SizeOfRawData)`

It then validates the 16-byte DPMX header, optional-name area, 32-byte entry table, data-block offset, and each resource's absolute range. Entry names are decoded as Shift-JIS and rejected when they contain path separators or control characters.

The parser uses `ReadOnlyRandomAccessSource` and positional file reads. It does not load the executable into RAM. Segment/entry reads are bounded by the file length and integer-overflow checks.

The DPMX record marker is checked against `0xffffffff`. Duplicate names are not rejected; callers should use `findEntry` only when the game’s table has a unique name for the requested asset.

## Decryption and key schedule boundary

The streaming cipher implements the supplied byte recurrence exactly and keeps the feedback byte across read buffers. Verified sample-vector pairs are present for:

| Entry | file_key | al | cl | initial_dl |
|---|---:|---:|---:|---:|
| start.ax | `0xb2b8edb5` | `0xb2` | `0x53` | `0x00` |
| abura.bmp | `0x7c01ad18` | `0x38` | `0x8d` | `0x00` |

The described PE virtual-address window `0x429e40..0x429e5c` is mapped and passed to the optional `DpmxDataKeyDeriver` strategy. **The arithmetic which combines those bytes and `file_key` into `(al, cl)` was not included in the supplied specification**, so no unverified formula has been invented.

When the injected PE/.data strategy cannot resolve a key, the resolver checks the two known vectors and then tests all 65,536 `(al, cl)` pairs against a narrow file signature:
- `.ax`: `HSP3` and HSP 3.1 version;
- `.bmp`: `BM`, declared file size matching the DPMX entry size, and a plausible pixel-data offset;
- selected text extensions: strict Shift-JIS decoding and printable characters.

Candidate pairs are accepted only when all signature-matching candidates produce byte-identical plaintext prefixes; candidates with different plaintext outputs are rejected. The cipher has an inherent symmetry: (al + 0x80, cl xor 0x80) can produce the same output as (al, cl), so more than one key pair does not by itself imply ambiguous plaintext. Unsupported file types return no key. This remains a heuristic validation strategy, not a mathematical guarantee for arbitrary BMP or text data.

## HSPHED and token loading

The loader reads the 96-byte HSP3.1 HSPHED base layout, validates `allsize`, and bounds the declared CS/DS/OT/DI segments before allocating them. The sample values described for `start.ax` fit that layout, including the 96-byte header, code/data segments and two-byte debug segment.

The token iterator reads the common token envelope:
- token type: low 12 bits of the first 16-bit word;
- extension flags: `EXFLG_0=0x1000`, `EXFLG_1=0x2000`, `EXFLG_2=0x4000`;
- `EXFLG_3=0x8000` marks a 32-bit value; otherwise the value is 16-bit;
- `TYPE_CMPCMD` carries an extra raw 16-bit branch-distance word after its command token.
  
OT entries are 32-bit offsets in 16-bit CS words, so the loader converts them to byte offsets when resolving labels.

It also exposes bounded NUL-terminated Shift-JIS string lookup in DS and 32-bit offsets from OT. This is a token-envelope parser, not a complete HSP3 language parser.

## Runtime and supported drawing subset

The renderer implements the initial target set:
- `screen 0`, `buffer id`, `gsel id`;
- `picload` via a DPMX-backed stream;
- `gcopy`, `boxf`, `mes`, `pos`, `color`, `font`;
- `gmode 0`, black color-key `gmode 2`, alpha `gmode 3/5`;
- `redraw 0/1`;
- `stick` rising-edge and non-trigger mask behavior.

The screen is initially 800x600 and is letterboxed without changing its aspect ratio. Offscreen surfaces have independent IDs and are bounded by dimension/pixel limits.

The VM consumes a typed IR (`Hsp3Instruction`). `Hsp3BytecodeDecoder` is now the default decoder in `Hsp3RuntimeAdapter.createSession`. It reads command heads and argument groups from CS, builds postfix expression trees, and resolves OT labels to command boundaries. Implemented command coverage includes basic assignments, goto/gosub/return, repeat/loop/break/continue, wait/await, if/else branches, getkey, and the initial graphics/input command subset (mes, pos, font, picload, color, redraw, gsel, gcopy, gmode, screen, buffer, boxf, stick).

The decoder is intentionally strict. Unknown command types/IDs are preserved as unsupported VM instructions or rejected rather than guessed. Expressions currently cover scalar integer/double/string literals, scalar variable references, selected system variables and the OpenHSP postfix binary operators. Array indexing, function-call expressions, module/DLL tokens, dynamic repeat expressions, and the full HSP variable model are not implemented. The Android host schedules bounded VM slices and wait delays; it does not emulate every platform service offered by Windows OpenHSP.

## Detection and user-facing behavior

`VisualNovelDetector` recognizes an HSP3 game only when an `.exe` in the imported folder passes the DPMX parser and its `start.ax` passes key resolution, HSPHED validation and token iteration.

The detector now requires the CS decoder to complete before reporting `PARSER_AVAILABLE`. The library shows a Launch button for `hsp3-dpmx` and starts `Hsp3PlayerActivity`, which creates a Canvas-backed 800x600 surface, runs bounded VM slices, and overlays D-pad / Enter-Z / Esc-X / Space touch controls. This proves the Android launch path is wired; it does not prove that an arbitrary HSP3 title or Demonophobia's complete script is compatible.

## Tests added

Synthetic fixtures cover:
- dynamic PE raw-section overlay calculation;
- entry parsing, entry range checks and PE VA-to-file-offset mapping;
- stateful decryption across very small read buffers;
- known `start.ax` vector and BMP signature auto-detection;
- HSPHED fields, CS/DS/OT/DI reads, narrow/wide tokens, EXFLG command heads, Shift-JIS strings, double literals and word-based OT label offsets;
- postfix arithmetic/bitwise/comparison expression decoding, argument boundaries, getkey and decoded command-to-VM dispatch;
- malformed HSP header/segment/token rejection;
- VM graphics dispatch, typed expression evaluation, assignments, repeat/gosub, stick rising-edge behavior, wait yields and unsupported-command failure;
- DPMX-to-HSP preparation and detector status.

The fixtures are generated by unit tests; they do not contain or redistribute Demonophobia assets.

## Remaining work before claiming Demonophobia compatibility

1. Recover and independently validate the exact PE `.data` key arithmetic at the supplied virtual-address range. The strategy hook is present, but the formula was not supplied and must not be guessed.
2. Validate the decoder against real HSP 3.1 `start.ax` files. Current command/argument support is a useful subset, not the entire OpenHSP grammar: arrays, function-call expressions, module/DLL tokens, additional system variables and dynamic repeat expressions remain unsupported.
3. Decode the additional graphics/input operations actually used by `demono.hsp`, including `gzoom`, `grotate`, `celput`, other gmode variants and any additional drawing state.
4. Add the filesystem, audio, save/load and other platform-facing services needed by the target script.
5. Run an end-to-end test with an unmodified Demonophobia installation and verify the first scene, resource lookups, branching, input and wait timing on an Android device.

Until those steps pass, the implementation should be described as **HSP3.1/DPMX parser and runtime foundation**, not full Demonophobia compatibility.

## References

- [OpenHSP HSP3 structure definitions](https://github.com/onitama/OpenHSP/blob/3dbb8729dad53ee876fc681696c8fe542698860b/src/hsp3/hsp3struct.h)
- [OpenHSP HSP3 graphics command dispatcher](https://github.com/onitama/OpenHSP/blob/3dbb8729dad53ee876fc681696c8fe542698860b/src/hsp3dish/hsp3gr_dish.cpp)
