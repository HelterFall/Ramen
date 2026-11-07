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

## 1A. Scene-by-scene observations from the user's comparison with the original

**Observation source:** the user described playback verbally on 2026-11-08. Screenshots are unavailable. The expected/original behaviour below is the user's reference; it is not independently captured evidence. Keep the original-vs-Ramen distinction explicit while fixing the renderer.

| Sequence | Expected in the original | Observed in Ramen | Main subsystem / confidence |
|---|---|---|---|
| Intro text on black | A centered line appears progressively, before the CRT image | It appears immediately | A separate non-dialogue/title rendering path is missing or bypassed. Confirmed: the existing 24 ms typewriter only animates the dialogue box. Exact source command for this title line is not yet identified. |
| Broken CRT and woman | The monitor remains visible above the scene; the woman/image is visible behind its glass, shakes and slowly drifts left; scanlines, dust/specks and scratches move over it | The monitor disappears or covers the woman; the woman's image can be displaced into a corner; scratch/noise effects are approximate | Layer order, DWQ alpha/mask, native rectangles, effect composition and HAIKEIYURE buffer semantics. Need per-layer resource/rect/effect/command-offset evidence; do not simply lower opacity of the whole monitor. |
| Playground/mountain background with cloud band | The lower scenery (playground/mountains) remains still; only the upper cloud band drifts | The lower scenery stays still, but the upper cloud band does not move | This is the bounded CLOUD (82) partial-height compositor case. The known layer-1 vector requests 300 output rows; that height is not itself the full-screen panorama defect. However, its first operand is 0 (countMax). In the pinned native reference, CAllEffect::CountUp advances only while count < countMax, and CLOUD updates m_x/m_y only when count > 0. With countMax=0 this vector does not move in that reference path. Ramen's static frame-0 result is therefore not, by itself, evidence of an Android animation bug. The observed drift must be traced to the actual moving layer/effect/resource rather than forced onto this CLOUD configuration. |
| Repeated monitor shots | Monitor/woman composition changes or zooms while the text remains in front; flicker and CRT imperfections continue | Text can stay in front, but there is no smooth intro; a black interruption sometimes covers the image for seconds | Correct text/compositor ordering is only partial. Black interruption may be a fade/flash/clear/scene transition or a missing asset; command timeline is needed to distinguish them. |
| Full-height male figure | Figure appears with a red vertical colour grade over the already-rendered scene | Figure appears without the expected red tone | The user's warning identifies effect 50. In the pinned family reference, ID 50 is GRADATION (a screen-buffer colour ramp); Ramen has no renderer path for it. High-confidence missing implementation. |
| Shaking monitor with woman behind it | Monitor shakes while the woman remains behind/inside the CRT aperture | A corner of the woman appears outside the intended opening; monitor is opaque over her | Likely geometry/mask/compositor issue, not just a movement amplitude issue. Exact layer identities and alpha coverage remain unverified. |
| Red flash | A brief red flash covers the figure and scene | Three disconnected red elements appear; the figure remains static | Check whether this is FLASH (17), FILL (54), GRADATION (50), or a combination. Ramen's global screen overlay is drawn above all layers, but a layer-local overlay is drawn at that layer's z-position and later layers can cover it. Do not assume a patchy effect is a colour problem until its native layer and command order are known. |
| Interstitial words / title card | Insert words animate; figure has already disappeared; black screen then shows a dynamically appearing line and game title | Figure remains; title text and game title are absent | Missing/incorrect clear and scene-command handling is plausible. Native SENTENCE (133) is an animated tiled-image reveal, not necessarily the source of these words; the actual target command/resource has not yet been identified. |
| Independent cloud panorama | The entire cloud image moves downward and progressively covers the field | Movement direction is correct, but only part of the image covers the viewport; the remaining area is blank/white | Separate full-scene pan/coverage defect. Do not attribute this to the 300-row CLOUD (82) cloud-band vector. Inspect the loaded bitmap size, layer native rectangle, SETEFFECTRECT source/destination dimensions, viewport reference size, clipping and whether the native transition expects wrap/repeat. Exact effect ID/resource is not yet identified from the spoken description. |
| Town/village panorama | The full town/village image pans to the right | Direction is correct, but part of the viewport remains blank/white instead of being covered by the panorama | Same class as the independent cloud panorama: image coverage/rectangle/cropping/repetition, not the static upper cloud-band animation. Confirm effect ID and compare bitmap dimensions with native source/destination rectangles. |
| Road background | Static road appears without previous cloud motion/images | Previous cloud images remain as semi-transparent overlays | Likely stale layer/effect state or missing clear at a scene boundary. SceneUpdate clears the visual snapshot, but dialogue updates can preserve existing layers when they provide no replacement sprites. Need command-offset trace before choosing whether to fix the runtime or an absent script clear. |

