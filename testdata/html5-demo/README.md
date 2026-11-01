# Ramen HTML5 Test Novel

This folder contains a tiny, original visual-novel-style project for validating Ramen's future HTML5 runtime.

## Contents

- `index.html` — entry point and semantic UI.
- `styles.css` — local responsive presentation and original CSS scene artwork.
- `app.js` — dialogue, choices, local save/load, and a generated Web Audio test tone.

## Run

Open `index.html` in a modern browser. The demo does not require a server, external libraries, remote assets, or network access.

## Test checklist

1. Open the page and confirm the first dialogue appears.
2. Continue to the choice screen.
3. Select each choice and confirm it reaches a different ending.
4. Save on a later scene, restart the story, then load the saved scene.
5. Tap **Проверить звук** after a user interaction and listen for a short generated tone.
6. Resize the browser to a narrow mobile viewport and check that controls remain usable.
7. Disconnect the network and confirm the demo still works.

## Notes

- All story text and visual artwork are original and included in this folder.
- No third-party game assets or copyrighted scripts are included.
- The audio check synthesizes a short test tone with the Web Audio API; it is not a test of decoding external audio files.
- Save data is stored in browser `localStorage`, which may behave differently in an embedded WebView or a restricted local origin. That difference is intentionally part of the runtime investigation.
- This fixture is not an engine compatibility claim and has not yet been validated inside Ramen's Android WebView.
