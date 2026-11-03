# System-NNN SPT format notes

**Status:** working format notes from one inspected sample; not a complete specification.
**Source:** user-provided analysis script (`hu.txt`) and its captured output (`ho.txt`), plus the user's clarification that GPK/GTB and VPK/VTB are already readable in the current project.
**Scope:** preserve observed values and hypotheses for follow-up parser work. Do not treat every candidate field as semantically proven.

## Confirmed project status (user clarification)

- GPK/GTB resource files are already readable in Ramen.
- VPK/VTB resource files are already readable in Ramen.
- The remaining research focus discussed here is the SPT script structure and how its tables, strings, and resource references are related.

This is a user-reported project status. Exact variant coverage and title/device acceptance results are not specified in the supplied note.

## Observations from the supplied SPT analysis output

The analysis script reads the SPT bytes, XOR-demasks every byte with `0xFF`, then examines the decoded buffer.

| Observation | Value |
|---|---:|
| Sample file size | `389500` bytes (`0x5F17C`) |
| Demasked signature | ASCII `SPTHEADER0` |
| Signature offset | `0x30` |
| First DWORD | `0x00000014` (`20`) |

### Candidate header table descriptors

The script reads four little-endian DWORD pairs at offsets `0x10`, `0x18`, `0x20`, and `0x28` (count followed by target offset):

| Descriptor position | Count | Target offset |
|---|---:|---:|
| `0x10` | 1299 | `0xC5BA` (50618) |
| `0x18` | 1124 | `0x167F2` (92146) |
| `0x20` | 69 | `0xC390` (50064) |
| `0x28` | 2 | `0x0` |

These are observed count/offset pairs. Their exact semantics and record layouts still need to be established. In particular, the count of 1299 and target `0xC5BA` are strong leads for the main table, but the output alone does not prove that its entries are 32-bit scene pointers.

### String and marker observations

- The first run accepted by the script's Shift-JIS heuristic starts at `0x32B64`.
- That run ends at `0x32BC9`, has 101 bytes, and decodes successfully with strict Shift-JIS.
- The script finds six occurrences of the byte marker `text\0` at:
  `0x32B34`, `0x32B3C`, `0x32B44`, `0x32B4C`, `0x32B54`, and `0x32B5C`.
- These marker locations precede the first detected Shift-JIS text run. Their meaning and relationship to the text records are not yet known.
- The Shift-JIS scan is heuristic: it searches for printable ASCII / half-width Katakana / valid double-byte Shift-JIS runs. It does not by itself establish the start or complete extent of every text record.

### DWORD values printed at `0x32B20`

The analysis script reads five little-endian DWORDs at this location:

| Absolute offset | Value |
|---|---:|
| `0x32B20` | `0x00016752` |
| `0x32B24` | `0x00016791` |
| `0x32B28` | `0x000167A3` |
| `0x32B2C` | `0x000167C6` |
| `0x32B30` | `0x000167D9` |

These look like offset/pointer candidates because they fall in the vicinity of the second descriptor's target region, but that interpretation is only a hypothesis. Validate each value against actual file structure and the associated resource/text data before relying on it.

## Relationship to the current System-NNN implementation

The current `feat/system-nnn-spt-loader` work already assumes:
- byte-wise XOR-`0xFF` demasking;
- signature `SPTHEADER[0-9]` at `0x30`;
- header count/offset descriptors at the positions listed above;
- a main table beginning at `0xC5BA`, with 1299 entries;
- a text-pool candidate beginning at `0x32B64`.

The existing decoder further assumes that the 1299 entries are four-byte little-endian values and applies a resource-index/dialogue classification rule. The supplied analysis output does **not** independently validate the four-byte entry interpretation, the value threshold, the dialogue ordering, or the relationship between the DWORDs at `0x32B20` and text markers. Keep those rules provisional until checked against the actual sample bytes and visible in-game scene order.

The current loader's interpretation of all NUL-delimited data from `0x32B64) onward as Shift-JIS text likewise needs validation beyond the first detected printable run.

## Next validation tasks

1. Preserve the original SPT sample and its SHA-256 outside the source repository; do not commit commercial game data.
2. Extend the read-only dump to show bounded hex/byte windows around `0xC390`, `0xC5BA`, `0x16752`, `0x167F2`, `0x32B20`, and `0x32B64`.
3. Decode the 1299-entry candidate table as both plausible-width alternatives where appropriate, then test which interpretation produces valid in-file targets / resource indices. Do not bake in UInt32 until evidence supports it.
4. Determine whether the 1124-count descriptor and its target `0x167F2) describe a separate offset table, text index, or another structure.
5. Determine the role of the 69-count descriptor at `0xC390`.
6. Trace the five values at `0x32B20` and the six `text\0` markers to concrete record boundaries.
7. Compare decoded strings and referenced GPK/GTB resources with the actual game scene order.
8. Only then finalize the SPT decoder's pointer rule and dialogue progression.

## Evidence boundary

The two supplied text files contain the analysis script and its printed summary, not the binary SPT sample itself. The recorded byte offsets and values are therefore preserved as reported observations, but cannot be independently re-parsed from the binary in this documentation update.
