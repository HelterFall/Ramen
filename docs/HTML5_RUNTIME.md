# Restricted HTML5 Runtime

## Status

The first WebView-backed HTML5 player is implemented as a prototype. CI verifies compilation, unit tests, and lint; execution of the fixture on physical Android devices is still a manual acceptance test.

## What can launch

Ramen currently enables the **limited HTML5** status only when an imported project has a root-level `index.html` and no detected TyranoScript marker. The player is intended for self-contained projects whose scripts, styles, images, audio, and other resources are stored beside that entry point and referenced with relative URLs.

This is not a general browser, a complete HTML5 game-engine implementation, or a claim that arbitrary projects will work.

## Resource loading and isolation

- The game is copied into Ramen's app-private `files/games/<id>` directory by the bounded importers.
- `WebViewAssetLoader` serves only the currently selected game's directory through the app-controlled `https://appassets.androidplatform.net/game/` origin. The handler is rooted at that game's directory, not the whole application data directory.
- Each game is assigned a separate WebView multi-profile session. Its DOM storage (including `localStorage`) is therefore separated from other games. The player fails closed when the installed Android System WebView does not support multi-profile sessions.
- File and content URL access are disabled. File-to-file and universal file URL access are disabled as well.
- The player does not add a JavaScript interface or expose Android methods to game scripts.
- Network loads, mixed content, pop-up windows, external navigation, geolocation, and WebView permission requests are blocked. The Android app does not request the `INTERNET` permission.

JavaScript and DOM storage are enabled because the local fixture needs dialogue logic, local saves, and Web Audio. Those capabilities are granted only inside the isolated local WebView profile.

## Known limitations

- Only a root-level `index.html` is eligible for this initial runtime.
- External fonts, APIs, CDNs, remote assets, and network-dependent features will not work.
- TyranoScript, Ren'Py, KiriKiri, and other engine-specific formats remain unsupported even when their markers are detected.
- There is no Android-to-JavaScript bridge, native save integration, download handling, gamepad layer, engine-specific full-screen/orientation policy, or advanced browser extension support.
- WebView behavior can vary with the Android System WebView version. Devices without multi-profile support cannot launch games through this prototype.

## Device acceptance checklist

1. Import `testdata/html5-demo` using **Choose folder** or package the fixture as a ZIP and import it.
2. Confirm the library labels it `HTML5 (ограниченная поддержка)` and shows **Запустить**.
3. Launch it and confirm the first dialogue, continuation, choices, and distinct endings work.
4. Save progress, restart the story, load the saved point, close the player, and relaunch to verify the save remains.
5. Tap **Проверить звук** after the page's user interaction and confirm the Web Audio test tone plays.
6. Open an external link or trigger an external resource request and confirm it is blocked.
7. Import two copies of the fixture, write different values to local storage in each, and verify their values are not shared.
8. Send Ramen to the background and return; confirm the WebView pauses and resumes without crashing.
9. Repeat on a second Android version / Android System WebView release if available.

Passing CI is not a substitute for this physical-device checklist.