The user also explicitly reported these runtime messages:
- OLDFILM (55) is a deterministic screen-scratch approximation; native randomized scratch timing is not exact.
- KE (27) uses the native DWQ atlas layout; particle positions use a deterministic sequence, not the original process RNG.
- SUNA (29) uses a deterministic grayscale noise plane; the native process RNG sequence is not reproduced.
- HAIKEIYURE (100) mode 1 moves the composed scene as a group; native MoveScreen changes only the already-rendered screen buffer at that layer.
- GRADATION (50) now applies non-negative vertical RGB offsets to the already-composed scene at its layer position. Negative/subtractive operands still produce an explicit unsupported warning; full target-scene parity remains to be visually accepted.

These warnings must not be treated as cosmetic. They identify real behavioural gaps in the effects that are active in the user's intro sequence.

## 1B. Source-confirmed causes and boundaries

### CLOUD (82): bounded partial-height path; the moving source is not yet identified

The supplied runtime audit records this literal parameter vector for CLOUD on layer 1:

`0,300,13,4,9999,200,1362,200,0,1,255,160,110,50`

For the Android path:
- the accepted software compositor requires parameter 0 = 0 and parameter 8 = 0; this vector passes those gates;
- parameter 1 requests 300 output rows, consistent with a partial upper band over stationary lower scenery;
- cloudWarpPixels returns a bitmap whose height is exactly those 300 rows;
- the renderer evaluates this bounded path at frame 0;
- in the pinned native source, SetParam copies parameter 0 into `countMax`; CAllEffect::CountUp increments only while count < countMax; CLOUD::Print updates m_x/m_y only while count > 0. With parameter 0 equal to 0, the native counter does not advance and this vector does not animate in that reference implementation.

The static output is consistent with this exact parameter vector; it is not safe to add an independent Android frame clock and call that a parity fix. The user's observed cloud drift remains a valid scene-level finding, but its source must be identified from the actual layer/effect/resource and command offsets. Keep the 300-row partial-height output when it represents the intended upper band; do not stretch it across the whole scene.

### GRADATION (50): the missing red scene grade

The pinned System-NNN family reference identifies effect 50 as GRADATION, between 49 BGDARK and 51 TAIYO. The reference software renderer computes RGB deltas from top to bottom and applies the resulting per-row colour adjustment to the already-rendered screen buffer over a configured vertical range. It is not simply a red tint placed on the male bitmap. Ramen now ports the non-negative/additive case at the same per-layer composition point; negative RGB-дельты remain explicitly unsupported because the current Compose path uses saturating Plus blending.

Ramen currently accepts effect 50 into layer state but hasNativeLayerEffectPath(50, ...) returns false. The source bitmap is consequently rendered by the ordinary fallback and the user sees the incomplete-effect warning. A faithful implementation needs a snapshot/compositor pass at the correct layer position: grade what has already been drawn, then continue rendering subsequent layers. Applying a red filter only to layer 5 would be the wrong model if the native operation is screen-buffer based.

The target executable's build variant still needs confirmation against actual target output; the pinned family source is strong reference evidence, not proof of every target-specific detail.

### CRT effects and transparency

The current renderer handles KE (27), SUNA (29), OLDFILM (55), and HAIKEIYURE (100) through known approximations:
- KE and SUNA generate deterministic Android patterns instead of the original process-global RNG stream.
- OLDFILM uses a deterministic scratch/scan-line model; the original creates and updates scratch lines with native rand() state.
- HAIKEIYURE mode 1 moves the entire Compose viewport as a group. The native operation changes the screen buffer at that point in the layer sequence.

A "glass" area in a CRT should normally be modelled using the correct DWQ alpha/mask and layer composition (possibly a separate monitor-frame layer), not by fading the entire monitor bitmap. The current code already decodes alpha masks, but the actual resource and its alpha pixels have not been tied to the user's monitor. Verify the exact loaded DWQ resources, alpha presence, layer indices, source/destination rectangles and visible intersections before changing alpha behaviour.

### Text and title animation are not the dialogue typewriter

The current 24 ms typewriter is attached only to SystemNnnPlayerUiState.dialogueText and the Compose dialogue box. It does not animate a separate centered line, a title card, or an interstitial bitmap.

