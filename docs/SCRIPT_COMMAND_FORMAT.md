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

String lengths are unsigned 16-bit values. The current implementation requires a non-zero length and caps each string payload at 4,096 bytes. Dialogue is also capped at 4,096 decoded Kotlin characters.

Background paths must be relative, slash-separated resource keys. Each segment is 1–64 Unicode letters/digits, underscores, periods, or hyphens. Empty segments, absolute paths, control characters, and . / .. segments are rejected.

Jump addresses are unsigned 32-bit little-endian file offsets measured from the beginning of the complete Script file, including its eight-byte magic. A jump must point exactly to the first byte of a decoded command; an address into an operand, the header, or EOF is malformed.

## Stream termination and malformed data

- EOF immediately before the next opcode is a normal end-of-stream condition. The parser returns null and the loader ends the program.
- EOF after an opcode but before all operands/string bytes are read is malformed.
- Unknown opcodes, zero/oversized string lengths, invalid text in the caller-selected charset, unsafe background paths, unknown jump addresses, and a stream with no executable commands are malformed. The two recognized system markers alone do not count as executable story commands.
- A script may contain at most 10,000 commands.

There is no separate end opcode in v1; the end of the stream terminates the program.

## Ramen integration

ScriptCommandParser is stateful over one caller-owned InputStream. It exposes parseNextCommand(stream): TextScriptCommand? and must be reused for consecutive reads from the same stream. The parser does not close the stream.

ScriptScenarioLoader validates the eight-byte magic and reads opcodes immediately afterward until clean EOF. It maps each command's absolute byte offset to a stable scenario ID addr_<offset>, validates jump targets against those IDs, and translates dialogue/background/jump operations into ScenarioCommand.Dialogue, ScenarioCommand.SetBackground, and ScenarioCommand.Jump. Sequential instructions link to the next address. The resulting ScenarioProject is executable by the existing ScenarioSession and uses its automatic-transition guard.

The experimental Android player selects 01.spt first, then conventional initializer names (init.spt, startup.spt, main.spt); if no .spt is present it looks for the exact eight-byte magic in the bounded source snapshot. It starts a ScenarioSession from the loaded project and displays the current dialogue directly in a Compose text panel. Background resource keys are resolved across discovered GPK/GTB image pairs: exact key, basename, then basename without extension. The chosen GPK entry is decoded from its indexed byte range with the bounded Android image decoder. Missing or ambiguous backgrounds produce a status message but do not stop dialogue playback. Unknown opcodes and other ScriptParseException failures are shown as a controlled load error and do not escape the player Activity.

BinarySignatureProbe validates the full Ramen test command structure only after matching the exact plain eight-byte Ramen fixture magic. A valid magic alone is not considered a valid script. The byte immediately following the magic is passed to the opcode parser without being interpreted as a version; unknown target-engine system opcodes are reported rather than skipped or guessed.

## Proprietary SPT compatibility boundary

The experimental loader is intentionally limited to the exact Ramen-owned eight-byte fixture header above. It does not treat a .spt extension as a format signature, does not auto-detect or apply XOR keys, and does not interpret the byte immediately after an arbitrary SPT header as an opcode.

The GARbro repository's Black Cyc handlers cover GPK/GTB images, VPK/VTB audio, and DWQ image payloads; its ArcFormats/BlackCyc directory does not contain an SPT script parser. Consequently GARbro does not provide the fixed SPT header fields, offset-table location/count, or code-start offset needed to implement the proprietary SPT command region. Ramen keeps those files unsupported rather than inventing header offsets or command operand lengths.

ScriptScenarioLoader retains an optional commandStreamLengthBytes bound for the synthetic fixture only. It is not a parser for a proprietary SPT offset table and does not discover a VFS trailer.

## Non-goals

V1 defines no speaker metadata, labels, choices, variables, conditional branches, compression, checksums, timestamps, resource archive layout, text layout, or proprietary-engine compatibility. The optional single-byte XOR transport described above is a compatibility wrapper around this fixture, not a command-format feature or a secure cipher. The original line-oriented Scenario DSL remains a separate fixture format.
