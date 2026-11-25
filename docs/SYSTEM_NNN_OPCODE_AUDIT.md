# System-NNN opcode and runtime audit

**Audit branch:** `audit/system-nnn-compat-20261126`  
**Baseline:** `effects/audit-flash-cloud-haikeiyure-20261107` at `7f215fe332dd6fa7c8c8684bdaa25d7e5e184930`  
**Reference:** [tinyan/SystemNNN](https://github.com/tinyan/SystemNNN), especially `nnnUtilLib/scriptDefine.h`, `nnnLib/execscript.cpp`, and `nyanEffectLib/`.  
**Scope:** source-level audit of the current Ramen System-NNN parser, decoder, VM, image/audio resource path and text path. Counts in the request are treated as user-supplied scan data; the 20 raw commercial `.spt` files are not present as independently readable repository fixtures, so this branch does not claim to have re-run that scan.

## Executive findings

1. The SPT reader already performs byte-wise XOR-`0xFF` de-masking, validates a signature, preserves bounded records and checks record boundaries. The current Ramen format profile is **not** the one stated in the request: it uses a 64-byte fixed header, `SPTHEADER[0-9]` at byte offset `0x30`, then four DWORD preamble/trailer words in the command region. The supplied description instead says 80 bytes and `SCENARIO HEADER`. These profiles cannot safely be merged without a raw target SPT proving the alternate header layout.
2. The requested matrix is not 48 pairs as written. Counting the listed entries gives **51**: 1 + 18 + 1 + 1 + 6 + 24. In addition, several of the supplied names do not match the public SystemNNN constants.
3. Confirmed missing runtime behavior includes native GOTO (`0x55560020`), OVERRAP (`0x22220005`), STARTMESSAGE/STARTKOMA (`0x3333001b/1c`), and several configuration/message functions. GOTO is implemented on this branch with absolute DWORD-to-byte conversion and record-boundary validation. The other commands remain explicitly unsupported until their target callback semantics and operands are confirmed.
4. The Compose renderer stages visual layer changes and publishes them at a DRAW or dialogue event. Native `STARTMESSAGE` / `STARTKOMA` are currently skipped as unknown system functions, so the target's per-message frame-commit behavior is not yet reproduced.
5. The image path already handles indexed GPK/GPK metadata, JPEG/PNG/BMP, packed DWQ BMP paths, RGB scanlines, an additional alpha-mask path, and BMP RLE fallbacks. This is source/test coverage, not proof that every commercial DWQ variant has been visually accepted on device.
6. MML/WGQ/VAW handling exists, including loose VAW/WGQ PACKTYPE profiles and Android playback. Playlist segments are prepared sequentially with MediaPlayer; gapless native-equivalent playback is not established.
7. The SPT text pool currently uses Shift-JIS decoding. XTX/MML uses CP932/Windows-31J. CP1251 detection, the 18-byte `sjis_ext.bin` substitution map, and root-level `Neuton.ttf` are not integrated.
8. Resource discovery previously descended into `!eng files/` and could merge alternate-bank files into the selected install. This branch prunes that backup directory and common developer fixture directories; regression coverage was added.

## Critical mapping conflicts

The following are direct disagreements between the supplied opcode labels and the public reference's `scriptDefine.h`. The code's numeric values are not interchangeable:

| Supplied interpretation | Public SystemNNN definition | Audit conclusion |
|---|---|---|
| `0x55560007 = GOTO` | `0x55560007 = RETURN`; `0x55560020 = GOTO` | Ramen already implements RETURN. This branch adds the actual GOTO code. |
| `0x55560008 = GOSUB` | `0x55560008 = CASE` | The current VM uses CASE/SELECT branch handling here. |
| `0x55560009 = RETURN` | `0x55560009 = SUB` | Ramen implements local SUB with a bounded return stack. |
| `0x5556000a/0b = load/jump to another SPT` | `0x5556000a = SCRIPT`, `0x5556000b = EXIT`; `0x55560015 = SCRIPTJUMP`, `0x55560016 = SUBSCRIPT` | SCRIPT/SCRIPTJUMP/SUBSCRIPT are implemented; EXIT ends the run. |
| `0x5556000c = SELECT` | `0x5556000c = ENDFILM` | Ramen advances film flow; SELECT is `0x22220006`. |
| `0x22220004 = SET_NAME` | `0x22220004 = DRAW` | Ramen implements the timed DRAW boundary. |
| `0x22220005 = VOICE_SYNC` | `0x22220005 = OVERRAP` | Ramen currently logs and skips this unimplemented transition command. |
| `0x22220006 = text formatting` | `0x22220006 = SELECT` | Ramen decodes choice options and CASE branches. |
| `0x33330011 = voice playback` | `0x33330011 = PREPAREOVERRAP`; voice is `0x33330008` | Voice resource playback is implemented at `0x33330008`. |
| `0x33330015/16 = layer clears` | `0x33330015 = LOADDWQ`, `0x33330016 = SETEFFECTRECT` | LOADDWQ and layer rectangles are implemented; clearing is `0x33330012/14`. |

**Important:** this is a title-specific compatibility request, so a different target build could theoretically use different code meanings. However, those differences must be backed by the scanned SPT bytes and/or the target executable's callbacks. Applying the supplied labels as if they were the public engine definitions would corrupt control flow and layer state.

## Coverage matrix for the listed pairs

Status terms:
- **Implemented** — decoded into a VM operation and handled by the player.
- **Partial** — bounded decoding exists, but full native semantics are not reproduced.
- **Skip/log** — the record is length-validated and advanced past, with unsupported-command diagnostics; it is not implemented.
- **Data/NOP** — intentionally does not produce a visible runtime event.

### `0x66660001` — DATA

| Code | Reference meaning | Ramen status | Notes |
|---|---|---|---|
| `0x55550002` | DATA/TABLE record | Data/NOP | Preserved as a bounded DATA record; skipped by the VM. It is not treated as an executable operation. |

### `0x66660002` — CONTROL

| Code | Public reference meaning | Ramen status | Notes |
|---|---|---|---|
| `0x55560001` | NOP | Implemented | Advances to the next validated record. |
| `0x55560002` | IF | Implemented | Bounded RPN evaluation and brace-span skipping. |
| `0x55560003` | ELSIF | Implemented | Evaluated as part of the IF chain. |
| `0x55560004` | ELSE | Implemented | Enters/skips the associated bounded block. |
| `0x55560005` | END | Implemented | Controller reset/return-to-controller path. |
| `0x55560006` | NEXT | Implemented | Returns from the active module frame. |
| `0x55560007` | RETURN | Implemented | Local SUB return; this is **not** GOTO in the public source. |
| `0x55560008` | CASE | Implemented | Used by SELECT option dispatch; this is **not** GOSUB. |
| `0x55560009` | SUB | Implemented | Descriptor-3 target lookup plus bounded local return stack. |
| `0x5556000a` | SCRIPT | Implemented | Module transfer through project catalog. |
| `0x5556000b` | EXIT | Implemented | Terminates the current run; this is not SCRIPTJUMP. |
| `0x5556000c` | ENDFILM | Implemented | Sequential film boundary / scene replay advance. |
| `0x5556000e` | ENDKOMA | Partial | Currently skipped. It does not itself yield a click-wait event. |
| `0x55560013` | ENDIF | Data/NOP | Skipped after branch processing. |
| `0x55560015` | SCRIPTJUMP | Implemented | Replaces the active module frame. |
| `0x55560016` | SUBSCRIPT | Implemented | Nested module transfer. |
| `0x0a00007b` | Open brace | Implemented | Recognized as expression-family `0xA000`, code `0x7b`; used by IF block skipping. |
| `0x0a00007d` | Close brace | Implemented | Recognized as expression-family `0xA000`, code `0x7d`. |

The public engine's GOTO is the additional code `0x55560020`, not one of the listed meanings. This branch now handles that record as an absolute DWORD offset in the de-masked SPT image, converts it to bytes, and rejects targets that are not validated record starts.

### `0x66660004` — FUNCTION

| Code | Supplied function names | Ramen status | Notes |
|---|---|---|---|
| `0x00000002` | `adjustdate`, `setcg`, `stopf5`, `midokuka` | Partial | The inspected title evidence maps function slot 2 to `stopf5`; only the exact zero-argument stopf5 shape is recognized, currently with no gameplay skip-mode state to change. The other named functions are not safely inferable from this single code value and remain unsupported. |

### `0x66660005` — CALCULATION

| Code | Reference meaning | Ramen status | Notes |
|---|---|---|---|
| `0x00000000` | CALCU / flags | Partial | RPN integer/variable expressions, assignment, arithmetic, logical and comparison operators are implemented for observed token families. Unknown token/operator families are skipped with diagnostics; not every possible script operator is supported. |

### `0x66660006` — SYSTEM COMMAND

| Code | Public reference meaning | Ramen status | Notes |
|---|---|---|---|
| `0x22220001` | PRINT | Implemented | Resolves primary text-pool reference and yields a dialogue event. |
| `0x22220002` | LPRINT | Implemented | Uses the same validated message reference path. |
| `0x22220003` | APPEND | Implemented | Uses the same validated message reference path; appends are not yet rendered as a fully native message buffer. |
| `0x22220004` | DRAW | Partial | Nonzero frame count yields a timed frame boundary; zero count continues inline. |
| `0x22220005` | OVERRAP | Skip/log | No transition-event type or native overrap renderer is wired into the player. |
| `0x22220006` | SELECT | Implemented | Choice options are resolved and mapped to CASE records. |

### `0x66660007` — SYSTEM FUNCTION

| Code | Public reference meaning | Ramen status | Notes |
|---|---|---|---|
| `0x33330007` | MUSIC | Implemented | Track number and loop operand decoded; asset manager resolves indexed/loose audio sources. |
| `0x33330008` | VOICE | Implemented | Pool 2 resource and channel decoded; indexed VPK and validated loose resources are supported. |
| `0x33330009` | SOUND | Implemented | Sound-list slot and channel decoded. |
| `0x33330011` | PREPAREOVERRAP | Skip/log | Transition preparation callback is not implemented. It is not the VOICE opcode. |
| `0x33330012` | CLEARALLEFFECT | Implemented | Clears active image-layer and screen-effect state. |
| `0x33330013` | SETEFFECT | Implemented | Layer effect ID and native operands preserved; visual fidelity varies by effect. |
| `0x33330014` | CLEAREFFECTLAYER | Implemented | Removes a selected layer and its layer-effect configuration. |
| `0x33330015` | LOADDWQ | Implemented | Resolves a Pool 2 DWQ resource into the selected layer. |
| `0x33330016` | SETEFFECTRECT | Partial | Source/destination rectangles and interpolation state are preserved; malformed rectangles fail open with diagnostics. |
| `0x33330017` | NOMESSAGEWINDOW | Skip/log | Message-window visibility state is not implemented. |
| `0x33330018` | SETCG | Skip/log | CG selection/state callback is not implemented. |
| `0x3333001a` | SETFILM | Implemented | Validated against the descriptor-3 film-table interval. |
| `0x3333001b` | STARTMESSAGE | Skip/log | No explicit native message-start event/state transition. |
| `0x3333001c` | STARTKOMA | Skip/log | No explicit per-koma frame transaction/commit event. This is a likely contributor to frame sequencing differences and requires operand/callback validation. |
| `0x3333001e` | SETDEMOFLAG | Skip/log | Demo-mode state is not represented. |
| `0x33330028` | FRAMECONTROL | Skip/log | Native frame pacing/default-frame control is not represented. |
| `0x33330050` | FILMNAME | Skip/log | Native metadata/configuration callback is not wired. |
| `0x33330051` | STORYNAME | Skip/log | Also appears as a special reserved descriptor-3 table target; do not treat that table marker as a normal story-name callback without interval context. |
| `0x33330052` | FILMTYPETIME | Skip/log | Also appears as the descriptor-3 continuation marker. Runtime metadata handling is not implemented. |
| `0x33330053` | CONFIGMASK | Skip/log | Native configuration-mask state is not represented. |
| `0x33330057` | AUTOMESSAGE | Skip/log | Auto-message state is not represented. |
| `0x33330058` | CANNOTCLICK | Skip/log | Click gating is not represented. |
| `0x33330059` | CANNOTSKIP | Skip/log | Skip gating is not represented. |
| `0x3333005a` | OPTIONOFF | Skip/log | Option/menu visibility state is not represented. |

The user-provided text gives different names for several of these functions. The public source mappings above were used for the implementation audit because they are directly inspectable; the 20-file frequency counts alone do not prove changed semantics.

## Non-opcode implementation review

### SPT structure and roots

- XOR-`0xFF` on every byte: implemented.
- Current signature: `SPTHEADER[0-9]` at `0x30`; current fixed header size: `0x40` (64 bytes).
- Current command stream: four DWORD preamble words, bounded variable-length records, four DWORD trailer words. Record length is DWORD count and is validated before reads/jumps.
- Current text pools: two indexed pools with aligned NUL-terminated records. Raw bytes and decode-error state are retained.
- Resource discovery: backup and developer fixture directories are now pruned before recursive discovery. Nested independent System-NNN installations remain excluded to avoid merging their bank indexes.
- `init/`, `movie/`, root `Neuton.ttf`, and title-specific init configuration are not fully consumed by the gameplay runtime.

### Visual transactions, z-order and effects

- Layer state is accumulated in a map keyed by native layer number and sorted by `layerIndex` before Compose rendering.
- `CLEARALLEFFECT` and `CLEAREFFECTLAYER` clear both image and associated effect configuration; a scene snapshot also replaces the pending visual set.
- Scene changes are committed at DRAW/dialogue boundaries. Native `STARTMESSAGE` and `STARTKOMA` are currently unimplemented, so the requested exact 8,307 STARTKOMA transactions are not yet matched.
- Existing effect implementation is an explicit subset. See [SYSTEM_NNN_EFFECT_REFERENCE.md](SYSTEM_NNN_EFFECT_REFERENCE.md) for implemented parameters and known approximations. A registered native effect ID is not by itself proof of correct Android rendering.

### DWQ/GPK image decoding

The current decoder uses PACKTYPE metadata rather than guessing from payload bytes. It supports JPEG/PNG/BMP through bounded Android decoding, sequential DWQ RGB rows, an independent alpha-mask path, packed-DWQ BMP decoding, and a bounded BMP fallback including RLE cases. Output is copied into `ARGB_8888` bitmaps. This still needs representative-image visual tests from the actual commercial data for every observed pack-level/alpha variant; the absence of a synthetic test failure does not prove native pixel parity.

### WGQ/VAW audio

- Loose `.vaw` and `.wgq` readers validate the 64-byte wrapper and PACKTYPE fields.
- WAVE, differential PCM WAVE, Ogg PACKTYPE=2, and Ogg PACKTYPE=6 payloads have bounded materialization paths.
- MML playlist parsing and WGQ segment playback exist, but Android MediaPlayer prepares segments sequentially; exact native timing/gapless playback and audio-focus behavior remain unverified.
- Indexed voice VPK/VTB resolution preserves bank identity and avoids inventing a global index across multiple banks.

### Text and fonts

- SPT pool decoder: Shift-JIS with replacement/error flag.
- XTX/MML name-list decoder: CP932/Windows-31J with UTF-8 BOM handling.
- CP1251 fallback, `sjis_ext.bin` 18-byte substitution semantics and `Neuton.ttf` loading are absent. Do not apply a heuristic CP1251 fallback to every undecodable string: a byte sequence may be valid in CP932 and silently become mojibake.

## Changes on this branch

- Added native `0x55560020` GOTO handling. Target is interpreted as an absolute DWORD offset in the de-masked SPT image; only validated command-record boundaries are accepted.
- Added tests for valid GOTO and rejection of a jump into a record payload.
- Excluded `!eng files/` and common local fixture directory names from recursive resource discovery.
- Added an asset-manager regression test proving backup/test fixture banks are not merged into the selected game's resource catalog.
- Added this compatibility matrix, including the conflicts between the request's opcode labels and the public reference constants.
- Added this branch to Android unit-test and debug-APK workflow push triggers.

## Validation boundary

The earlier visual-effects branch has an APK-only Actions run that succeeded, but that workflow does not execute unit tests. This branch enables the Android unit-test workflow and APK workflow on push. Final green status must be taken from the new Actions runs for the resulting branch HEAD. No physical-device visual/audio acceptance is claimed by this source audit.
