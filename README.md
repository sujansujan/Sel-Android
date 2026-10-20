# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/).

## v2.4.1 Highlights
- **Compact, Zero-Waste Plain-Text Popup:**
  - Removed duplicate code + label columns (`YT   YT >`, `WEB  WEB >`, `MD   MD >`, `LOG  LOG`, `WIKI WIKIPEDI`) so each cell renders a single clean plain-text label (`YOUTUBE >`, `WEB >`, `MD >`, `|> PIPE >`, `DEFINE`, `TRANSLATE`, `Aa CASE`, `INCOGNITO`, `SPEAK`, `+ LOG`, `WIKIPEDIA`, `DEV >`).
  - Tightened vertical row spacing (`includeFontPadding = false`, `4dp` vertical padding, and crisp `1dp` dividers) and narrowed popup width (`212dp–236dp` in 2-col mode, `174dp–204dp` in 1-col mode) to eliminate empty dead space while preserving comfortable touch targets.
- **Up to 2 Extra Plain-Text Bracket Rows (`ROW 1` & `ROW 2`):**
  - Alongside `[CPY] [PST] [CUT] [ALL]`, assign up to 5 tools per row into `ROW 1` and `ROW 2`.
- **External Browser Incognito Mode (`INCOGNITO`):**
  - Opens your actual installed browser in Incognito / Private mode (long-press `INCOGNITO` for `[sel]`'s internal zero-history WebView fallback).
