# System-NNN visual acceptance plan

**Date:** 2026-11-08  
**Branch:** docs/system-nnn-launch-roadmap  
**Scope:** foreground image layers, placement, movement, effect timing and rendering. This document separates user observation from source-level facts and hypotheses.

## 1. Acceptance state from the user

| Area | Current assessment | Work status |
|---|---|---|
| Audio | Observed as working very well; user considers it acceptable | Closed unless a concrete defect is reported |
| Background image | Observed as rendering normally | Accepted baseline; protect from regression |
| Foreground/native layers | Better than earlier versions, but placement/appearance is still wrong | Active |
| Effects | Not yet visually assessed as correct | Active |
| Moving/floating images | May move off-screen or appear incorrectly | Active |

These are user-observed results, not CI claims.

## 2. The render pipeline in the current code

1. SystemNnnCommandDecoderImpl decodes LOADDWQ into a native layer index and a Pool 2 resource name. SETEFFECT and SETEFFECTRECT are decoded separately.
2. SystemNnnPlayerViewModel stores pending layer effects and rectangles so they can be applied whether they arrive before or after the bitmap load.
3. At the next visible event, resolveSprites loads the bitmap, retains/reuses unchanged layers, and resolves the current layer state.
4. SystemNnnPlayerScreen establishes a centered game viewport, draws the background, then draws layer indices in ascending order. The viewport clips anything outside its bounds.
5. SystemNnnAnimatedSpriteLayer converts native rectangles and movement offsets into Compose dimensions and translations, then applies effect-specific painters/transforms.
6. Screen-level overlays are drawn over the composed viewport; dialogue and quick controls are separate Compose UI layers above the game viewport.

This confirms the intended architectural path, but not the correctness of the final pixels.

## 3. High-priority code findings

### V1 — two placement models coexist

**Source facts**
- The native viewport reference size is taken from the background bitmap width/height. Without a background, it uses the first sprite bitmap dimensions, then 800×600.
- A LOADDWQ layer gets (0,0,bitmapWidth,bitmapHeight) as its fallback native rectangle when there is no configured SETEFFECTRECT.
- A sprite reference without a native rectangle uses legacy bottom-center placement with a 0.62 viewport-width and 0.82 viewport-height size.

**Why it matters**
A native layer and a legacy sprite can have different origins and scales even if they reference similar images. If the native target's logical screen size differs from the background bitmap dimensions, every native rectangle is scaled against the wrong reference size. If no SETEFFECTRECT is present, a character-sized image starts at the upper-left using its bitmap dimensions.

**Next evidence**
For a problematic layer, record: module, film, layer index, resource name, bitmap width/height, background width/height, SETEFFECTRECT source/destination rectangles, and the final computed destination rectangle in viewport pixels. Do not change the background renderer, which the user has accepted.

### V2 — off-screen motion is possible by design

- BEZIERMOVE (62) and BSPLINE2 (119) compute position offsets from native control-point parameters.
- CHARABURE (132) adds deterministic jitter.
- MOVE (52) requires both source and destination SETEFFECTRECT rectangles; absent rectangles are reported and the layer cannot move.
- The offsets are not clamped to the visible canvas. The outer viewport clips any part outside its bounds.

A missing image and an image positioned outside the viewport can therefore look similar. Diagnostics should include the calculated bounds before clipping.

### V3 — overlay effects share one layer frame counter

The screen currently keeps a single layerEffectFrame for overlay effects such as FADE, FLASH, SUNA and OLDFILM. The state key is the full layer-configuration set; changing one layer configuration resets the counter used by all overlay layers. This can restart another layer's effect and makes simultaneous effects difficult to compare to native timing.

The geometric effects have per-sprite Animatable state, but the overlay effects do not have independent per-layer clocks. A later improvement should track start time/frame by layer and command offset.

### V4 — HAIKEIYURE mode 0 clock was fixed

Previously the local mode had an Animatable initialized at zero, but the LaunchedEffect condition omitted effect 100. Without SETEFFECTRECT motion, the frame stayed at zero and the layer displacement did not progress. The current branch adds a shared shouldAnimateLayerProgress policy, advances mode 0, and has a regression unit test. A new CI run is still required.

Mode 1 is still an approximation: the Android renderer shifts the composed viewport as a group, while the documented native callback moves the screen buffer at a particular point in the layer sequence.

### V5 — CHANGE and multi-layer composition need target comparison

CHANGE (40) uses a second distinct layer, requires matching bitmap dimensions, and blends both images on an offscreen Compose surface with BlendMode.Plus. The operand and blend weights have pure unit coverage, but the final composite may differ from native alpha/transparency behaviour. Compare the same target transition and retain screenshots.

