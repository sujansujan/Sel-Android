# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A fast, minimal PopClip + Google Lens plain-text alternative for Android built with pure native views, `TYPE_ACCESSIBILITY_OVERLAY`, and on-device Google Play Services ML Kit OCR, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/) and styled with the official [Dracula & Alucard](https://draculatheme.com/spec) palettes.

## v2.7.0 Highlights
- **Universal Cross-App Popup Reliability (`SelectionDetector`):**
  - Detects floating ActionMode & selection toolbars across **all** apps (Chrome, Firefox/GeckoView, WebViews, Jetpack Compose `SelectionContainer`, WhatsApp, Telegram, Gmail, React Native, PDF readers) via multi-window scanning, view-ID matching, deep BFS, and class-agnostic `ACTION_COPY`.
  - Uses API 26+ `EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY` for exact pixel selection bounds and `WindowInsets` / IME awareness so the popup never gets hidden behind notches, nav bars, or keyboards.
  - Eliminated premature touch-dismissal bugs, stuck-detached overlay states, stale foreground package tracking, and clipboard listener leaks.
- **Intuitive Dual-Mode Screen Grab + On-Device Pixel OCR (`ScreenTextHarvester`, `ScreenOcrEngine`, `LensOverlayView`):**
  - Translucent screen visibility keeps the real app crisp underneath—with accurate per-line highlights, natural multi-tap block selection, **live drag-to-lasso box selection**, 1-tap detected entity chips, and a **`[LIST]` Reader + `[EDIT]` Inline Editor** sheet.
  - Built-in **Google Play Services ML Kit Pixel OCR** (`[OCR]` button & automatic fallback when 0 accessibility nodes exist) freezes video/image frames via API 30+ `takeScreenshot()` and recognizes text right off images, memes, and videos.
- **Performance, Modularity & Automated CI Tests:**
  - Cached `rules.txt`, blacklist, and clipboard history in memory (`0` disk reads per popup open), pre-parsed `ThemePalette` colors, pre-compiled regexes (`TextTransforms`), bounded cached `NetworkTools`, lazy `TextToSpeech`, and removed dead background favicon fetching.
  - Modularized `MainActivity` into dedicated tab controllers (`Home`, `Settings`, `Tools`, `Rules`, `Clips`) with shared `UiKit` helpers, and added JVM JUnit tests executed automatically in GitHub Actions.
