# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`.

## Features
- **Universal X-Ray Screen Text Grabber (`⊞ GRAB SCREEN`):** Select and extract text on any screen even when `textIsSelectable="false"`. Triggerable via 3rd-party Intent (`io.github.selclip.ACTION_GRAB_SCREEN`), Deep Link (`sel://grab`), Quick Settings Tile (`[sel] Grab Text`), or Assist shortcut.
- **In-Popup Magnet Converter (`🧲 HASH → MAGNET`):** Displays the generated `magnet:?xt=urn:btih:...` link directly inside the popup with `[ ⎘ COPY MAGNET ]`, `[ 📋 PASTE / REPLACE ]`, and `[ ⚡ OPEN IN TORRENT APP ]` options.
- **Crash-Free Touch Drag-and-Drop Reordering (`≡`):** Smooth translation-based drag-and-drop sorting that never detaches views mid-gesture.

## 3rd-Party Intent for Screen Text Extraction
- **Action:** `io.github.selclip.ACTION_GRAB_SCREEN`
- **Package:** `io.github.selclip`
- **Activity Component:** `io.github.selclip/io.github.selclip.ScreenGrabTriggerActivity`
- **Deep Link URI:** `sel://grab`
