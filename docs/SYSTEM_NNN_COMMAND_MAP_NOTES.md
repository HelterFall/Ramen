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
