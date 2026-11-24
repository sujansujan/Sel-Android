# `[sel]` — Smart Text Selection Palette & Google Lens-Style Grab for Android

A fast, lightweight PopClip + Google Lens text utility for Android built with pure native views, `TYPE_ACCESSIBILITY_OVERLAY`, and on-device Google Play Services ML Kit OCR.

## v2.8.0 Highlights
- **Exact Google Lens-Style Screen Text Grab (`ScreenOcrEngine`, `LensOverlayView`):**
  - Freezes your screen instantly with a smooth scan shimmer while on-device ML Kit OCR detects every individual word (`TextBlock → Line → Element`), falling back cleanly to Accessibility word harvesting on Android 8–10 or `FLAG_SECURE` screens.
  - Soft frosted line pills keep the screen crystal clear; tap any word to select, double-tap to select a paragraph, drag across words to sweep-select, or drag left/right **teardrop handles** with a live floating loupe preview above your thumb.
  - Modern rounded Google Lens bottom sheet with **Copy text**, contextual smart pills (**Call**, **Open link**, **Clean URL**, **Magnet**, **Define**), **Search**, inline **Translate**, **Listen**, **More tools**, and inline **Edit**.
- **Modern Floating Selection Popup & Clean App UI:**
  - Elevated `16dp` rounded floating card with smooth pop-in animation, crisp sans-serif typography, comfortable tap targets, clear **Copy / Paste / Cut / All** quick bar, human-readable tool labels, and smart context banners.
- **Universal Cross-App Popup Reliability & Performance:**
  - Works reliably across Chrome, Firefox, WebViews, Jetpack Compose, WhatsApp, Telegram, Gmail, React Native, and PDF apps via multi-window toolbar detection, deep BFS, API 26+ exact character bounds, in-memory preference/rule caching, and automated GitHub Actions unit tests.
