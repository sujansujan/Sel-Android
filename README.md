# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`.

## v1.6 Highlights
- **Google Lens-Style Plain-Text Screen Grabber (`⊞ LENS GRAB`):** Select and copy unselectable screen text directly in place like Google Lens—rendered in a crisp monospace terminal aesthetic. Tap individual word chips, double-tap to select a block, swipe across words, or drag the `[` and `]` terminal handles at 60fps.
- **Context-Aware Popup Items:** Tools appear only when relevant to your selection (`🧲 HASH → MAGNET` only on torrent hashes, `⌫ CLEAN URL` only on URLs with tracking/query parameters, `📖 DEFINE WORD` only on 1–4 word selections, `⇅ SORT LINES` only on multi-line text, `🧹 CLEAN TEXT` only on messy whitespace/linebreaks, and `＠ EXTRACT LINKS` only when URLs/emails/phones/tags are present).
- **Markdown Folder Submenu (`M↓ MARKDOWN ▸`):** All Markdown formatting tools (`MD BOLD`, `MD ITALIC`, `MD CODE`, `MD QUOTE`, `MD LINK`, `MD STRIKE`, `MD BULLET LIST`, `MD TASK LIST`, `MD HEADING`, `MD SPOILER`) live inside an inline folder submenu.

## 3rd-Party Intent for Screen Text Extraction
- **Action:** `io.github.selclip.ACTION_GRAB_SCREEN`
- **Package:** `io.github.selclip`
- **Activity Component:** `io.github.selclip/io.github.selclip.ScreenGrabTriggerActivity`
- **Deep Link URI:** `sel://grab`
