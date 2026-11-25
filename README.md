# Sel — PopClip-Style Selection Bar for Android (`v4.0.0`)

A fast, lightweight, power-efficient PopClip alternative for Android built from scratch with pure native Android views, `TYPE_ACCESSIBILITY_OVERLAY`, and zero bloated frameworks.

## Architecture & Engineering Principles
- **Explicit Lifecycle State Machine (`PopupStateMachine`)**: Deterministic transitions (`Hidden → PendingProbe → Visible(Bar | Drawer) → Hidden`) eliminate stuck windows, flicker, duplicate overlays, and stale toolbar state.
- **Smart Positioning & Clamping (`PopupPositioner`)**: Anchors near the active selection while reserving clearance for Android teardrop handles and nearby native `Copy/Cut/Paste` toolbars, automatically flipping above the selection when the keyboard (IME) is open and falling back cleanly when apps omit bounds.
- **Strictly Event-Driven & Near-Zero Idle Drain**:
  - Zero polling, zero background timers, zero wakelocks, zero foreground services.
  - Minimal `AccessibilityServiceInfo` (no `flagIncludeNotImportantViews` bloat) with O(1) short-circuits when idle.
  - Lazily initialized network (`LazyNetworkClient`) and speech (`LazyTtsSpeaker`) handlers allocate zero threads until actually invoked.
  - Decoupled companion UI (`MainActivity`) stays completely unloaded in the background.
- **Minimal Permissions & Dependencies**: `INTERNET` (on-demand Define/Translate only), `BIND_ACCESSIBILITY_SERVICE`, and pure-Java `zxing:core` (~15 KB after R8) for offline QR generation.

## Key Features
- **Zero Native Redundancy**: Omits redundant Copy/Cut/Paste/Select All on the floating bar and gives you spacious `48dp` touch targets in 1–2 tidy rows.
- **Always-On Search, YouTube & Pins**:
  - **Customizable Default Search**: Tap for instant lookup; long-press to switch engines (long-press any engine in the drawer to set default).
  - **YouTube (`YT`)**: Tap for instant YouTube search; long-press for time filters (`1H`, `24H`, `7D`, `30D`, `1Y`).
  - **Pin Anything (`★`)**: Pin any built-in action or custom search rule to stay permanently on the bar.
- **Context-Aware Actions**: Nepal & generic phone (`Call`/`SMS`/`WA`/`W4B`), URLs (`Open`/`Clean`/`QR`), Email, Torrent Hash/Magnet, inline Math/Unit/Hex/Timestamp/Base64 evaluator, JSON formatter, HTML stripper, Define Word (Dictionary + Wikipedia fallback), Translate to English, Entity Extractor, Markdown & Regex/Pipe drawers, Clipboard History, Scratchpad Notes, Per-App Mute, and JSON Backup/Restore.
- **Universal Fallback**: Also registers in Android's native selection menu via `ACTION_PROCESS_TEXT`.
