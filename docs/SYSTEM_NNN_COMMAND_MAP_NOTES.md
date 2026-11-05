# System-NNN SPT command-area map

**Date:** 2026-11-05  
**Target test package:** Gore Screaming Show — user-confirmed cleaned build with NSFW material removed  
**Scope:** structural, read-only analysis only. No scenario text is reproduced and no VM behavior is inferred.

## Input and method

- Source archive: user-provided `hui(1).zip`, 1,969,339 bytes, SHA-256 `20a06b948bfb7d5c0fb5b2fc6a02396161c29e8959d1743599f81de3fea93509`.
- The archive contains 20 SPT modules and 10 sidecar/configuration files. Its CRC check passed.
- Each SPT was demasked with the already-validated XOR `0xFF` transform.
- The two indexed string pools were located with the same structural rules as the current Kotlin loader. The scanned region is `0x40 .. firstPhysicalAddressTable` (exclusive).
- The region was inspected on a 4-byte grid. Each cell was displayed diagnostically as `[u16 parameter][u16 candidate marker]`.
- Candidate markers were taken from the independent System-NNN format notes: `6666`, `5555`, `5556`, `3333`, `2222`, `4444`, `8000`, `9000`, `A000`, `A500`.

This interpretation is a diagnostic view, not a VM decoder. The fact that a 16-bit value matches a known marker does not by itself prove an instruction boundary or command meaning.

## Aggregate results

- Total scanned cells: **1,172,604**.
- Cells whose second UInt16 matched one of the ten candidate markers: **395,677** (**33.74%**).
- Per-module candidate-marker density: **26.69%–58.11%**. The range is too broad to treat the entire area as a simple flat array of already-understood opcodes.
- Aggregate candidate-marker counts:

| Candidate marker | Count |
|---|---:|
| `0x6666` | 196,525 |
| `0x3333` | 136,349 |
| `0x2222` | 46,324 |
| `0x5556` | 12,300 |
| `0xA000` | 2,099 |
| `0x8000` | 921 |
| `0x9000` | 560 |
| `0xA500` | 431 |
| `0x5555` | 84 |
| `0x4444` | 84 |

## Four exact adjacent-cell families

Across all 20 modules, these adjacent-cell patterns are exact:

1. `(parameter=7, marker=0x6666)` is immediately followed by a cell whose marker is `0x3333`. There are **136,349** such pairs, and this equals the total number of `0x3333` cells. Every observed `0x3333` has the stated preceding cell.
2. `(parameter=6, marker=0x6666)` is immediately followed by `0x2222`. There are **46,324** such pairs, exactly equal to all `0x2222` cells.
3. `(parameter=2, marker=0x6666)` is immediately followed by `0x5556`. There are **12,300** such pairs, exactly equal to all `0x5556` cells.
4. `(parameter=1, marker=0x6666)` is immediately followed by `(parameter=2, marker=0x5555)`. There are **84** such pairs, exactly equal to all `0x5555` cells; each is followed by a `0x4444` cell.

The values in the parameter field of the second cell vary by family. For the `0x2222` family, the observed second-cell parameters are 1–6. For `0x3333`, 24 distinct values occur (7–90). For `0x5556`, 16 distinct values occur (1–22). The meaning of those parameters is not established.

A secondary pattern also appears: `(parameter=2, marker=0x6666)` is followed by `0xA000` in **1,188** cases; the following cell's parameter is 123 in 594 cases and 125 in 594 cases. This is recorded as a pattern only.

## Pool 1 count cross-check

A stronger count invariant emerged:

> For every one of the 20 modules,  
> **Pool 1 entry count = number of `0x2222` cells whose parameter is 1, 2, or 3.**

- Total Pool 1 entries across the package: **37,383**.
- Total `0x2222` cells with parameter 1, 2, or 3: **37,383**.
- Exact match: **20/20 modules**, including the four modules with an empty Pool 1.
- Other `0x2222` parameters (4, 5, or 6) account for another **8,941** cells and are not included in this equality.

This is a strong structural correlation and a useful lead for mapping Pool 1 references. It does **not** yet prove which particular command cell refers to which particular Pool 1 record, nor what the three parameter values mean.

| Module | Pool 1 records | `0x2222` parameters 1–3 | `0x2222` parameters 4–6 |
|---|---:|---:|---:|
| `01` | 1299 | 1299 | 438 |
| `_staff` | 0 | 0 | 11 |
| `02` | 1074 | 1074 | 337 |
| `03` | 2606 | 2606 | 825 |
| `04` | 816 | 816 | 346 |
| `05` | 900 | 900 | 244 |
| `06` | 191 | 191 | 56 |
| `07` | 924 | 924 | 243 |
| `08` | 302 | 302 | 108 |
| `082` | 2088 | 2088 | 463 |
| `akane` | 6063 | 6063 | 1231 |
| `aoi` | 5571 | 5571 | 916 |
| `backspt` | 0 | 0 | 168 |
| `day` | 0 | 0 | 51 |
| `kiika` | 6416 | 6416 | 1165 |
| `op1` | 32 | 32 | 184 |
| `sys` | 0 | 0 | 0 |
| `yamiko` | 4424 | 4424 | 1012 |
| `yuka` | 4068 | 4068 | 882 |
| `yuka2` | 609 | 609 | 261 |

## Individual Pool 1 reference mapping — exact across all modules

The third 32-bit little-endian cell after the pattern

```
[parameter=6, marker=0x6666]
[referenceKind=1..3, marker=0x2222]
[u32 Pool 1 record index]
```

was tested as a zero-based Pool 1 record index.

**Result: exact for every supplied module.**
- Across the 16 modules with non-empty Pool 1, references in increasing code-area offset order are exactly `0, 1, 2, ..., Pool1Count-1`.
- There are no missing indices, duplicate indices, or descents in any of the 16 modules.
- The four modules whose Pool 1 is empty (`_staff`, `backspt`, `day`, `sys`) contain zero references of these three kinds.
- Package total: **37,383** references for **37,383** Pool 1 records.
- Opaque reference-kind totals: kind 1 = **37,008**, kind 2 = **125**, kind 3 = **250**.

| Module | Pool 1 records | Kind 1 | Kind 2 | Kind 3 | Sequence check |
|---|---:|---:|---:|---:|---|
| `01` | 1299 | 1299 | 0 | 0 | exact 0–1298 |
| `_staff` | 0 | 0 | 0 | 0 | empty / exact |
| `02` | 1074 | 1012 | 19 | 43 | exact 0–1073 |
| `03` | 2606 | 2606 | 0 | 0 | exact 0–2605 |
| `04` | 816 | 812 | 2 | 2 | exact 0–815 |
| `05` | 900 | 839 | 20 | 41 | exact 0–899 |
| `06` | 191 | 191 | 0 | 0 | exact 0–190 |
| `07` | 924 | 866 | 20 | 38 | exact 0–923 |
| `08` | 302 | 302 | 0 | 0 | exact 0–301 |
| `082` | 2088 | 2088 | 0 | 0 | exact 0–2087 |
| `akane` | 6063 | 6051 | 5 | 7 | exact 0–6062 |
| `aoi` | 5571 | 5567 | 2 | 2 | exact 0–5570 |
| `backspt` | 0 | 0 | 0 | 0 | empty / exact |
| `day` | 0 | 0 | 0 | 0 | empty / exact |
| `kiika` | 6416 | 6416 | 0 | 0 | exact 0–6415 |
| `op1` | 32 | 32 | 0 | 0 | exact 0–31 |
| `sys` | 0 | 0 | 0 | 0 | empty / exact |
| `yamiko` | 4424 | 4250 | 57 | 117 | exact 0–4423 |
| `yuka` | 4068 | 4068 | 0 | 0 | exact 0–4067 |
| `yuka2` | 609 | 609 | 0 | 0 | exact 0–608 |
| **Total** | **37,383** | **37,008** | **125** | **250** | **exact** |

