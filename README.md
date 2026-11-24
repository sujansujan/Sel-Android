# `[sel_]` — PopClip-Inspired ASCII Selection Bar for Android

A minimal, context-aware PopClip alternative for Android styled with crisp ASCII / Dracula & Alucard aesthetics, built with pure native views, `TYPE_ACCESSIBILITY_OVERLAY`, and optional on-device Google Play Services ML Kit OCR.

## v2.9.0 Highlights
- **True Context-Aware macOS PopClip Horizontal ASCII Bar (`PopupContextEngine`):**
  - Shows **only the 3–5 actions relevant to what you selected**—never dumping 20 buttons on every highlight:
    - Phone → `CALL | SMS | WA | W4B | ^C | +`
    - Dirty URL → `OPEN | CLEAN | ^C | QR | +`
    - Email → `MAIL | ^C | WEB | +`
    - Math / Unit / Timestamp / Base64 → `= <answer> | ^C | +`
    - Torrent Hash → `MAGNET | OPEN | ^C | +`
    - 1–3 Words (read-only) → `^C | DEF | TR | WEB | YT | +` (omits useless Cut/Paste!)
    - Editable text box → automatically includes `^X` (Cut), `^V` (Paste), and `UNDO`
  - Tap `[+]` on the right of the bar whenever you want the full compact ASCII tools drawer.
- **Instant, Pixel-Aligned Tap-to-Select Screen Grab (`LensOverlayView`):**
  - Launches in `<15ms` with crystal-clear screen visibility and `getLocationOnScreen()` offset correction so every tap hits the exact word under your finger on every notch/cutout device.
  - Tap any word to select it, double-tap for paragraph, drag to sweep-select, or drag `[` / `]` handles—with the context-aware ASCII PopClip bar floating right beside your selection and `[OCR]` 1 tap away for images & videos.
- **Simplified 3-Tab ASCII App (`[HOME]`, `[ACTIONS]`, `[SETTINGS]`):**
  - Removed all duplicate toggles, top-row slot clutter, and redundant backup buttons for a clean, effortless experience.
