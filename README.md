# `[sel_]` — PopClip-Inspired ASCII Selection Bar for Android

A minimal, context-aware PopClip alternative for Android styled with crisp ASCII / Dracula & Alucard aesthetics, built with pure native views, `TYPE_ACCESSIBILITY_OVERLAY`, and optional on-device Google Play Services ML Kit OCR.

## v3.0.0 Highlights
- **Zero Native Redundancy + Bigger Thumb-Friendly ASCII PopClip Bar:**
  - Removed redundant `Copy`, `Cut`, `Paste`, and `Select All` from the main selection bar (since Android's native selection toolbar cannot be suppressed without root).
  - Larger, effortless **46dp+ touch targets** (`13sp` bold monospace) that wrap cleanly into 1–2 tidy rows with hairline `1dp` borders and `|` separators.
- **Always-On Customizable Search, YouTube & Pinned Actions:**
  - **Default Search (`GGL`/`DDG`/`BRV`/custom)**: Always on the bar — tap for instant search, **long-press** to open all search engines (and long-press any engine in the drawer to make it default!).
  - **YouTube (`YT`)**: Always on the bar — tap for instant YouTube search, **long-press** for time-filtered YouTube searches (`1H`, `24H`, `7D`, `30D`, `1Y`).
  - **Pin Anything (`[*]`)**: Pin any built-in or custom action in `[ACTIONS]` so it always stays on the bar alongside smart context actions (`CALL/SMS/WA/W4B`, `OPEN/CLEAN`, `MAIL`, `= answer`, `MAGNET`, `DEF/TR`, `LINKS`, `JSON`, `UNDO`).
- **Rock-Solid Selection Detection Across All Apps:**
  - Listens to `TYPE_WINDOWS_CHANGED`, `TYPE_VIEW_LONG_CLICKED`, `TYPE_VIEW_SELECTED`, and `TYPE_VIEW_TEXT_SELECTION_CHANGED` with two-stage delayed probes (`100ms` + `190ms`), Chromium/WebView `event.text` fallback, automatic toolbar-signature reset on dismissal, and a reliable `360ms` Android 10+ clipboard focus probe.
- **Overhauled Block-First Screen Text Grab (`LensOverlayView`):**
  - Built on mobile-first **block-first tapping** (Universal Copy pattern + Fitts's Law thumb-zone dock): generous `+6dp` padded hitboxes with `getLocationOnScreen()` pixel alignment, multi-block tap stacking (`[1]`, `[2]`), double-tap / `[EDIT]` native Android `EditText` for real OS word/substring selection handles, `[LIST]` scrollable full-screen text reader, and `[OCR]` on-device ML Kit OCR for images & videos.
