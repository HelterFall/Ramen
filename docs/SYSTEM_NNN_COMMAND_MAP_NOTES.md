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
- Resource/background/audio command references, branching, waits, choices and module transitions.
- The startup module and true execution order for this specific title.

No Kotlin code or tests were changed during this analysis. The VM decoder remains disabled. No runtime behavior or game launch is claimed.

## Next diagnostic step

Use the exact Pool 1 logical-address table to test the 37,383-count relation at the **individual-record level**: inspect bounded words following each `0x2222` pair, determine whether they encode a pool index/address, and check the mapping against all 20 modules. Keep the result as a read-only scanner until the reference rule is proven. Then investigate the `0x3333` and `0x5556` families, followed by module transitions.
