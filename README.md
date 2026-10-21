# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/) and styled with the official [Dracula & Alucard](https://draculatheme.com/spec) palettes.

## v2.5.0 Highlights
- **Official Dracula (Dark) & Alucard (Light) Themes + Auto System Toggle:**
  - Automatically follows Android system Dark / Light mode out of the box (`Dracula` `#282A36` in Dark mode, `Alucard` `#FFFBEB` in Light mode), with a toggle in Settings (`[ FOLLOW SYSTEM: ON/OFF ]` + manual Dracula/Alucard switch).
- **Computer-Shortcut Inspired Top Icon Rows (Max 4 Items) & Text-Only List Below:**
  - Top rows (`EDIT ROW`: `[^C] [^V] [^X] [^A]`, plus `ROW 1` and `ROW 2`) display **only** bracketed icons and are capped at **4 items per row**.
  - Tools in the 1-column list and 2-column grid below the top rows display **clean text labels only** — showing a tool's bracketed icon (`[/]`, `[|>]`, `[≡]`, `[::]`, `[?=]`, `[+]`, `[<>]`, `[**]`, `[s/]`, `[W]`, `[>>]`, `[Aa]`, `[~]`, `[?x]`, `[A↓]`, `[@#]`, `[0x]`, `[~)]`, `[D/]`) **only** when placed on a top row.
- **Custom Folder Builder in Settings (Default `DEV` Folder Removed):**
  - Removed the pre-bundled `DEV` folder and `Incognito` tool completely.
  - Create your own custom folders by name in `02:TOOLS` and tap `[DIR:ROOT]` on any built-in or custom tool to keep the items you want inside your folders.
