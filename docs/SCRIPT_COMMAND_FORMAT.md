# Ramen synthetic Script v1

## Scope

This document specifies an original binary fixture used to test Ramen's command parser and bridge into the engine-neutral Scenario runtime. The eight-byte magic was previously recorded as a Ramen test-fixture signature. The version, encoding selector, opcode values, and operand layouts below are explicit Ramen v1 decisions; they are not a claim about the proprietary sample's script format.

The format is read-only and contains no executable/native payloads or expression language.

## Header

All numeric fields are little-endian.

| File offset | Size | Field | V1 value |
|---:|---:|---|---|
| 0 | 8 bytes | Magic | EB FF FF FF FE FF 99 99 |
The eight-byte magic is the complete header. The first command opcode is at absolute file offset 8; there is no version or encoding-selector byte. The text charset is selected by the caller/engine adapter (UTF-8 by default in the Ramen test player).

## Commands

Each command begins with a one-byte opcode.

| Opcode | Command | Operand layout |
|---:|---|---|
| 0x01 | DisplayDialogueCommand(text) | u16 byte length, then text bytes in the header-selected charset |
| 0x02 | ChangeBackgroundCommand(imagePath) | u16 byte length, then path bytes in the header-selected charset |
| 0x03 | JumpToSceneCommand(targetAddress) | u32 absolute file offset of the target command |

String lengths are unsigned 16-bit values. The current implementation requires a non-zero length and caps each string payload at 4,096 bytes. Dialogue is also capped at 4,096 decoded Kotlin characters.

Background paths must be relative, slash-separated resource keys. Each segment is 1–64 Unicode letters/digits, underscores, periods, or hyphens. Empty segments, absolute paths, control characters, and . / .. segments are rejected.

Jump addresses are unsigned 32-bit little-endian file offsets measured from the beginning of the complete Script file, including its eight-byte magic. A jump must point exactly to the first byte of a decoded command; an address into an operand, the header, or EOF is malformed.

## Stream termination and malformed data

- EOF immediately before the next opcode is a normal end-of-stream condition. The parser returns null and the loader ends the program.
- EOF after an opcode but before all operands/string bytes are read is malformed.
- Unknown opcodes, zero/oversized string lengths, invalid UTF-8/Shift-JIS sequences, unsafe background paths, unknown jump addresses, invalid text in the caller-selected charset, and an empty command stream are malformed.
- A script may contain at most 10,000 commands.

There is no separate end opcode in v1; the end of the stream terminates the program.

## Ramen integration

ScriptCommandParser is stateful over one caller-owned InputStream. It exposes parseNextCommand(stream): TextScriptCommand? and must be reused for consecutive reads from the same stream. The parser does not close the stream.

ScriptScenarioLoader validates the eight-byte magic and reads opcodes immediately afterward until clean EOF. It maps each command's absolute byte offset to a stable scenario ID addr_<offset>, validates jump targets against those IDs, and translates dialogue/background/jump operations into ScenarioCommand.Dialogue, ScenarioCommand.SetBackground, and ScenarioCommand.Jump. Sequential instructions link to the next address. The resulting ScenarioProject is executable by the existing ScenarioSession and uses its automatic-transition guard.

The experimental Android player selects 01.spt first, then conventional initializer names (init.spt, startup.spt, main.spt); if no .spt is present it looks for the exact eight-byte magic in the bounded source snapshot. It starts a ScenarioSession from the loaded project and displays the current dialogue directly in a Compose text panel. Background resource keys are resolved across discovered GPK/GTB image pairs: exact key, basename, then basename without extension. The chosen GPK entry is decoded from its indexed byte range with the bounded Android image decoder. Missing or ambiguous backgrounds produce a status message but do not stop dialogue playback. Unknown opcodes and other ScriptParseException failures are shown as a controlled load error and do not escape the player Activity.

BinarySignatureProbe validates the full Script v1 structure after matching the magic. A valid header alone is not considered a valid script.

## Non-goals

V1 defines no speaker metadata, labels, choices, variables, conditional branches, compression, encryption, checksums, timestamps, resource archive layout, text layout, or proprietary-engine compatibility. The original line-oriented Scenario DSL remains a separate fixture format.
