# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/).

## v2.0 Highlights
- **Zero-Overlap Above & Below Selection + Open Keyboard (IME) Awareness:**
  - Detects open soft keyboards (`AccessibilityWindowInfo.TYPE_INPUT_METHOD`) via `getVisibleScreenBottom()` so the popup never overlaps the keyboard when anchored below (`▼`) and never mistakes keyboard clipboard chips (`[Copy] [Cut]`) for the floating selection toolbar.
  - Excludes standalone browser URL-bar `"Share"` buttons from `findNativeSelectionToolbarBounds()` and clusters candidate selection buttons by horizontal row (`centerY`), preventing browser bottom toolbars from pulling the popup down over the selection when anchored above (`▲`).
  - Separates raw selection bounds (`lastRawCandidateBounds`) from the resolved forbidden zone (`currentSafeZone`) so repeated layout passes never compound padding, and adds `findBestTightNodeBoundsForText()` + a 95ms/220ms delayed toolbar refinement check for WebViews.
  - Keeps the header word/character counter (`1W·8C ▲`) on a single crisp horizontal line.
- **`↗ WEB SEARCH ▸` Subgroup with Top 4 Search Engines & Live Favicons:** Groups **Google**, **DuckDuckGo**, **Bing**, and **Brave Search** into a dedicated subfolder, displaying each search engine's real favicon.
- **Automatic Favicons for All Future User-Added Web Engines:** `FaviconManager` automatically extracts the domain from any `http://` or `https://` command added via the app (`03:RULES` quick-adder or `rules.txt`), downloads its favicon in the background, and caches it in memory + disk (`cacheDir/favicons/`).
- **`▶ YOUTUBE ▸` Subfolder:** Consolidates all YouTube search filters (`All Time`, `Last Hour`, `Today`, `This Week`, `This Month`, `This Year` + custom `YT/...` rules) into a clean subfolder like `M↓ MARKDOWN ▸`.
