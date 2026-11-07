# System-NNN visual effect reference

This file records source-level facts verified against the public reference runtime
`tinyan/SystemNNN`, revision `78a05053c78b3b80065a9a209c6685a7c60a2e83`.
It is a comparison aid, not a claim that Ramen has implemented every effect.

Reference files:
- [effectlist.h](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectLib/effectlist.h) — native numeric IDs.
- [allEffect.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectLib/allEffect.cpp) — registration, `SetEffectScript`, `SetLayerScript`, and `PrintLayers`.
- [commoneffect.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectLib/commoneffect.cpp) — default `CheckNeedPicPrint` returns TRUE.
- [Picture.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanPictureLib/Picture.cpp) — DWQ row loading and alpha-mask handling.

## Source-picture decision verified for effects seen in the test title

`PrintLayers` calls `CheckNeedPicPrint` before blitting the original layer picture,
then calls that effect's `Print`. Therefore FALSE means the effect must not get an
extra ordinary source-picture blit; it does **not** mean the effect itself can be
skipped. TRUE means the native runtime blits the source picture before calling
the effect's Print method.

| Native ID | Effect | Source file | Native source-picture rule |
|---:|---|---|---|
| 0 | NOP | [base implementation](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectLib/commoneffect.cpp) | TRUE (base default) |
| 8 | TRANS | [effectTrans.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectTrans.cpp) | FALSE |
| 9 | CTRANS | [effectCTrans.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectCTrans.cpp) | FALSE |
| 19 | ANIME | [effectAnime.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectAnimeLIB/effectAnime.cpp) | FALSE |
| 12 | FLOWER | [effectFlower.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectNaturalLIB/effectFlower.cpp) | TRUE (inherits base default) |
| 13 | RAIN | [effectRain.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectNaturalLIB/effectRain.cpp) | TRUE (inherits base default) |
| 17 | FLASH | [effectFlash.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectScreenLIB/effectFlash.cpp) | TRUE (inherits base default); later changes its command to FILL |
| 27 | KE | [effectKe.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectSpecialLIB/effectKe.cpp) | FALSE |
| 29 | SUNA | [effectSuna.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectScreenLIB/effectSuna.cpp) | TRUE (inherits base default) |
| 40 | CHANGE | [effectchange.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectchange.cpp) | FALSE |
| 54 | FILL | [effectfill.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectScreenLIB/effectfill.cpp) | FALSE |
| 55 | OLDFILM | [effectoldfilm.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectScreenLIB/effectoldfilm.cpp) | TRUE (inherits base default) |
| 61 | SPECIALMOVE | [effectSpecialMove.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectSpecialMove.cpp) | FALSE |
| 64 | TURN | [effectTurn.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectTransformLIB/effectTurn.cpp) | FALSE |
| 65 | STRETCHBLT | [effectStretchBlt.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectTransformLIB/effectStretchBlt.cpp) | FALSE |
| 66 | BUFFER | [effectBuffer.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectEtcLIB/effectBuffer.cpp) | FALSE; `CheckAllPrint` is also FALSE |
| 82 | CLOUD | [effectCloud.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectNaturalLIB/effectCloud.cpp) | FALSE |
| 90 | CHARAFLASH | [effectCharaFlash.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectCharaFlash.cpp) | FALSE |
| 93 | GRASSPLANE | [effectGrassPlane.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectNaturalLIB/effectGrassPlane.cpp) | FALSE |
| 94 | POLESCROLL | [effectPoleScroll.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectSpecialLIB/effectPoleScroll.cpp) | FALSE |
| 95 | WATERDROP | [effectWaterDrop.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectLib/effectWaterDrop.cpp) | FALSE |
| 99 | COLORADDSUB | [effectColorAddSub.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectColorAddSub.cpp) | FALSE |
| 107 | CHARARASTER | [effectCharaRaster.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectCharaRaster.cpp) | FALSE |
| 110 | CHARAGHOST | [effectCharaGhost.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectCharaGhost.cpp) | FALSE |
| 113 | LUPIN | [effectLupin.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectLib/effectLupin.cpp) | FALSE |
| 124 | CHARAGREY | [effectCharaGrey.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectCharaGrey.cpp) | FALSE |
| 125 | CHARABOKASHI | [effectCharaBokashi.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectCharaBokashi.cpp) | FALSE |
| 126 | BGBOKASHI | [effectBGBokashi.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectSpecialLIB/effectBGBokashi.cpp) | FALSE |
| 127 | SEPIA | [effectCharaSepia.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectCharaSepia.cpp) | FALSE |
| 131 | ADDSUB | [effectCharaAddSub.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectCharaAddSub.cpp) | FALSE |
| 132 | CHARABURE | [effectCharaBure.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectCharaLIB/effectCharaBure.cpp) | FALSE |
| 100 | HAIKEIYURE | [effectHaikeiYure.cpp](https://github.com/tinyan/SystemNNN/blob/78a05053c78b3b80065a9a209c6685a7c60a2e83/nyanEffectNaturalLIB/effectHaikeiYure.cpp) | Conditional: FALSE when `para[5] == 0`, TRUE otherwise |

## Required comparison for every additional effect

Before changing an effect implementation, check these exact native methods in
the corresponding `.cpp` file and record the result here:

1. `SetParam`: operand count, defaults, clamping, and state initialized.
2. `InitParameter`: whether it re-applies defaults or reconstructs state.
3. `CountIsMax`: whether it changes the command, clears the layer, or continues.
4. `CheckNeedPicPrint` and `CheckAllPrint`: whether the source picture is blitted
   and whether the layer participates in the normal pass.
5. `Print`: source/destination picture, screen buffer, blend, mask and clipping
   operations; whether another layer or global resource is read.
6. Registration in `allEffect.cpp`: an enum value without a registered object is
   not automatically an implemented effect.

Then compare Ramen's decoded operands and the resulting layer/resource state
against the native behavior. A green unit-test run validates code-level cases
only; final acceptance still requires the same scene on the target title/device.

## DWQ image comparison

The image loader needs a separate audit from effect rendering:

- `LoadDWQHeader` determines whether a mask exists and whether the image was
  already cropped; these are different flags.
- `LoadDWQData` reads ordinary DWQ rows in the native stored order.
- `LoadMask` decodes the following mask stream; the native alpha byte comes
  from `rgbquad[index].rgbRed`.
- `CutDataByMask` changes the visible image bounds when the image was not
  pre-cropped.

Do not infer these rules from the BMP header alone. Record PACKTYPE, header flags,
image data range, mask range, dimensions, bit depth, row orientation and alpha
statistics from the actual resource being decoded.

## Android parity work recorded on the current branch

- **TURN (64):** the Compose painter now uses native angle/scale/opacity operand order, source-center pivot offsets, optional quadratic/cubic spline translation, and RGB add/subtract. The DWQ bitmap's decoded alpha mask is retained for native transparency mode 2; mode 0 explicitly ignores it. Native color-key mode 1 still needs a dedicated pixel path and is not claimed as exact.
- **CTRANS (9):** frame-interpolated RGB offsets and the native interpolated clip rectangle are now applied when the destination rectangle is known. Its opacity continues to use the native start/end and speed operands. The renderer still depends on the decoder having produced the native DWQ mask alpha correctly.
- **BUFFER (66):** the ordinary bitmap fallback is suppressed. This follows the native implementation: optional CPicture::Shrink() reallocates/copies the image buffer, Print() is a no-op, and CheckAllPrint() is FALSE. Android does not need to imitate the native allocation-only operation.

These are source-level ports of the described paths, not device-verified parity.

### STRETCHBLT (65) — Compose path added from observed source parameters

The Android layer renderer now maps native scale X/Y, center offsets, frame interpolation,
overall transparency for native mode 2, and optional RGB add/subtract to a dedicated
Compose transform. This is a source-level mapping, not a bit-exact copy of the native
software sampler. Native color-key transparency mode 1 still requires a pixel-level
key comparison. The supplied runtime diagnostic samples for STRETCHBLT use native
transparency mode 0, so they do not depend on that unimplemented mode.

### COLORADDSUB (99) — Compose path added for observed RGB operations

The Android layer renderer now interpolates the native start/end RGB offsets and applies
a saturating color-matrix adjustment while retaining the decoded bitmap alpha. The
uploaded runtime diagnostics use native transparency flag 1 and speed type 0 for these
entries. Other transparency modes still require dedicated pixel-level parity checks.

## Values observed in the supplied runtime diagnostic

These are literal parameter vectors from the supplied log, not inferred parameter examples:

| Effect | Observed layer/resource | Observed parameters |
|---|---|---|
| TURN (64) | layer 4, `ta_effe_light01`, 600×600 | `700,375,312,0,0,220,300,2,100,100,0,0,0,0,0,0` |
| STRETCHBLT (65) | multiple layers, including film 3 | `430,0,0,205,205,200,200,5,3,0,100,100,0,0` |
| COLORADDSUB (99) | layer 7 | `80,-130,-255,-255,-230,-255,-255,1,0,0` |
| CTRANS (9) | layers 1, 2, 5 and 6 | `2000,100,0,2,100,0,0,0,800,600,0,0,800,600` |
| CLOUD (82) | layer 1 | `0,300,13,4,9999,200,1362,200,0,1,255,160,110,50` |

TURN, STRETCHBLT and COLORADDSUB now have dedicated Compose rendering paths.
CTRANS has the observed opacity/mask path. CLOUD still requires its own perspective
pixel compositor and is not claimed as implemented.
