# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`.

## Features
- **Instant Browser & App Overlay:** Automatically spawns the vertical palette across native apps, Google Keep, Twitter/X, and Browsers (via deep Accessibility tree inspection, automatic WebView `ACTION_COPY` extraction, and `ACTION_PROCESS_TEXT` fallback).
- **Touch Drag-and-Drop Reordering (`≡`):** Grab and slide the `≡` handle to reorder tools in real time (or toggle `[✓]` to hide tools you don't need), plus automatic **Most-Used on Top** sorting.
- **In-Popup Power Tools:**
  - `🧲 HASH → MAGNET` — Converts 40/64-char hex or 32-char Base32 torrent info-hashes into magnet links (with tracker list & 1-tap client launch).
  - `▣ QR CODE` — Scannable QR code rendered directly inside the popup.
  - `📖 DEFINE WORD` — Dictionary phonetics & definitions (with Wikipedia fallback) inside the popup.
  - `🌐 TRANSLATE` — Instant in-popup translation with 1-tap replace/copy.
  - `🔊 SPEAK ALOUD` — Native offline TTS readout of selected text.
  - `＠ EXTRACT LINKS` — Pulls all URLs, emails, phone numbers, and `#tags` from messy selections.
  - `🧹 CLEAN TEXT` — Unwraps hard line breaks and strips invisible characters.
  - `⇄ UNIT CONVERTER` — Contextual auto-conversion for `km/mi`, `kg/lb`, `°C/°F`, `cm/in`, `ft/m`, and `0xHEX`.
  - `▶ YOUTUBE` (`▶d TODAY`, `▶w LAST WEEK`, `▶m MONTH`) & `🕵 INCOGNITO` browser.
