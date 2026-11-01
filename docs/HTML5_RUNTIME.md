# Restricted HTML5 Runtime

## Status

The first WebView-backed HTML5 player is implemented as a prototype. CI verifies compilation, unit tests, and lint; execution of the fixture on physical Android devices is still a manual acceptance test.

## What can launch

Ramen currently enables the **limited HTML5** status only when an imported project has a root-level `index.html` and no detected TyranoScript marker. For a full project, import the directory containing the root `index.html` or a ZIP of that directory; its scripts, styles, images, audio, and other resources can then be loaded using relative URLs. A separate **HTML file** import is available for standalone `.html`/`.htm` pages. It copies the selected document as `index.html` and extracts supported embedded base64 images and audio into private local asset files. It does not import neighbouring CSS, JavaScript, images, or audio files, so relative references to adjacent files still require folder or ZIP import.

This is not a general browser, a complete HTML5 game-engine implementation, or a claim that arbitrary projects will work.

## Resource loading and isolation

- The game is copied into Ramen's app-private `files/games/<id>` directory by the bounded importers.
- `WebViewAssetLoader` serves only the currently selected game's directory through the app-controlled `https://appassets.androidplatform.net/game/` origin. The handler is rooted at that game's directory, not the whole application data directory.
- Each game is assigned a separate WebView multi-profile session. Its DOM storage (including `localStorage`) is therefore separated from other games. The player fails closed when the installed Android System WebView does not support multi-profile sessions.
- File and content URL access are disabled. File-to-file and universal file URL access are disabled as well.
- Supported embedded base64 image and audio data URIs in a standalone HTML document are streamed into the game's private `assets` directory during import; HTML references are rewritten to the local appassets origin. The extraction has per-resource and total size limits.
- The player reports JavaScript console errors, local asset loading failures, the installed WebView version, and a post-visual-state DOM summary in its runtime status area. The summary includes viewport dimensions, menu/card/button bounds and CSS visibility, the element hit-tested at the Play button center, stage state, and the native WebView's measured/visible bounds and hardware-acceleration state.
- The initial HTML navigation starts only after the Compose-hosted WebView has non-zero measured bounds. The diagnostic footer reserves a fixed height from the first composition and scrolls internally, so a long status message cannot shrink the WebView after navigation. If Compose changes the WebView's measured bounds, Ramen requests a relayout/redraw and dispatches a browser resize event; orientation/screen-layout changes receive the same treatment.
- Main-frame JavaScript errors are shown as an explicit failure instead of leaving an unexplained blank player.
- The player does not add a JavaScript interface or expose Android methods to game scripts.
- Network loads, mixed content, pop-up windows, external navigation, geolocation, and WebView permission requests are blocked. The Android app does not request the `INTERNET` permission.

JavaScript and DOM storage are enabled because the local fixture needs dialogue logic, local saves, and Web Audio. Those capabilities are granted only inside the isolated local WebView profile.

## Known limitations

- Only a root-level `index.html` is eligible for this initial runtime.
- Single-file imports are limited to one HTML document (maximum 512 MiB). Supported embedded base64 images and audio are extracted, but assets beside the original file are not copied; use folder or ZIP import for a multi-file project.
- External fonts, APIs, CDNs, remote assets, and network-dependent features will not work.
- TyranoScript, Ren'Py, KiriKiri, and other engine-specific formats remain unsupported even when their markers are detected.
- There is no Android-to-JavaScript bridge, native save integration, download handling, gamepad layer, engine-specific full-screen/orientation policy, or advanced browser extension support.
- WebView behavior can vary with the Android System WebView version. Devices without multi-profile support cannot launch games through this prototype.

## Device acceptance checklist

1. Import `testdata/html5-demo` using **Choose folder** or package the fixture as a ZIP and import it. For a standalone page, use **HTML file** and select a `.html`/`.htm` file.
2. Confirm the library labels it `HTML5 (ограниченная поддержка)` and shows **Запустить**.
3. Launch it and confirm the first dialogue, continuation, choices, and distinct endings work.
4. Save progress, restart the story, load the saved point, close the player, and relaunch to verify the save remains.
5. Tap **Проверить звук** after the page's user interaction and confirm the Web Audio test tone plays.
6. Open an external link or trigger an external resource request and confirm it is blocked.
7. Import two copies of the fixture, write different values to local storage in each, and verify their values are not shared.
8. Import a self-contained HTML page with embedded base64 background images and audio; confirm the assets are extracted and the scene renders.
9. Launch in portrait and confirm the reported DOM viewport roughly matches the native WebView size in dp; rotate to landscape and confirm both dimensions change to the new orientation without reloading the page. Repeat in the opposite direction.
10. Rotate the device while a scene and its audio are playing; confirm the current WebView session and audio are retained.
11. Send Ramen to the background and return; confirm the WebView pauses and resumes without crashing.
12. Check the runtime status area for the WebView version, DOM geometry (`menu`, card, Play button, hit-tested element, stage, viewport), and native WebView geometry (`Native WebView` size, screen position, visible rectangle, hardware acceleration). Record these values and any JavaScript or local-resource errors shown there.
13. Repeat on a second Android version / Android System WebView release if available.

Passing CI is not a substitute for this physical-device checklist.
