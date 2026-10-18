# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`.

## Features
- **Dynamic Autosizing Vertical Palette:** Wraps tightly around active tools and expands dynamically for inline views (`WRAP_CONTENT` with adaptive bounds).
- **In-Popup QR Code Generator (`▣ QR CODE`):** Renders a scannable QR code directly inside the floating popup.
- **In-Popup Word Definition (`📖 DEFINE WORD`):** Fetches dictionary phonetics/meanings (with Wikipedia summary fallback) directly inside the popup.
- **YouTube Time Filters:** Instant search for `▶ YOUTUBE`, `▶d YT TODAY`, `▶w YT LAST WEEK`, and `▶m YT MONTH`.
- **Incognito Search & Link Opener (`🕵 INCOGNITO`):** Opens selected URLs or searches in a built-in zero-history, cookie-free ephemeral browser (with 1-tap external private browser handoff).
- **Reorderable Tools + Most-Used on Top:** Automatically floats your most-used tools to the top, with manual `[↑]` / `[↓]` reordering available both inside the popup (`⇅`) and in the app dashboard.

## Enabling on Android 13+
1. Install the APK from GitHub Actions Artifacts.
2. Open **Settings -> Apps -> [sel] -> ⋮ (top right) -> Allow restricted settings**.
3. Open `[sel]` and enable it in Accessibility Settings.
