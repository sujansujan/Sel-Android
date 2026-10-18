# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/).

## v1.9 Highlights
- **Zero-Overlap Above & Below Selection Positioning:** Uses a unified `Gravity.TOP` screen coordinate space (`getRealMetrics` / `currentWindowMetrics`), detects Android & browser native floating selection toolbars (`Copy | Select all`), measures the popup's exact height, and locks `params.y = safeZone.top - gap - popupHeight` with an `OnLayoutChangeListener` so the popup **never** overlaps the selection when placed above or below.
- **`↗ WEB SEARCH ▸` Subgroup with Top 4 Search Engines & Live Favicons:** Groups **Google**, **DuckDuckGo**, **Bing**, and **Brave Search** into a dedicated subfolder, displaying each search engine's real favicon.
- **Automatic Favicons for All Future User-Added Web Engines:** `FaviconManager` automatically extracts the domain from any `http://` or `https://` command added via the app (`03:RULES` quick-adder or `rules.txt`), downloads its favicon in the background, and caches it in memory + disk (`cacheDir/favicons/`).
- **`▶ YOUTUBE ▸` Subfolder:** Consolidates all YouTube search filters (`All Time`, `Last Hour`, `Today`, `This Week`, `This Month`, `This Year` + custom `YT/...` rules) into a clean subfolder like `M↓ MARKDOWN ▸`.
