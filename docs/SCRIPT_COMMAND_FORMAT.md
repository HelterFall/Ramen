# Ramen synthetic Script v1

## Scope

This document specifies an original binary fixture used to test Ramen's command parser and bridge into the engine-neutral Scenario runtime. The eight-byte magic is the complete header; command opcodes follow immediately. The opcode values and operand layouts below are explicit Ramen test-fixture decisions, not a claim about the proprietary sample's script format.

The format is read-only and contains no executable/native payloads or expression language.

## Header

All numeric fields are little-endian.

| File offset | Size | Field | Value |
|---:|---:|---|---|
| 0 | 8 bytes | Magic | EB FF FF FF FE FF 99 99 |

The eight-byte magic is the complete header. The first command opcode is at absolute file offset 8; there is no version or encoding-selector byte. The text charset is selected by the caller/engine adapter (UTF-8 by default in the Ramen test player).

## Commands

Each command begins with a one-byte opcode.

| Opcode | Command | Operand layout |
|---:|---|---|
| 0x01 | DisplayDialogueCommand(text) | u16 byte length, then text bytes in the caller-selected charset |
| 0x02 | ChangeBackgroundCommand(imagePath) | u16 byte length, then path bytes in the caller-selected charset |
| 0x03 | JumpToSceneCommand(targetAddress) | u32 absolute file offset of the target command |
| 0xFE | NitroStartSceneCommand | no operands; start-scene marker, followed by the next opcode |

String lengths are unsigned 16-bit values. The current implementation requires a non-zero length and caps each string payload at 4,096 bytes. Dialogue is also capped at 4,096 decoded Kotlin characters.

Background paths must be relative, slash-separated resource keys. Each segment is 1–64 Unicode letters/digits, underscores, periods, or hyphens. Empty segments, absolute paths, control characters, and . / .. segments are rejected.

Jump addresses are unsigned 32-bit little-endian file offsets measured from the beginning of the complete Script file, including its eight-byte magic. A jump must point exactly to the first byte of a decoded command; an address into an operand, the header, or EOF is malformed.

## Stream termination and malformed data

- EOF immediately before the next opcode is a normal end-of-stream condition. The parser returns null and the loader ends the program.
- EOF after an opcode but before all operands/string bytes are read is malformed.
- Unknown opcodes, zero/oversized string lengths, invalid text in the caller-selected charset, unsafe background paths, unknown jump addresses, and a stream with no executable commands are malformed.
- Opcode 0xFE is treated as a zero-operand Nitro start-scene marker. It creates a no-op scene that transitions to the next decoded instruction; repeated markers are bounded by the existing command and automatic-transition limits. This interpretation is a compatibility hypothesis based on the observed marker position, not a complete description of every Nitroplus SPT command.
- A script may contain at most 10,000 commands.

There is no separate end opcode in v1; the end of the stream terminates the program.

## Ramen integration

ScriptCommandParser is stateful over one caller-owned InputStream. It exposes parseNextCommand(stream): TextScriptCommand? and must be reused for consecutive reads from the same stream. The parser does not close the stream.

ScriptScenarioLoader validates the eight-byte magic and reads opcodes immediately afterward until clean EOF. It maps each command's absolute byte offset to a stable scenario ID addr_<offset>, validates jump targets against those IDs, and translates dialogue/background/jump operations into ScenarioCommand.Dialogue, ScenarioCommand.SetBackground, and ScenarioCommand.Jump. NitroStartSceneCommand is represented by an automatic jump to the next decoded instruction. Sequential instructions link to the next address. The resulting ScenarioProject is executable by the existing ScenarioSession and uses its automatic-transition guard.

The experimental Android player selects 01.spt first, then conventional initializer names (init.spt, startup.spt, main.spt); if no .spt is present it looks for the exact eight-byte magic in the bounded source snapshot. It starts a ScenarioSession from the loaded project and displays the current dialogue directly in a Compose text panel. Background resource keys are resolved across discovered GPK/GTB image pairs: exact key, basename, then basename without extension. The chosen GPK entry is decoded from its indexed byte range with the bounded Android image decoder. Missing or ambiguous backgrounds produce a status message but do not stop dialogue playback. Unknown opcodes and other ScriptParseException failures are shown as a controlled load error and do not escape the player Activity.

BinarySignatureProbe validates the full Ramen test command structure after matching the magic. A valid magic alone is not considered a valid script. The byte immediately following the magic is passed to the opcode parser without being interpreted as a version; unknown target-engine system opcodes are reported rather than skipped or guessed.

## Optional single-byte XOR transport

The startup resolver supports a fixed, repeating one-byte XOR mask around this fixture format. It derives the candidate key from the raw first byte as `rawFirstByte XOR 0xEB`, then validates the complete eight-byte magic after applying that key. A zero key preserves an unmasked file. The same key is applied to every command opcode, length field, address byte, and text byte through a streaming view; byte positions and absolute jump addresses do not change.

This is a narrow compatibility layer for the observed `0x10` mask, not a general cipher detector. A candidate is accepted only when the decoded header and the complete known command stream validate. The four opcodes listed above remain the only commands currently implemented by this fixture loader; XOR decoding does not establish support for the full proprietary Nitroplus command set.

Some target SPT files may append a clear VFS/resource index after the encrypted command region. `ScriptScenarioLoader` therefore exposes an optional `commandStreamLengthBytes` bound: when the caller has independently established the command-region length, the loader stops before the trailer and leaves those bytes unread. The current startup resolver does not yet derive that boundary from the target VFS layout; it still treats EOF as the command-stream terminator unless a caller supplies the verified bound.

## Non-goals

V1 defines no speaker metadata, labels, choices, variables, conditional branches, compression, checksums, timestamps, resource archive layout, text layout, or proprietary-engine compatibility. The optional single-byte XOR transport described above is a compatibility wrapper around this fixture, not a command-format feature or a secure cipher. The original line-oriented Scenario DSL remains a separate fixture format.