The native family reference has effect 133 SENTENCE: it progressively reveals a tiled picture atlas using tile dimensions, direction, wipe mode and timing parameters. Ramen does not include 133 in its supported effect path. This is a candidate only if the target actually uses a tiled sentence/title image; it must not be assumed to be the missing title-text command. The target's relevant SPT command/resource and its timing must be identified from the diagnostic command trace.

### Layer persistence and apparent black interruptions

The Compose tree draws native sprite layers in ascending layer-index order, then applies layer-local overlay effects at that layer's position; a later higher-numbered layer can cover such an overlay. A screen-level overlay is drawn after all image layers. Thus a full-scene red flash must be attached to the screen-buffer slot (or otherwise explicitly composed after all scene layers), not arbitrarily to the male layer.

The ViewModel clears the full visual snapshot on SceneUpdate and explicit clear commands. A dialogue update that supplies no replacement sprite list can preserve pending/visible layers. This can explain old cloud/figure layers persisting between some visual events, but cannot yet prove whether the missing clear is in script flow or runtime semantics. The multi-second black gaps also need the command/effect trace; do not automatically classify them as a bitmap decoding failure.

## 1C. Revised fix order

1. **Upper cloud motion:** use the expanded runtime layer diagnostics to identify the actual moving resource/effect and its command offsets. Do not add a clock to the observed CLOUD 82 vector with parameter 0 = 0 unless further native evidence establishes a different update path. Preserve the 300-row partial-height compositor output and the stationary lower scenery.
2. **Layer evidence and CRT composite:** add final pixel bounds/visible intersection, alpha-presence/mask metadata, layer index, command offset, native rectangles and effect frame to diagnostics. Then correct monitor frame/glass/woman order without reducing the whole monitor's opacity.
3. **GRADATION (50):** implement a screen-buffer vertical RGB grade at the correct layer/compositor point, then compare the red scene transition. Do not tint only the man sprite.
4. **Clear/persistence and global flash:** trace scene/film boundaries and actual CLEAR commands; ensure the figure is gone before the interstitial. Check FLASH/FILL/GRADATION command scope and z-order so a true screen flash covers all intended content.
5. **Non-dialogue title/text:** locate the actual SPT command and resource for the centered line, interstitial insert and game title. Implement progressive reveal only after distinguishing text commands from SENTENCE (133) tiled-image animation.
6. **Improve native CRT parity:** separately improve OLDFILM, KE, SUNA random timing and HAIKEIYURE mode 1 buffer semantics. Keep warning labels until each is visually accepted.
7. Protect the accepted audio path and ordinary background bitmap path throughout.

No screenshot or screen recording was available for this review. These findings combine the user's verbal comparison with source-level facts and the existing SPT diagnostic vector; exact layer/resource attribution remains open where stated.

### Independent full-scene panorama: geometry investigation (separate from CLOUD band)

The moving cloud panorama and the town/village panorama are whole-scene image transitions, not the upper cloud-band case. The user confirms their movement directions are already correct (down for the first, right for the second); the defect is that the source image covers only part of the viewport and leaves the rest blank/white. The known CLOUD (82) layer-1 vector must not be used as an explanation for these two transitions.

The current renderer clips layers to the game viewport and sizes native layers from their native rectangle. For rectangle motion, `moveRect` interpolates the origin while retaining the source width and height until the final frame. Thus an undersized source rectangle can remain undersized throughout the motion; a target rectangle inconsistent with the bitmap, incorrect reference dimensions, a crop, or missing wrap/repeat can expose a blank region even while the movement vector itself is correct. This is a concrete candidate to inspect, not yet a confirmed root cause for either panorama. The exact native effect/resource is not yet identified from verbal symptoms alone. Inspect its `effect_set` and `layer_rect` diagnostic records, bitmap dimensions, `fromRect`, `toRect`, viewport size, and the final visible intersection before changing the movement maths.

Acceptance for these panorama scenes is full intended viewport coverage throughout the transition, in the correct direction, with no blank half and no stale previous panorama. This is a separate test from the stationary upper cloud band.

---

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
- CLOUD (82) is handled only for the observed unmasked software parameter case. The supplied vector has parameter 0 = 0; in the pinned native reference that sets countMax to zero, so CountUp cannot advance and CLOUD's m_x/m_y remain stationary. The frame-0 Android output is consistent with that vector. The scene's reported cloud drift must be matched to its true moving layer/effect/resource. The target EXE's build variant is not established.
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