### V6 — effect parity is mixed, not binary

- TURN (64) and STRETCHBLT (65) have dedicated Compose transforms. Native colour-key transparency mode 1 still needs a pixel-level implementation.
- CTRANS (9) has opacity, colour-offset and clip-rectangle paths; target-specific parameter parity is not yet accepted.
- CLOUD (82) is handled only for the observed static, unmasked software case. The target EXE's build variant is not established.
- KE (27), SUNA (29), OLDFILM (55), and CHARABURE (132) use deterministic Android pseudo-random sequences rather than the original process-global Windows RNG.
- Effects outside the explicit path list are not implemented; some may display a bitmap fallback with a warning rather than the native effect.

## 4. Bitmap decoding checks

The graphics audit must keep image decoding separate from placement:
- GPK/GTB and standalone WGQ are validated before bitmap creation.
- DWQ PACKTYPE has dedicated paths, including packed BMP data and alpha-mask handling.
- DWQ stored row order is handled separately from normal BMP row order.
- PACKTYPE 7 JPEG alpha masks are rejected above the pixel budget rather than decoded with mismatched sampled dimensions.
- Large but valid packed images can still create a transient memory peak because compressed data, decoded pixels, and Bitmap may overlap.
- The player logs bitmap dimensions/config/alpha, but the current snapshot does not record the final post-layout pixel rectangle for every sprite.

A background appearing correctly is useful evidence for the background's own decode path, but does not prove that every foreground archive type, mask, crop or layout path is correct.

## 5. Test coverage boundary

The repository has pure effect-math tests and ViewModel state/command tests. It does not have a dedicated Compose screenshot/pixel suite that validates:
- final position and size of each layer;
- layer stacking order and alpha edges;
- viewport clipping;
- the actual CHANGE composite;
- effect timing against a target frame sequence;
- whether a moving image is visible or beyond the viewport.

Synthetic tests prove formulas and state transitions, not visual parity with the game.

## 6. Recommended acceptance sequence

### Stage A — stable layer geometry, no effects
Use one known scene and capture the background dimensions and every loaded foreground layer. Confirm bitmap identity, pixel dimensions, native layer index, and destination rectangle. Compare expected rectangles with actual visible bounds. Test the same layer with and without SETEFFECTRECT.

### Stage B — stacking and transparency
Check two and then three simultaneous image layers. Confirm ascending layer order, alpha mask edges, transparent areas, and whether clearing one layer leaves the others untouched. Include same-size and different-size CHANGE partner cases.

### Stage C — motion
Test MOVE, BEZIERMOVE, BSPLINE2, TURN, STRETCHBLT, SCROLL and CHARABURE separately. For each, retain the raw parameter vector, frame count, computed x/y/width/height, visible intersection with the viewport, and beginning/middle/end screenshots. Include deliberate off-screen paths to confirm clipping is intentional rather than a coordinate-scale error.

### Stage D — screen and overlay effects
Test FADEIN/FADEOUT, FLASH, SHAKE, FILL, SUNA and OLDFILM individually, then test overlapping effects on different layers. Verify that starting/changing one layer does not restart another layer's timer.

### Stage E — target parity
Compare a small set of known game scenes with the original engine or reference screenshots. Prioritize only effects actually present in the target game's diagnostics. Do not attempt to implement all family effect IDs before establishing which ones the target uses.

## 7. Diagnostics to add before guessing at coordinates

For each visible layer, the export should record:
- module / film / command offset;
- layer index and resource name;
- source bitmap width/height, alpha presence/config;
- background/reference viewport width/height;
- native rectangle and source/destination rectangles;
- effect ID, raw parameters and current frame;
- computed destination x/y/width/height in viewport pixels;
- clipped visible rectangle and whether it is empty/outside;
- partner layer index for CHANGE.

This allows "resource missing", "decoder gave the wrong bitmap", "wrong coordinates", and "correctly clipped off-screen" to be distinguished.

## 8. Priority order

1. Keep the accepted background and audio paths untouched.
2. Build a fresh APK and run CI for the HAIKEIYURE mode-0 fix.
3. Instrument computed layer geometry and per-layer effect frame values.
4. Validate the coordinate reference against the target game's actual logical screen resolution.
5. Fix basic layer rectangles/placement before changing effect algorithms.
6. Validate movement and clipping, then transition effects.
7. Reopen audio only if the user reports a concrete failure.

---

**Acceptance rule:** no effect or movement is marked visually supported solely because its math test or APK build passes. It is accepted only after comparison with a real target scene on the Android player.
