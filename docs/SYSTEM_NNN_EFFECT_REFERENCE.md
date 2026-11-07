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
