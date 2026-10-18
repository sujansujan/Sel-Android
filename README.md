# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`.

## Features
- **Vertical TUI Palette:** Docked along the right screen edge so it never collides with Android's horizontal `Copy | Paste` menu.
- **Universal Compatibility:** Works automatically across native apps, Google Keep, Twitter/X, and Browsers (via deep Accessibility tree inspection, Android 10+ clipboard focus probe, and `ACTION_PROCESS_TEXT` selection bar integration).
- **Monochrome Glyph Column:** Fixed-width Unicode symbols (`⌫`, `∑`, `⎘`, `Aa`, `❝`, `⟨⟩`, `⇅`, `⊕`, `↗`).
- **Plain-Text Custom Rules:** Add custom URL actions or text wrappers directly inside the app using `GLYPH | LABEL | TARGET`.

## Enabling on Android 13+
1. Install the APK from GitHub Actions Artifacts.
2. Open **Settings -> Apps -> [sel] -> ⋮ (top right) -> Allow restricted settings**.
3. Open `[sel]` and enable it in Accessibility Settings.
