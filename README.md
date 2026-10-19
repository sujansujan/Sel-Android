# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/).

## v2.3 Highlights
- **Up to 2 Additional Custom Icon-Only Tool Rows (`ROW 1` & `ROW 2`):**
  - Alongside the built-in 4-icon edit strip (`[ ⎘ ] [ 📋 ] [ ✂ ] [ ⬚ ]`), you can assign up to 5 tools per row into **2 additional icon-only rows** (`R1` and `R2`).
  - Tools assigned to `R1` or `R2` display as compact, single-line icon buttons right below Copy/Paste/Cut/Select All and are automatically removed from the text list below so nothing is duplicated.
  - Tap `[LIST]` → `[★R1]` → `[★R2]` on any tool in the `02:TOOLS` tab or inside the popup's `⚙` configure view (or tap `[ ↺ LOAD 2-ROW PRESET ]`).
- **100% Unique Built-In Tool Icons:**
  - Every built-in tool, header control, and submenu item has its own distinct glyph (`⎘` Copy, `📋` Paste, `✂` Cut, `⬚` Select All, `⌖` Lens Grab, `🔎` Web Search, `▶` YouTube, `🗂` Clip History, `M↓` Markdown, `λ` Regex/Pipe, `📖` Define, `🌐` Translate, `▣` QR Code, `🧲` Magnet, `⌫` Clean URL, `＠` Extract, `🧹` Clean Text, `⇅` Sort Lines, `Aa` Cycle Case, `🕵` Incognito, `🔊` Speak, `⊕` Append Log, `⚙` Configure).
- **External Browser Incognito Mode (`🕵 INCOGNITO`):**
  - Tapping `🕵` opens your **actual installed browser in Incognito / Private mode** (passes `private_browsing_mode` / `EXTRA_OPEN_NEW_INCOGNITO_TAB` / `OPEN_AS_INCOGNITO` for Firefox/Focus/DuckDuckGo, and uses an Accessibility helper for Chrome/Brave/Edge/Samsung Internet to automatically open `More options (⋮) → New incognito/private tab` and submit the URL/query without touching normal history).
  - Long-pressing `🕵` opens `[sel]`'s internal zero-history WebView as a quick fallback.