This supports treating the third cell as a direct Pool 1 index for this observed reference shape. The 1/2/3 kind values remain opaque: no semantics are assigned to them. This also does not decode the rest of the VM or prove which UI action a referenced string should trigger.

## Read-only scanner added to Ramen

The Kotlin parser now exposes:
- `SystemNnnPool1Reference`: code-area offset, opaque kind, and Pool 1 index.
- `SystemNnnPool1ReferenceScan`: all matches plus a boolean that is true only when the references cover Pool 1 in exact ascending order.
- `SystemNnnScript.scanPool1References()`: bounded 4-byte scan for the exact three-cell reference shape.

The scanner does **not** execute commands, display strings, infer the kind's meaning, or alter player behavior. Synthetic unit tests cover all three kinds, ordering mismatch, unsupported kinds, wrong prefixes and truncated rows. Android CI #312 passed assembleDebug, unit tests, lint and APK upload with this scanner included. The commercial SPT binaries remain outside Git, so the all-20 sample result is preserved in this report rather than as a committed binary fixture. No device test was performed.

## Per-module command-area map

The pair counts below are counts of adjacent cells, not counts of confirmed semantic commands.

| Module | End offset | 4-byte cells | `7/6666 → */3333` | `6/6666 → */2222` | `2/6666 → */5556` | `1/6666 → 2/5555` |
|---|---:|---:|---:|---:|---:|---:|
| `01` | `0x316E8` | 50 602 | 5 674 | 1 737 | 723 | 4 |
| `_staff` | `0x1510` | 1 332 | 171 | 11 | 17 | 5 |
| `02` | `0x27288` | 40 082 | 4 606 | 1 411 | 512 | 4 |
| `03` | `0x68FCC` | 107 491 | 12 360 | 3 431 | 1 519 | 4 |
| `04` | `0x25318` | 38 070 | 4 400 | 1 162 | 564 | 4 |
| `05` | `0x1BC30` | 28 412 | 3 262 | 1 144 | 366 | 4 |
| `06` | `0x5F10` | 6 068 | 665 | 247 | 91 | 4 |
| `07` | `0x1CF94` | 29 653 | 3 413 | 1 167 | 323 | 4 |
| `08` | `0xB708` | 11 698 | 1 375 | 410 | 149 | 4 |
| `082` | `0x3DF68` | 63 434 | 7 495 | 2 551 | 630 | 4 |
| `akane` | `0xA4EC4` | 168 865 | 19 664 | 7 294 | 1 553 | 4 |
| `aoi` | `0x833E4` | 134 377 | 15 654 | 6 487 | 1 166 | 4 |
| `backspt` | `0x1FC94` | 32 533 | 3 810 | 168 | 267 | 5 |
| `day` | `0x5438` | 5 374 | 686 | 51 | 76 | 5 |
| `kiika` | `0xA2FA8` | 166 874 | 19 477 | 7 581 | 1 486 | 4 |
| `op1` | `0x139E4` | 20 073 | 2 428 | 216 | 229 | 4 |
| `sys` | `0x6B4` | 413 | 3 | 0 | 45 | 5 |
| `yamiko` | `0x7C64C` | 127 363 | 14 911 | 5 436 | 1 106 | 4 |
| `yuka` | `0x6EFE0` | 113 640 | 13 258 | 4 950 | 1 182 | 4 |
| `yuka2` | `0x19A68` | 26 250 | 3 037 | 870 | 296 | 4 |

## External cross-check and its limits

An independent public note for a **different game using the System-NNN/NNNLib family** documents the SPT command area as 4-byte aligned cells in the form `[u16 parameter][u16 op]`, and lists the same candidate marker values. Its accompanying scripts validate the XOR transform and the two-pool layout.

