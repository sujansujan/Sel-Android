# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`.

## v1.7 Highlights
- **5-Tab Terminal Dashboard (`MainActivity`):** Organized into `01:HOME`, `02:TOOLS`, `03:RULES`, `04:CLIPS`, and `05:BACKUP`.
- **Settings & Custom Commands Backup/Restore:** Export and import your entire configuration (tool order, hidden tools, use counts, pinned clips, per-app blacklist, and `rules.txt` custom commands) via `.json` file, clipboard, or share sheet.
- **Google Lens-Style Plain-Text Screen Grabber (`⊞ LENS GRAB`):** Includes `[+ADD]` multi-region selection, draggable `[` and `]` handles, a context-aware smart bottom button, and Android 11+ `takeScreenshot()` fallback.
- **Ring-Buffer Clipboard History (`📋 CLIP HISTORY ▸`), Regex/Pipe Submenu (`λ REGEX / PIPE ▸`), Markdown Submenu (`M↓ MARKDOWN ▸`), & Custom Folders (`FOLDER/LABEL`).**
- **UX Polish:** 1-tap `↶ UNDO LAST EDIT`, collapse-to-pill (`[–]` -> `[sel_ ▸]`), magnetic edge-snap, and per-app mute (`⊘ MUTE IN APP`).
