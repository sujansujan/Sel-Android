# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/).

## v2.2 Highlights
- **Nepal Phone Number Detection (`98...`, `97...`, `+977...`) with 1-Line Call, SMS, WhatsApp & WhatsApp Business Bar:**
  - Automatically detects local (`98XXXXXXXX`, `97XXXXXXXX`) and international (`+977...`, `00977...`) phone numbers and pins a 4-button horizontal action bar (`📞CALL`, `💬SMS`, `🟢WA`, `🏢W4B`) right at the top of the popup.
  - Automatically prepends country code `977` for local `98...` / `97...` numbers when opening **WhatsApp** (`com.whatsapp`) or **WhatsApp Business** (`com.whatsapp.w4b`) so chats open directly without needing to save the contact first.
- **Single-Line Icon-Only Edit Strip (`[ ⎘ ] [ 📋 ] [ ✂ ] [ ⬚ ]`):**
  - Replaces the full-width `COPY TEXT` row with 4 equal-width icon-only buttons on a single line: **Copy** (`⎘`), **Paste** (`📋`), **Cut** (`✂`), and **Select All** (`⬚`).
  - Supports power long-presses: long-press `⎘` to **Append Copy (`+⎘`)** to your current clipboard; long-press `📋` to jump straight to **Clipboard History**.
- **1-Tap `⊞` / `☷` 2-Column Compact Grid Mode + Long-Press Folder Shortcuts:**
  - Tap `⊞` in the popup header to toggle between the classic **1-Column List** and a **2-Column Compact Grid** that fits 2× as many tools on screen without scrolling.
  - Long-press `↗ WEB SEARCH ▸` to immediately search Google, `▶ YOUTUBE ▸` to immediately search YouTube, or `M↓ MARKDOWN ▸` to bold text in 1 gesture.