References:
- [System-NNN format notes](https://github.com/flamecho/pretrans-extractor/blob/0e8db7f4292b91073fd85f2f73c73daeb7160451/references/engines/system-nnn.md)
- [Independent PC sample parser](https://github.com/flamecho/pretrans-extractor/blob/0e8db7f4292b91073fd85f2f73c73daeb7160451/scripts/cloverlib_extract.py)
- [Independent PSP sample parser](https://github.com/flamecho/pretrans-extractor/blob/0e8db7f4292b91073fd85f2f73c73daeb7160451/scripts/asakiyumemishi_psp_extract.py)

Those references concern another title. Their dialogue/choice classifications and game-specific behavior are **not** imported into this target. Only the binary layout hypotheses were cross-checked against the 20 supplied modules.

## Reference-kind comparison and opaque 0x3333 / 0x5556 families

### Pool 1 reference kinds 1, 2, and 3

The 37,383 Pool 1 references remain an exact one-to-one ascending mapping. The kind tag has this distribution:

| Kind tag | Records | CRLF records | CRLF share | Mean raw bytes | Median raw bytes | Strict Shift-JIS |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 37,008 | 28,587 | 77.25% | 117.86 | 104 | 36,892 / 37,008 |
| 2 | 125 | 91 | 72.80% | 117.44 | 109 | 125 / 125 |
| 3 | 250 | 144 | 57.60% | 136.62 | 123.5 | 250 / 250 |

- All three kinds point into the same Pool 1 and use the same 4-byte-aligned NUL-terminated record structure. There is no evidence of three different storage encodings.
- Kinds 2 and 3 occur only in seven modules: 02, 04, 05, 07, akane, aoi, and yamiko. The other modules use only kind 1 (or have an empty Pool 1).
- Kind sequence transitions across adjacent Pool 1 indices: 1→1 = 36,968; 1→2 = 25; 2→2 = 3; 2→3 = 120; 3→2 = 97; 3→3 = 130; 3→1 = 23; 2→1 = 1.
- Kinds 2 and 3 are therefore clustered rather than uniformly mixed. Kind 3 records have fewer CRLF separators and somewhat greater average length, but these statistics do not establish what kind 1/2/3 mean.

Conclusion: retain the kind field as an opaque value. Do not name it as narration, speaker text, choice, voice, or any UI behavior without a separate cross-reference.

### 0x3333 family — variable-span boundary candidate

Across the 20 modules, there are **136,349** matches of:

~~~
[parameter=7, marker=0x6666]
[parameter, marker=0x3333]
[u16 value N, u16 zero]
~~~

- The third cell has upper UInt16 = 0 and lower UInt16 in the observed range 0–20.
- Its most common lower values are: 1 = 49,092; 2 = 39,639; 0 = 14,119; 9 = 13,808; 3 = 8,550; 5 = 3,725; 4 = 2,514; 6 = 1,489; 12 = 1,027; 16 = 728.
- A candidate continuation cell at start + (N + 4) * 4 contains marker 0x6666 in **122,992 / 136,349** cases (**90.21%**).
- Other values at that candidate position are marker 0x0000 in 12,577 cases, 0xFFFF in 737, and 0x3333 in 43.
- By module, the 0x6666 continuation hit rate ranges from 76.6% to 100%; the low rate is in backspt (2,917/3,810). The sys module has only three samples, all three match.

This strongly suggests that N participates in determining a variable-sized block, but the 9.79% of non-0x6666 cases prevent treating the formula as a universal instruction boundary. The Kotlin scanner therefore reports it only as a **candidate** and preserves the raw N value.

### 0x5556 family — fixed three-cell shape candidate

Across the 20 modules, there are **12,300** matches of:

~~~
[parameter=2, marker=0x6666]
[parameter, marker=0x5556]
[raw UInt32 value]
~~~

- The third cell common raw values are 4 = 6,640; 5 = 2,128; 3 = 1,137; 7 = 891; 0 = 341; 1 = 86; 2 = 80.
- A notable exception is exactly 0xA0000000 in 341 cases.
- The next aligned cell, at start + 12 bytes, contains marker 0x6666 in **10,757 / 12,300** cases (**87.46%**); the remaining 1,543 cases have 0x0000 in the marker half.
- The 0x6666 continuation share ranges from 42.2% in sys (19/45) to 94.6% in yuka2 (280/296).

The three-cell shape is repeatable, and the following-cell position is a useful boundary candidate. It is not a proven fixed-length VM instruction, because 12.54% of observations do not point to a recognized continuation marker.

### Read-only scanner added

The Kotlin API scanOpaqueMarkerPatterns() now reports only exact-prefix matches for the 0x3333 and 0x5556 families. Each result contains:
- code-area offset;
- family marker and its parameter;
- the raw following UInt32 word;
- an optional candidate next-cell offset and its raw marker.

It does not execute, skip, or decode the surrounding bytes. The 0x3333 continuation offset is reported only when the raw N value is within the observed range 0–20 and its upper word is zero. The 0x5556 scanner inspects the cell immediately after its three-cell shape. Synthetic tests cover both patterns, candidate offsets, an out-of-range 0x3333 value, and rejection of a wrong prefix.

## Third/fourth header descriptors and scene.dat cross-check

### scene.dat structure observed directly

The supplied archive contains a 1,164-byte scene.dat file. Its header fields are:
- DWORD 0: 1,164 (file size);
- DWORD 1: version 1;
- DWORD 2: 69 records.

All 69 records parse exactly to EOF as:
~~~ 
[u32 type][u32 valueCount][valueCount × u32 values]
~~~

Observed type counts: type 10 = 4; 11 = 15; 12 = 14; 13 = 16; 14 = 2; 15 = 14; 18 = 2; type 4 = 2. Observed valueCount distribution: 1 = 28; 2 = 10; 3 = 26; 4 = 1; 5 = 4. No text values were extracted or reproduced.

### Descriptor 3

- Descriptor 3 has different count/base pairs in all 20 SPT files; its count is 69 only in 01.spt.
- In every one of the 20 samples, the first decoded DWORD at physical file offset 0x40 equals descriptor3.logicalBase + descriptor3.entryCount.
- The next DWORD at 0x44 varies by module; follow-up analysis of the clean `hui(5).zip` archive shows that it points to the second DATA/TABLE record immediately following the first one.
- Searching the files for a 4-byte-aligned, strictly increasing UInt32 table beginning with descriptor3.logicalBase + descriptor3.entryCount found **no valid table in any of the 20 files** under the same rule that successfully locates Pool 1 and Pool 2.
- The fact that 01.spt descriptor 3 count equals scene.dat record count (69) is an interesting one-file correlation, not proof that the descriptor points to scene.dat. The other 19 descriptor counts differ.

At this initial checkpoint descriptor 3 had not yet been classified; later 20-module analysis below establishes it as a DWORD pointer vector for film targets, not a third string pool.

### Descriptor 4

- Descriptor 4 has base 0 in all 20 files; its count ranges from 1 to 20.
- Because those tables would be very short, searching for short monotonic DWORD sequences produces many incidental matches. No unique descriptor-4 table location was established.
- No relationship between descriptor 4 and the 0x5555/0x4444 marker pair has been proven.

### No direct scene.dat-to-marker mapping yet

A simple equality comparison between scene.dat record type/valueCount fields and the 0x3333/0x5556 marker parameters does not establish a one-to-one mapping. Keep the structures separate until an offset/index path is shown at the byte level.

## What this proves — and what it does not

**Supported by the supplied bytes:**
- 4-byte scanning alignment is consistent across all 20 modules.
- The four adjacent-cell families above are exact across all 20.
- The Pool 1 count equality for `0x2222` parameters 1–3 is exact across all 20.
- Candidate marker counts and command-area boundaries can be reproduced from the archived sample and current pool-location rules.

**Still unknown:**
- The semantic meaning of the four pair families and their parameter fields.
- Whether each `0x2222` pair maps to a Pool 1 entry in simple occurrence order.
- Exact instruction lengths where additional operands/data follow the two-cell prefix.
- Resource/background/audio command references, branching, waits and choices; module transfers are resolved in the later native-executable section below.
- The startup module and true execution order for this specific title.

This paragraph records the state at that earlier diagnostic checkpoint only. Later sections document subsequent parser/VM work and native-confirmed module routing; no device launch or end-to-end result is claimed.

## Next diagnostic step

Use the exact Pool 1 logical-address table to test the 37,383-count relation at the **individual-record level**: inspect bounded words following each `0x2222` pair, determine whether they encode a pool index/address, and check the mapping against all 20 modules. Keep the result as a read-only scanner until the reference rule is proven. Then investigate the `0x3333` and `0x5556` families, followed by module transitions.


## Third-descriptor pointer vector — record-kind cross-check across all 20 modules

A second pass tested the third descriptor as a **DWORD-indexed pointer vector**, rather than as another monotonic logical-address table. For each table index `i`, the pointer cell is read at:

```
physical_pointer_cell = (descriptor3.logicalBase + i) * 4
target_record_offset  = read_u32_le(physical_pointer_cell) * 4
```

Every pointer cell is inside the validated command region, and every target resolves to an exact length-prefixed record boundary.

**The target record type is exact in all 20 modules:**
- The first `descriptor4.entryCount` entries all target a type-7 `SYSTEM_FUNCTION` record with code family `0x3333`, code ID `0x51` (decimal 81).
- Every remaining entry targets a type-7 `SYSTEM_FUNCTION` record with code family `0x3333`, code ID `0x50` (decimal 80).
- The counts match exactly for each module: `descriptor4.entryCount == count(0x3333/0x51)`; `descriptor3.entryCount - descriptor4.entryCount == count(0x3333/0x50)`.
- There are **1,028** table entries across the package: **80** reserved-prefix targets of `0x3333/0x51` and **948** ordinary film-entry targets of `0x3333/0x50`.
- As a separate count correlation, `0x3333/0x52` (decimal 82) occurs exactly as often as `0x3333/0x50` in every module (948 total). Its role is not assigned here.

| Module | Descriptor 3 | Descriptor 4 | `0x3333/0x51` | `0x3333/0x50` | All targets and roles exact |
|---|---:|---:|---:|---:|---|
| `01` | 69 | 2 | 2 | 67 | yes |
| `02` | 53 | 3 | 3 | 50 | yes |
| `03` | 178 | 20 | 20 | 158 | yes |
| `04` | 56 | 2 | 2 | 54 | yes |
| `05` | 37 | 6 | 6 | 31 | yes |
| `06` | 10 | 2 | 2 | 8 | yes |
| `07` | 27 | 2 | 2 | 25 | yes |
| `08` | 15 | 3 | 3 | 12 | yes |
| `082` | 55 | 8 | 8 | 47 | yes |
| `_staff` | 3 | 1 | 1 | 2 | yes |
| `akane` | 103 | 3 | 3 | 100 | yes |
| `aoi` | 86 | 4 | 4 | 82 | yes |
| `backspt` | 23 | 4 | 4 | 19 | yes |
| `day` | 5 | 2 | 2 | 3 | yes |
| `kiika` | 112 | 5 | 5 | 107 | yes |
| `op1` | 23 | 3 | 3 | 20 | yes |
| `sys` | 3 | 3 | 3 | 0 | yes |
| `yamiko` | 59 | 4 | 4 | 55 | yes |
| `yuka` | 96 | 2 | 2 | 94 | yes |
| `yuka2` | 15 | 1 | 1 | 14 | yes |

This confirms the previously documented **reserved-prefix** interpretation of descriptor 4 and the ordinary film-entry portion of descriptor 3 for this sample set. It also explains why `sys.spt` has no ordinary film 0: all three descriptor-3 slots are reserved-function targets.

### Function sidecar cross-check (negative result)

- `function.xtx` is a 79-byte CP932/CRLF file with four name/label rows.
- All 80 `0x3333/0x51` records across the 20 SPTs have two payload DWORDs; the first is 1 and the second is an in-range Pool 2 record index.
- None of these 80 Pool 2 targets is a `project.xtx` module name or a name token from `function.xtx`. The sidecar therefore does not currently identify these records as module transitions or map them to its four named entries.
- The 22 type-4 `FUNCTION` records observed across the package all have code `0x00000002` and one zero payload DWORD. No direct name/ID mapping to `function.xtx` was established.
- Do not assign a system-function meaning from the record code or label file alone.

The Kotlin `filmStartOffset()` reader now validates **every** descriptor-3 pointer and its expected reserved/film record class before returning a requested film target. Synthetic tests cover valid mixed tables, a pointer into a record payload, a reserved slot with the wrong record class, and a film slot that points to a reserved record.

This validates the table structure, not which project module is launched first or what an individual system function does.

## Film interval envelope and Pool 2 module-name references

This section reuses the already validated descriptor-3 pointer vector, bounded record map, project.xtx catalog, and secondary Pool 2 record boundaries. It does not repeat the earlier marker-density or Pool 1 scans, and it does not assign VM behavior to any code.

### Repeated per-film record envelope

The 948 ordinary film slots across the 19 non-system modules partition into 948 intervals when their descriptor-3 targets are sorted by physical record offset. Every interval has the same structural anchors:

- The first record is type 7, code 0x3333/0x50, five DWORDs long; it is the exact descriptor-3 target.
- The immediately following record begins exactly 20 bytes later and is type 7, code 0x3333/0x52, also five DWORDs long.
- Exactly one type-7 code 0x3333/0x1A record occurs later in each interval; it is five DWORDs long.
- The 0x3333/0x1A record is immediately followed by type-2 control record 0x5556/0x0C in all 948 intervals. The current partial decoder labels 0x5556/0x0C as ENDFILM; this observation does not define the behavior of 0x3333/0x1A.
- The record immediately before 0x3333/0x1A is 0x5556/0x0E in 884 intervals. The other 64 have different raw record codes and remain unclassified.

All three SystemFunction records (0x50, 0x52, 0x1A) use the observed two-word payload shape [1, Pool2Index] in this package. The 0x50 and 0x1A indices are unique per film interval; the 0x52 index is constant within each module (one distinct index across all ordinary films of that module). sys.spt has no ordinary film intervals and therefore no ordinary-film 0x50/0x52/0x1A envelope.

This is a structural film-block invariant, not a decoded meaning for function IDs 0x50, 0x52, or 0x1A.

### Exact references to project module-name tokens

A read-only scan checked only records with exactly two payload DWORDs [1, index], where index is in range for that module's Pool 2 and the referenced Pool 2 text exactly equals one of the 20 names in project.xtx.

It found **511** candidate module-name references:

| Record identifier | Candidate references to module-name tokens |
|---|---:|
| type 6, 0x2222/0x0001 | 1 |
| type 6, 0x2222/0x0004 | 123 |
| type 7, 0x3333/0x0007 | 34 |
| type 7, 0x3333/0x0014 | 310 |
| type 7, 0x3333/0x001A | 15 |
| type 7, 0x3333/0x001B | 15 |
| type 7, 0x3333/0x0053 | 13 |
| **Total** | **511** |

For each of the 15 0x3333/0x001A references to a module-name token, exactly one 0x3333/0x001B record in the same source module references the same Pool 2 index. These are not local or adjacent pairs: all 15 corresponding 0x001B records lie in a different ordinary-film interval; in 13 cases 0x001B occurs earlier in the file and in two cases it occurs later. The relation is a repeated same-index reference, not evidence of a paired call. The distinct source-to-target pairs are:

- 01 → yuka
- 03 → yamiko and _staff
- akane → _staff (three distinct Pool 2 entries)
- aoi → _staff (three distinct Pool 2 entries)
- kiika → _staff (three distinct Pool 2 entries)
- yamiko → _staff
- yuka → _staff and yuka2

Separately, the 123 0x2222/0x0004 references to module-name tokens are distributed across 03 → yamiko (15), akane → _staff (13), aoi → _staff (21), kiika → _staff (53), and yuka → yuka2 (21).

These are exact references to project module-name strings, not accidental numeric matches. However, the opcodes may also reference other Pool 2 labels, resources, or jump-target names. Their semantics have not been proven. Do not convert these 511 records directly into module transitions or calls.

### Cross-title NNNLib reference (supporting clue only)

A public analysis of other games using the System-NNN/NNNLib family describes Pool 2 as a mixed table containing choices, scene titles, resource identifiers, and jump-target script names. It also treats project.xtx row order as script loading order and classifies sys/backscript files as system UI in those other titles:

- [System-NNN format notes in pretrans-extractor](https://github.com/flamecho/pretrans-extractor/blob/0e8db7f4292b91073fd85f2f73c73daeb7160451/references/engines/system-nnn.md)
- [Cloverlib extraction reference](https://github.com/flamecho/pretrans-extractor/blob/0e8db7f4292b91073fd85f2f73c73daeb7160451/scripts/cloverlib_extract.py)
- [Asaki PSP extraction reference](https://github.com/flamecho/pretrans-extractor/blob/0e8db7f4292b91073fd85f2f73c73daeb7160451/scripts/asakiyumemishi_psp_extract.py)

Those are different games and extraction tools, not an opcode specification for this title. They support treating the matched Pool 2 module strings as possible jump-target data, but do not establish that 0x3333/0x1A, 0x3333/0x1B, or 0x2222/0x0004 perform a module call.

### Next validation

Compare the payload and control-flow context of module-name references against the same opcodes that point to ordinary Pool 2 labels. Resolve the sys/backspt system UI path only when a concrete dispatch target is proven. No module-navigation behavior is enabled by these findings.

### Module-name references versus ordinary Pool 2 references — negative control

The same exact [1, Pool2Index] payload shape under every one of the seven record codes above also references many Pool 2 entries that are not project module names. The split is:

| Record identifier | Module-name targets | Other Pool 2 targets |
|---|---:|---:|
| type 6, 0x2222/0x0001 | 1 | 15 |
| type 6, 0x2222/0x0004 | 123 | 1,983 |
| type 7, 0x3333/0x0007 | 34 | 452 |
| type 7, 0x3333/0x0014 | 310 | 5,149 |
| type 7, 0x3333/0x001A | 15 | 933 |
| type 7, 0x3333/0x001B | 15 | 24,779 |
| type 7, 0x3333/0x0053 | 13 | 37 |
| **Total** | **511** | **33,348** |

This is a useful negative control: none of these record codes is exclusive to module-name targets. For example, 0x3333/0x001A occurs once per ordinary film interval and is followed by 0x5556/0x000C whether its Pool 2 target is a module name or any other Pool 2 entry. The 15 module-name targets are therefore not enough to call that record a module transfer.

The record-neighbour distributions also overlap for module-name and other Pool 2 targets. For 0x2222/0x0004, the next record is 0x5556/0x000E in 74/123 module-name cases and 1,544/1,983 other-target cases; 0x3333/0x001B likewise has ordinary 0x2222 and 0x3333 successors among both categories. No unique control-flow signature was established.

Finally, the 15 same-index 0x001B references are all in a different ordinary-film interval from their matching 0x001A reference. Their relative order is 13 earlier and 2 later. This explicitly rules out describing the pair as an adjacent command sequence in the current evidence.

Conclusion: retain these as exact Pool 2 cross-references to module-name strings. Until a branch/dispatch target is resolved from the VM's actual control-flow fields, do not enable module navigation or classify these opcodes as calls.


## 2026-11-05 — scene catalog cardinality and module-reference scanner

### Scene metadata from the new user archive

A read-only structural parse of `hui/spt/scene.dat` confirms:
- Header: declared size `1164`, version `1`, row count `69`.
- All 69 variable-length rows parse exactly to EOF.
- Opaque row-type counts: `4:2`, `10:4`, `11:15`, `12:14`, `13:16`, `14:2`, `15:14`, `18:2`.
- Value-count distribution: `1:28`, `2:10`, `3:26`, `4:1`, `5:4`.

No meaning is assigned to row types or values. In particular, numeric similarity alone does not establish a scene-to-film pointer.

The same archive's `hui/nya/scenevoice.xtx` contains exactly 69 unique sequential ASCII identifiers, `@sc01` through `@sc69`. Its lines use CRLF. The new reader scans only these ASCII identifiers and leaves the CP932 header/display text uninterpreted.

For `01.spt`, descriptor 3 has 69 pointer entries and descriptor 4 has a reserved prefix count of 2, leaving 67 ordinary film slots. Thus three independently stored counts are 69:
- `scene.dat` rows: 69;
- `scenevoice.xtx` IDs: 69;
- `01.spt` descriptor-3 entries: 69.

This is a strong cardinality correlation, but **not yet an established index mapping**. The reserved prefix is part of the 69-entry pointer vector; do not subtract it from either sidecar count or assume row 0 maps to pointer slot 0.

### New read-only Kotlin diagnostics

- `SystemNnnSceneCatalogReader` reads the version-1 `scene.dat` row envelope with file-size, row-count, per-row value-count, truncation, and EOF checks. It preserves row types and UInt32 values verbatim.
- The same reader extracts `@scNN` identifiers from `scenevoice.xtx`, including CRLF files, without decoding or copying its non-ASCII text.
- `compareWithFilmTable()` reports matching/mismatching cardinalities and the reserved/ordinary film counts. Its `sameIndexMappingProven` field is deliberately always false until an actual row-to-pointer relationship is demonstrated.
- `SystemNnnModuleReferenceScanner` scans one parsed SPT stream for the exact two-DWORD payload `[1, Pool2Index]` where the indexed Pool 2 bytes equal one of the `project.xtx` module names. Each result preserves source module, target name, record offset, identifier type, code family/id, Pool 2 index, and record length. It is a string-reference candidate, **not a call/jump instruction**.

### Candidate-reference replay against all 20 supplied modules

An independent read-only replay of the scanner predicate against the new archive's 20 `hui/spt` modules found 511 exact module-name references. The per-record-code counts are unchanged from the earlier notes:
- type 6 `0x2222/0x0001`: 1;
- type 6 `0x2222/0x0004`: 123;
- type 7 `0x3333/0x0007`: 34;
- type 7 `0x3333/0x0014`: 310;
- type 7 `0x3333/0x001A`: 15;
- type 7 `0x3333/0x001B`: 15;
- type 7 `0x3333/0x0053`: 13.

The complete candidate source-to-target counts (all seven record codes combined) are:
- `01 → yuka`: 2;
- `03 → yamiko`: 17; `03 → _staff`: 2;
- `akane → _staff`: 49;
- `aoi → _staff`: 173;
- `kiika → _staff`: 213;
- `yamiko → _staff`: 17;
- `yuka → _staff`: 11; `yuka → yuka2`: 27.

The `0x2222/0x0004` subset retains the earlier, narrower edges: `03 → yamiko` (15), `akane → _staff` (13), `aoi → _staff` (21), `kiika → _staff` (53), and `yuka → yuka2` (21).

These are exact references to module-name strings, not proven module transfers. The negative control remains decisive: the same record families also reference many non-module Pool 2 entries. No runtime module navigation has been enabled. Kotlin scanner tests use synthetic records; the 511-reference total above comes from the independent archive replay, not from a committed game binary or a device run.

### Next evidence needed

1. Compare each `scene.dat` row with the same-index descriptor-3 target record and its payload, including the two reserved slots, rather than relying on equal counts.
2. Compare `@scNN` sequence positions with the row and pointer values to establish (or reject) an ordering relation.
3. Use the new scanner's per-record offsets and surrounding film intervals to separate actual dispatch candidates from ordinary labels.
4. Do not add a VM module-call opcode until a target module/film and return behavior are proven across more than one transition.


### Direct-value comparison — negative result

A deliberately narrow comparison was also made between the fifth DWORD of each of the 69 descriptor-3 target records in 01.spt and every UInt32 value carried by the 69 scene.dat rows:
- The 69 pointer-record values are distinct.
- Only one numeric value is shared between the two complete value sets.
- That shared value is not at the same row/slot index.

Therefore, scene.dat is not a direct table of the pointer records' fifth-DWORD values. This does not rule out an indirect/index-based relationship, but it rules out using a naïve numeric equality as the scene-to-film mapping.


### Per-record source-film annotation for candidate module references

The Kotlin diagnostics now expose all validated descriptor-3 entries as `SystemNnnFilmTableEntry` records, preserving the original pointer-table index, the ordinary zero-based film number (null for reserved-prefix entries), and the resolved command offset.

`SystemNnnModuleReferenceScanner` sorts these validated target offsets by physical location and annotates each candidate reference with the nearest preceding table entry:
- `sourceFilmTableIndex` preserves which descriptor-3 slot owns the physical interval;
- `sourceFilmNumber` gives the ordinary film number after the reserved prefix, or null if the candidate is in a reserved prefix interval.

This is a structural ownership annotation only. It does not make a module-name string reference an executable transfer, and it does not identify the target film in the destination module. Synthetic tests cover a reserved entry plus two ordinary film intervals and confirm that candidate records are assigned to their enclosing source intervals.

The next useful diagnostic is to group the 511 candidate references by (source module, source film number, record family/code, target module) and compare same-film control-flow context. No VM call/return behavior should be enabled until the destination film and return convention are independently proven.


### Independent source-film grouping replay over the 20 SPT modules

The source-film annotation predicate was replayed independently against the 20 demasked SPT files in the new archive. Results:
- all **511/511** exact module-name candidates resolve to a preceding ordinary descriptor-3 film entry;
- no candidate falls before the first film entry or into a reserved-prefix interval;
- candidates are distributed across **173 distinct source-film intervals** in **7 source modules**;
- per-source-module totals: `01:2`, `03:19`, `akane:49`, `aoi:173`, `kiika:213`, `yamiko:17`, `yuka:38`.

This validates the structural ownership assignment as a useful way to index the candidate records. It still does not show that a candidate changes modules: the target is just a Pool 2 string that exactly matches a project module name, and several opcode families share the same payload form for non-module labels.

The 15 exact `0x3333/0x1A` references and their same-module/same-Pool2-index `0x3333/0x1B` counterparts remain in different source-film intervals in all 15 cases (13 counterparts physically earlier, 2 later). Source-film grouping therefore does not turn these into a local call/return pair. Preserve both the physical offset and source-film owner; do not pair them as adjacent commands.

A useful next artifact is a deterministic, read-only export grouped by source module → source film → record family/code → target string. It can reveal whether specific candidates consistently sit next to control-flow structures without altering player execution.


### Bounded record context and in-app diagnostic report

The candidate scanner now attaches the previous and next record from the same validated physical film interval. Each context item contains only offset, identifier type, code family/id, record length, and at most the first four opaque payload DWORDs. A neighbor that starts at the next film-table target is deliberately excluded from the current interval.

The Resource Inspector's Text Data tab now offers "Сохранить карту ссылок на модули (.txt)" when one or more sibling project.xtx catalogs are present. Export is performed on the existing worker thread and uses the validated project catalog and SPT parsers. Every catalog is reported separately; per-module read failures are preserved as warnings rather than silently discarded.

The deterministic UTF-8 report groups candidates by project → source module → source film interval → candidate record. It includes adjacent-record metadata and opaque payload previews, but no scenario dialogue, Pool 1 contents, resource payloads, or presumed control-flow labels. A 20,000-candidate cap bounds output. The report explicitly states that matching module-name strings are not proven calls/jumps/startup routes.


## 2026-11-05 — actual script-control records identify module transfers

### External NNNLib family reference (pinned, other title)

A public NNNLib source tree from another title was inspected only as engine-family context:
- `nnnUtilLib/scriptDefine.h` names `0x5556/0x000A` as SCRIPT, `0x5556/0x0015` as SCRIPTJUMP, and `0x5556/0x0016` as SUBSCRIPT.
- It names `0x3333/0x0052` FILMTYPETIME and `0x3333/0x0053` CONFIGMASK, disproving the prior suspicion that 3333/53 is a module call.
- `nnnLib/execscript.cpp` resolves the first transfer operand with `GetStringPtr` (Pool 2). `nnnLib/gamecallback.cpp` constructs `spt\\<name>.spt` and loads that module. In that other-title reference, SCRIPT and SUBSCRIPT enter a deeper script level; SCRIPTJUMP replaces the current level. It copies the DWORD after the Pool 2 operand into `m_stack[253]`, and its `AdjustScriptCall` applies that value during save-state restoration. This is family context only; the target executable below shows a different transfer path.
- The reference initializes its controller with `spt\\sys.spt`.
- Pinned source revision: `tinyan/SystemNNN@78a05053c78b3b80065a9a209c6685a7c60a2e83`. This is family-level context from another title, not a target-specific runtime specification.

### Exact target-archive evidence

A read-only scan of all 20 SPT modules in hui(4).zip found **29** type-2 CONTROL records in code family `0x5556`, IDs `0x000A`, `0x0015`, or `0x0016`. Every record has one DWORD operand that resolves by exact Pool 2 index to a module name declared by `project.xtx`.

| Target code | Count | Source → target module |
|---|---:|---|
| `0x5556/0x000A SCRIPT` | 14 | `sys` → `op1, 01, 02, 03, 04, 05, 06, 07, 08, 082, akane, aoi, kiika, day` |
| `0x5556/0x0015 SCRIPTJUMP` | 3 | `01` → `yuka`; `03` → `yamiko`; `yuka` → `yuka2` |
| `0x5556/0x0016 SUBSCRIPT` | 12 | `03` → `_staff` (1); `akane` → `_staff` (3); `aoi` → `_staff` (3); `kiika` → `_staff` (3); `yamiko` → `_staff` (1); `yuka` → `_staff` (1) |

All 29 targets match project module names exactly; all 14 SCRIPT records are in `sys.spt`. These are materially stronger than the earlier 511 generic Pool 2 string references.

### Corrected interpretation of 0x3333/0x53

The NNNLib family source identifies `0x3333/0x0053` as CONFIGMASK. Thirteen occurrences happened to point at the Pool 2 string `_staff`, but these are not module calls. Keep them in the generic string-reference report only. The other 511 `[1,index]` references likewise remain unclassified.

### Remaining runtime proof

- `sys.spt` is the strongest boot-controller candidate: it contains all 14 SCRIPT references, and the family reference starts its controller from `sys.spt`. Do not enable auto-start until the target-specific control flow is verified.
- The DWORD after the one-word Pool 2 payload is the next record's length. Disassembly of the target `gss.exe` confirms that SCRIPT/SUBSCRIPT/SCRIPTJUMP handlers do not read it as a selector; see the native transfer verification section below.
- CONTROL SUB (`0x5556/0x0009`) and RETURN (`0x5556/0x0007`) must be mapped separately from module SCRIPT/SUBSCRIPT.
- A read-only `SystemNnnScriptTransferScanner` was added. It labels the three code families, resolves exact Pool 2 module names, and captures source offsets/film context. It does not execute a transfer.


## 2026-11-05 — локальные CONTROL SUB связаны с descriptor-3 jump vector

### Семейная семантика NNNLib

В NNNLib reference из другого title CONTROL SUB (0x5556/0x0009) обрабатывается как локальный вызов: первый DWORD payload индексирует descriptor-3 jump vector, второй DWORD сохраняется как локальный SUB ID; переход выполняется к адресу из выбранного descriptor-3 элемента. CONTROL RETURN (0x5556/0x0007) возвращает управление из локального стека. Это семейный reference на pinned revision tinyan/SystemNNN@78a05053c78b3b80065a9a209c6685a7c60a2e83, не самостоятельная спецификация target title.

### Проверка на 20 целевых SPT

Независимый replay по всем модулям из hui(4).zip подтверждает:
- 1 069 / 1 069 CONTROL SUB имеют ровно два DWORD payload;
- 1 069 / 1 069 первых операндов находятся в диапазоне descriptor 3;
- каждый выбранный descriptor-3 элемент указывает на валидную 5-DWORD SYSTEM_FUNCTION запись 0x3333/0x50 (обычная) либо 0x3333/0x51 (reserved);
- второй операнд внутри каждого модуля образует уникальную плотную последовательность локальных ID 0..SUBCount−1 без пропусков и повторов.

Количество SUB по модулям: sys 9, backspt 22, 01 65, 02 50, 03 182, 04 67, 05 37, 06 9, 07 27, 08 16, 082 58, akane 105, aoi 89, kiika 115, yamiko 68, yuka 109, day 8, op1 19, yuka2 13, _staff 1.

CONTROL RETURN встречается 1 008 раз при 1 069 SUB. Их нельзя сопоставлять один-к-одному по порядку: нужны ветвления и реальные continuations.

В код добавлен read-only SystemNnnLocalSubroutineScanner. Он проверяет оба операнда, разрешает первый через валидированный descriptor-3 vector, сохраняет target function code/offset, проверяет плотность SUB ID и не исполняет SUB/RETURN.
## 2026-11-05 — SETFILM связывает film-table slot с исполняемым блоком

### Replay по всем 20 SPT модулям архива

После проверки каждого ordinary descriptor-3 интервала (80 reserved entries исключены) получено:
- **948 / 948** ordinary intervals содержат ровно одну type-7 SYSTEM_FUNCTION запись `0x3333/0x001A SETFILM`;
- payload у каждой записи ровно `[1, N]`: один аргумент и one-based номер фильма;
- сразу следующая запись — type-2 CONTROL `0x5556/0x000C ENDFILM`;
- `N = descriptor3.tableIndex - reservedPrefixCount + 1` во всех 948 случаях;
- ноль ordinary intervals без SETFILM, с несколькими SETFILM или с несовпадающим номером.

Распределение SETFILM по модулям совпадает с количеством ordinary films: sys 0, backspt 19, 01 67, 02 50, 03 158, 04 54, 05 31, 06 8, 07 25, 08 12, 082 47, akane 100, aoi 82, kiika 107, yamiko 55, yuka 94, day 3, op1 20, yuka2 14, _staff 2.

### Family reference semantics

В NNNLib source другого title `SystemFunctionSetFilm` берёт DWORD payload и сохраняет `filmNumber - 1` как текущий film для активного SPT-модуля. `ENDFILM` возвращает управление внешнему script loop. В обычном режиме он не завершает всё приложение; для вызова из локальной подпрограммы после ENDFILM исполняется следующий `RETURN`, который восстанавливает сохранённый PC. Gallery/scene-selection обработка ENDFILM у reference отдельная и здесь пока не реализована.

### Ramen implementation

- Decoder теперь отдельно обрабатывает SYSTEM_FUNCTION `0x3333/0x001A`: требует ровно два DWORD payload `[1, filmNumber]`, вычисляет owning descriptor-3 entry по физическому интервалу и проверяет `filmNumber == zeroBasedFilmNumber + 1`.
- `SETFILM` возвращает typed `SystemNnnDecodedCommand.SetFilm`; ViewModel сохраняет one-based `currentFilmNumber` в UI state.
- CONTROL `ENDFILM` теперь sequentially advances to the next validated record; CONTROL `END` остаётся настоящим концом управляющего потока.
- Добавлен интеграционный synthetic test: local SUB → descriptor3 ordinary target → 0x3333/0x50 → 0x3333/0x52 → SETFILM → ENDFILM → RETURN → точное продолжение caller.
- Это связывает фильм с номером descriptor3 slot и делает локальные film subroutines исполнимыми в пределах SPT. Это **не** устанавливает связь `scene.dat row N` ↔ `@scNN` ↔ film N; она остаётся неподтверждённой.
- Автоматический старт из sys.spt, межмодульные SCRIPT/SCRIPTJUMP/SUBSCRIPT, gallery navigation, выбор следующего фильма в режиме scene и полноценное end-to-end прохождение по-прежнему не реализованы.

## 2026-11-05 — project routing and native scene.dat resolution

### Module executor routing now implemented

The target archive confirms that SCRIPT (0x5556/0x000A), SCRIPTJUMP (0x5556/0x0015) and SUBSCRIPT (0x5556/0x0016) each have exactly one DWORD payload: a Pool 2 index resolving to a module token in project.xtx. Native disassembly of the target `hui/gss.exe` confirms that each transfer handler reads only that operand at record+0x0C, passes the resolved module name to the callback, and advances PC by the current record's length. It does not read record+0x10 (the following record's length) as a return selector. Ramen therefore stores only the exact post-record caller continuation in its module frame; the speculative selector candidate has been removed.

Runtime behavior:
- SCRIPT and SUBSCRIPT push the current module, stream, decoder, current film and exact command continuation; then enter the target SPT at its first record.
- SCRIPTJUMP replaces the current SPT at the same module-stack depth.
- NEXT returns to the saved parent continuation.
- END resets the sys controller to its command-stream entry and clears nested module frames.
- EXIT ends playback.
- Each module decoder has its own local SUB/RETURN stack and film-table cache; project variables are shared across decoders, matching the reference runtime's shared variable state.
- Native target `gss.exe` has three executor slots selected by `scriptRunMode` 0, 1 and 2. Initialization loads `sys.spt` into slot 0; SCRIPT/SUBSCRIPT move to the next slot, SCRIPTJUMP stays at the current slot, and NEXT decrements the mode. Therefore normal controller execution permits at most two nested module frames; direct scene playback begins at slot 1 and permits one. Ramen now enforces these two target-specific limits rather than the unrelated family-reference depth of three.
- IF/ELSIF/ELSE now evaluates the bounded RPN condition payload and walks the declared brace spans instead of blindly skipping every branch.

### scene.dat: corrected native layout

The previous generic type/count/values interpretation was wrong. The target's NNNLib CSceneList reads scene.dat as:
int fileSize; int characterCount; [int sceneCount; (int sptNumber; int filmCount; int filmNumbers[filmCount])...]...

Reference behavior establishes:
- sptNumber is passed through absolute value to CNameList::GetName(sptNumber); therefore it is a direct, zero-based index into the ordered project.xtx list, not an index into descriptor3.
- A negative sptNumber marks an H-scene.
- Each film number is one-based. GetPlayFilmNumber takes its absolute value and subtracts one before SetPCByFilmNumber.
- A negative film number is still playable, but GetCheckFilmNumber returns -1 and excludes it from completion checks.

The parser now exposes character groups, scene rows, signed module/film values, zero-based module/film indexes, H-scene flag and completion-check flag. The runtime resolves every row through project.xtx and validates every referenced film index against that specific module's ordinary descriptor3 slots.

### Actual cleaned archive map

The supplied primary hui/spt/scene.dat is 1,164 bytes and parses exactly to one character group and 69 scene rows, with no trailing data. The primary project.xtx has 20 ordered modules. scene.dat uses these module indexes:

| project.xtx index | Module | Scene rows | Film references | Ordinary film slots |
|---:|---|---:|---:|---:|
| 4 | 03 | 2 | 4 | 158 |
| 10 | 082 | 4 | 4 | 47 |
| 11 | akane | 15 | 23 | 100 |
| 12 | aoi | 14 | 36 | 82 |
| 13 | kiika | 16 | 42 | 107 |
| 14 | yamiko | 2 | 2 | 55 |
| 15 | yuka | 14 | 33 | 94 |
| 18 | yuka2 | 2 | 6 | 14 |

All 150 film references resolve within the descriptor3 film-table range of their owning modules. The largest referenced zero-based film indexes are 111, 45, 99, 81, 106, 54, 93 and 12 respectively. There are 43 negative film references; these remain playable and are marked completion-untracked. The parallel scenevoice file contains 69 ordered @sc01..@sc69 identifiers.

### Scene replay execution

When launched with the public Activity extras EXTRA_SCENE_CHARACTER_INDEX and EXTRA_SCENE_INDEX (optional EXTRA_SCENE_FILM_CHOICE_INDEX, default 0), the Activity resolves the scene.dat row to a module and selected film. The player starts at that module's descriptor3 pointer. It retains the remainder of that row's film list as a replay queue.

ENDFILM is now represented as a separate decoded event:
- normal story execution advances to the next record, matching YoyakuExecRoutine when m_sceneMode is false;
- scene replay advances to the next film in the selected scene row;
- after the last scene film, replay ends.

This is the engine route; a visible scene-gallery picker/UI has not yet been added. CI/tests were intentionally not run per current work direction.


## 2026-11-05 — native target executable confirms module-transfer semantics

Source: the user-provided cleaned archive `hui(5).zip`, using only SPT structural words and the native executable's code; no scenario text or media was extracted. The target `hui/gss.exe` is PE32/i386 (image base `0x00400000`).

Disassembly of its interpreter shows the three target branches:
- SCRIPT: VA `0x00417000`;
- SUBSCRIPT: VA `0x00417059`;
- SCRIPTJUMP: VA `0x004170B2`.

Each branch reads the DWORD at active-record offset `+0x0C` (the sole Pool 2 operand), resolves that string as a module name, and passes it to the transfer callback. On return from the callback, the interpreter reads the current record's length DWORD and advances PC by that length. None of the three branches reads active-record offset `+0x10`; in the target stream that cell is simply the next record's length. Therefore this title does **not** use that cell as a SCRIPT/SUBSCRIPT return selector.

This target evidence supersedes the earlier cross-title NNNLib hypothesis. The external reference stores the following DWORD in `m_stack[253]` and uses `AdjustScriptCall` while restoring saves, but that mechanism must not be transplanted into this executable. Ramen's exact `record.endOffsetExclusive` continuation is the correct normal return point for the parent module; the speculative `returnSelectorCandidate` field has been removed from decoder and module-call frames.

A structural coincidence is explicitly rejected as proof: the next-record length values (3–5) can be used as low indexes into a later `DATA/TABLE` record marked `0x44440003`, and the resulting values happen to land on command-record boundaries in a few observed comparisons. The native transfer branches never perform that lookup.


## 2026-11-06 — target-native SPT executor depth

The cleaned target `hui/gss.exe` confirms that the executor stack is not the four-level stack from the other-title NNNLib reference:
- Startup initializes `scriptRunMode` to 0 and loads `spt\\sys.spt` into the executor object at `this+0x3660` (initialization code around VA `0x00409ACC`).
- The selector at VA `0x0040E560` maps modes 0, 1 and 2 to executor objects at `this+0x3660`, `this+0x3664` and `this+0x3668` respectively.
- The transfer callback at VA `0x00411CB0` increments the mode for SCRIPT and SUBSCRIPT, but not SCRIPTJUMP. When the mode is already 2 it formats the warning `これ以上、下のスクリプトはよべません` (no further lower-level script can be called) and does not increase the mode.
- The scheduler at VA `0x004106E0` decrements the mode on NEXT; END resets it to 0 and resets the controller PC.

Consequences for Ramen:
- Normal project playback starts in sys slot 0, so it may push at most two module frames (slots 1 and 2).
- A direct scene replay starts from the selected story module, corresponding to slot 1, so it may push at most one nested module frame (slot 2).
- SCRIPT and SUBSCRIPT share the same depth increase. SCRIPTJUMP replaces the current module at the same depth. NEXT restores the saved caller continuation and decrements one level.
- At the native overflow boundary the executable warns and reuses its final executor slot. Ramen instead rejects the extra push with a bounded error rather than silently overwriting the active nested frame.

This is based on the target executable, not the NNNLib source from another title. No CI/tests/lint/APK build was run for this change.


## 2026-11-06 — target-native SELECT/CASE branch mapping

Source: cleaned user archive `hui(5).zip`; all 20 primary `hui/spt/*.spt` command streams were structurally parsed. Only record identifiers, codes, DWORD counts, indexes and command offsets were used; no scenario text was reproduced.

### Native executable behavior

- The SELECT command handler is at `gss.exe` VA `0x0041720A`. It reads the option count from the first payload DWORD. For the newer record shape, it reads the message-prefix count from the final DWORD, copies the prefix/option Pool 2 indexes in that order, and forwards the metadata to the selection callback. The target's time-limit, auto-select and special-flag DWORDs are all zero in the scanned SELECT records.
- The target's CASE lookup routine is at VA `0x00417570`. Starting at the PC immediately after SELECT, it skips an initial outer OPEN_BRACE record by that record's own length only. It then searches CONTROL/CASE records (`0x55560008`), compares the low 16 bits of the first payload DWORD with the requested case number, and returns the command boundary immediately after the matching CASE record.
- When a CASE does not match, the routine advances past the CASE record. If the next record is OPEN_BRACE, it skips by the DWORD span stored in that brace record's first payload. If that lands on CLOSE_BRACE, it also advances by the closing-brace record length before continuing to the next CASE.

### Full primary-archive scan

| Check | Result |
|---|---:|
| Primary SPT modules parsed | 20 / 20 |
| SELECT records | 46 |
| Options per SELECT | 1–4 (maximum 4) |
| Prefix rows | 1 total: 45 SELECTs have 0, one has 1 |
| Pool 2 references in option/prefix lists | 46 / 46 valid |
| CASE lists | 46 / 46 exactly enumerate `1..N` |
| CASE bodies wrapped in OPEN_BRACE | 104 / 104 |
| Nonzero time-limit / auto-select / special-flag values | 0 |

Per-module SELECT counts: `01=11`, `02=5`, `03=9`, `04=3`, `05=1`, `06=1`, `07=0`, `08=0`, `082=1`, `_staff=0`, `akane=4`, `aoi=4`, `backspt=0`, `day=0`, `kiika=2`, `op1=1`, `sys=0`, `yamiko=0`, `yuka=4`, `yuka2=0`.

### Ramen correction

The decoder already mapped option `N` to CASE `N` and returned the boundary immediately after that CASE. Its branch-body brace skip matches the native span/closing-brace behavior for the 104 observed case blocks. One mismatch was corrected: when SELECT is followed by an outer OPEN_BRACE, Ramen now skips only that opening record, matching the target `GetCase` routine, rather than skipping the entire wrapper span before scanning CASE records. A synthetic regression test covers this case.

Ramen intentionally still does not expose the native time-limit/auto-select/special-flag metadata in its choice UI. The three fields are all zero across the 46 SELECT records in this cleaned archive. Tests were not run per current work direction.


## 2026-11-06 — target IF/ELSIF/ELSE execution

- Native target IF dispatch is at `gss.exe` VA `0x00416611`. It evaluates the IF/ELSIF RPN condition and tests the result with signed `jg` semantics: values greater than zero take the branch; zero and negative values follow the false path.
- A true branch resumes at the command immediately after its IF/ELSIF record. A false branch skips the following OPEN_BRACE block by the declared DWORD span and resumes at the next ELSIF/ELSE marker. After a branch has been entered, later ELSIF/ELSE records skip their own brace-wrapped bodies. ENDIF and ENDKOMA remain sequential boundaries.
- The Ramen decoder's branch traversal matches this target behavior on all observed condition layouts. Full primary SPT scan: 490 IF/ELSIF/ELSE records, 490 valid brace spans, and a maximum ELSIF chain of 11.
- Native CALCU evaluator at VA `0x00417690` changes a zero divisor to 1 for integer `/` and `%`, including their compound forms. The Kotlin evaluator has been corrected to do the same.
- Synthetic regression tests cover false IF/ELSIF fallthrough to ELSE, skipping remaining alternatives after a true IF, and `10 / 0` evaluating through the native divisor-to-one fallback. Tests remain unrun by request.


## SYSTEM_COMMAND DRAW (`0x22220004`)

The runtime warning at SPT offset `0x13080` identifies a type-6 record
(`identifier=0x66660006`, `SYSTEM_COMMAND`) with code
`0x22220004`. In the pinned NNNLib family reference,
`scriptDefine.h` names this code `CODE_SYSTEMCOMMAND_DRAW` / `DRAW`.
This is a system command, not a `0x3333` SYSTEM_FUNCTION, so it is outside
the scope of `SystemNnnResourceFunctionScanner`.

The reference `execscript.cpp` forwards the record's opaque drawing payload
to the native script callback, advances the program counter by the record's
declared length, and yields a DRAW event when its draw-count field is nonzero.
That describes the family reference implementation; the cleaned Ramen
repository does not contain the target `gss.exe` or a verified
Gore-Screaming-Show-specific DRAW payload schema.

Ramen therefore treats `0x22220004` as a bounded no-op for now: the command
stream reader validates the complete record boundary, and the decoder returns
`Skip(record.endOffsetExclusive)` without reading any payload DWORDs. This
prevents the unsupported-record stop and continues at the next record, but
does not render any graphics encoded by DRAW. A target-specific renderer
remains future work; no payload values are reclassified as resource indices
or module references.
