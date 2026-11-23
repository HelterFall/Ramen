# LiveMaker compiled LSB format notes

## Versioning

The compiled `.lsb` version is independent of the VF archive version. The parser accepts compiled LSB versions `103` through `117`. Versions `103`–`116` map to LiveMaker 2; version `117` maps to LiveMaker 3. A `vf` archive can therefore be VF version 102 while containing scripts whose LSB version is 103 or later.

Primary format reference: `pylivemaker`'s published `livemaker.lsb.lmscript`, `livemaker.lsb.command`, and `livemaker.lsb.core` source:
- https://pylivemaker.readthedocs.io/en/latest/_modules/livemaker/lsb/lmscript.html
- https://pylivemaker.readthedocs.io/en/latest/_modules/livemaker/lsb/command.html
- https://pylivemaker.readthedocs.io/en/latest/_modules/livemaker/lsb/core.html

## File structure

| Field | Encoding |
|---|---|
| Version | UInt32 little-endian |
| Flags | UInt8 |
| Command-type map count | UInt32 little-endian |
| Parameter-stream byte count | UInt32 little-endian |
| Command parameter map | `map count` byte arrays, each `parameter-stream byte count` bytes |
| Commands | UInt32-prefixed array of command records |

Each command-parameter map byte is expanded least-significant-bit first. These maps describe the property arguments carried by component-creation commands; they are not a command count.

Every command record begins with:

| Field | Encoding |
|---|---|
| Command type | UInt8 |
| Indent | UInt32 little-endian |
| Mute | Boolean byte |
| NotUpdate | Boolean byte |
| LineNo | UInt32 little-endian |
| Command-specific body | Depends on command type and LSB version |

Command records do not have an independent record-size prefix. A parser that does not know a command's body layout cannot safely skip that command and continue with the next one. Ramen therefore returns a partial parse when it encounters an unknown command type.

## Expressions

A `LiveParser` expression is a UInt32-prefixed array of operations. Each operation contains:

- operation type byte;
- CP932 PascalString result name;
- UInt32 operand count;
- a function identifier byte when the operation type is `Func` (11);
- the operands.

Each operand starts with a type byte: `0` variable (CP932 PascalString), `1` signed Int32, `2` 10-byte IEEE extended float, `3` flag byte, or `4` CP932 PascalString. Ramen currently preserves the 80-bit float bytes; the initial VM does not evaluate them yet.

## Selected command bodies implemented in the parser

| Command type | Body |
|---:|---|
| 0–1 | One expression (`If`, `Elseif`) |
| 2 | No arguments (`Else`) |
| 3, 27 | CP932 PascalString (`Label`, `Comment`) |
| 4 | Label reference (page + UInt32 label), then condition expression (`Jump`) |
| 5 | Label reference, result variable, condition expression, prefixed expression array (`Call`) |
| 6 | Expression (`Exit`) |
| 7 | Condition and time expressions, plus StopEvent for versions above 106 (`Wait`) |
| 8–12, 23–25, 30, 36–37, 42–44, 48–54, 56 | Component properties selected by the command parameter map |
| 13 | Flip transition expressions and version-gated optional fields |
| 14 | Expression (`Calc`) |
| 15 | CP932 variable name, type byte, initial-value expression, scope byte (`VarNew`) |
| 16 | CP932 variable name (`VarDel`) |
| 17–18 | GetProp / SetProp expressions and result variable |
| 19, 26, 28, 39, 55 | One expression |
| 20 | Length-prefixed compiled TpWord block, target/history/wait expressions, optional StopEvent (`TextIns`) |
| 21 | MovieStop expressions, optional StopEvent |
| 22, 45–47, 61–63 | No-argument commands |
| 29 | Four CallHist expressions, optional FormatName for versions above 110 |
| 31–35 | While / loop expressions and loop index fields |
| 38 | GameSave slot expression, page, version-gated label, caption expression |
| 40–41 | Label reference and all-clear byte |
| 57 | Six PropMotion expressions, optional Paused expression for versions above 107 |
| 58 | FormatHist name expression, optional target for versions above 110 |
| 59–60 | Component properties, Act expression, prefixed target-expression array |

The parser preserves command metadata, expressions, strings, integer fields, component-property IDs, and raw TpWord bytes. It does not yet parse TpWord glyphs into display runs.

## Initial VM scope

`LiveMakerVirtualMachine` is a host-driven execution core, not a complete player.

Currently implemented in the VM:
- a bounded step loop;
- variable creation/deletion;
- a growing expression subset (literal/variable values, assignment, array indexing, arithmetic, comparisons, logical/bitwise operations, string concatenation, and a bounded set of pure string/math functions);
- `TextIns` surfaced to the host as raw TpWord bytes plus target/history expressions;
- `If` / `Elseif` / `Else` branch selection by command indentation;
- `WhileInit` / `While` / `WhileLoop` execution, plus conditional `Break` / `Continue` transfers;
- conditional `Exit` and timed `Wait` events; `StopEvent` independently controls event-loop processing while waiting;
- cross-page `Jump` / `Call` handoff events (including preserved call-argument expressions), a call stack, and host-driven script loading/return;
- `LiveMakerGameSession` coordinates the VM with loose `.lsb` pages and bounded on-demand reads of `.lsb` entries from VF v102 archives; target `LineNo` values are resolved to command-array indices.

Not implemented as engine semantics yet:
- fully validated behavior against commercial LSB samples and uncommon nested control-flow edge cases;
- the complete LiveParser operator/function library, host-side function adapters, and 80-bit float evaluation;
- TpWord glyph decoding and text layout;
- graphics, component lifecycle, transitions, audio, menus, input, save/load, and resource resolution.

Commands outside the VM subset are returned to the host as `HostCommandRequired`; the VM does not silently pretend they ran. The first host can choose to implement a command or stop execution.

## Ramen files and tests

- Parser: `app/src/main/java/io/github/helterfall/ramen/inspection/LiveMakerLsbParser.kt`
- VM: `app/src/main/java/io/github/helterfall/ramen/runtime/livemaker/LiveMakerVirtualMachine.kt`
- Page repositories and session: `app/src/main/java/io/github/helterfall/ramen/runtime/livemaker/LiveMakerGameSession.kt`
- Synthetic parser fixture: `app/src/test/java/io/github/helterfall/ramen/inspection/LiveMakerLsbFixture.kt`
- Parser tests: `app/src/test/java/io/github/helterfall/ramen/inspection/LiveMakerLsbParserTest.kt`
- VM tests: `app/src/test/java/io/github/helterfall/ramen/runtime/livemaker/LiveMakerVirtualMachineTest.kt`

Tests use synthetic records and do not include any commercial game content. A successful synthetic test run establishes parser/VM behavior for the tested subset only; it does not prove whole-game compatibility.