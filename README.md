# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/).

## v2.1 Highlights
- **Zero-Lag / Instant Popup Show (Fixed v2.0 UI Hang):**
  - Removed recursive full-tree Accessibility walks (`findBestTightNodeBoundsForText`, post-order `findSelectionInTree`, and recursive `findSelectedOrFocusedNode`), replacing them with **O(1)** `event.source`, `event.text`, and `findFocus(FOCUS_INPUT)` lookups.
  - Removed blocking `refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)` IPC calls and eliminated the `TYPE_WINDOW_CONTENT_CHANGED` / `scheduleToolbarRefinementCheck` loop while the popup is visible.
  - Removed manual `v.measure()` calls outside `ViewRootImpl` and moved all `FaviconManager` disk cache reads off the UI thread so the popup renders in `< 2ms` with zero UI stutter.
- **Zero-Overlap Above & Below Selection + Open Keyboard (IME) Awareness:**
  - Single cached `refreshScreenMetricsCache()` check detects open soft keyboards (`AccessibilityWindowInfo.TYPE_INPUT_METHOD`) and native `"Copy"` / `"Cut"` selection bars while ignoring keyboard suggestion strips and browser bottom URL-bar `"Share"` buttons.
- **`↗ WEB SEARCH ▸` Subgroup with Top 4 Search Engines & Live Favicons:** Groups **Google**, **DuckDuckGo**, **Bing**, and **Brave Search** into a dedicated subfolder, displaying each search engine's real favicon.
- **Automatic Favicons for All Future User-Added Web Engines:** `FaviconManager` automatically extracts the domain from any `http://` or `https://` command added via the app (`03:RULES` quick-adder or `rules.txt`), downloads its favicon in the background, and caches it in memory + disk (`cacheDir/favicons/`).
- **`▶ YOUTUBE ▸` Subfolder:** Consolidates all YouTube search filters (`All Time`, `Last Hour`, `Today`, `This Week`, `This Month`, `This Year` + custom `YT/...` rules) into a clean subfolder like `M↓ MARKDOWN ▸`.
