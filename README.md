# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/) and styled with the official [Dracula & Alucard](https://draculatheme.com/spec) palettes.

## v2.6.0 UX Highlights
- **Streamlined `[HOME]` with 1-Tap Sandbox Test Chips & Smart Onboarding:**
  - The top-right status badge (`[ ● ACTIVE ↗ ]` / `[ ○ ENABLE ↗ ]`) is now clickable from every screen, and `[HOME]` collapses the setup banner into a single status line once the service is active.
  - Added 1-tap sample chips (`[PHONE]`, `[WORD]`, `[MATH]`, `[URL]`, `[HASH]`) above the Sandbox so anyone can test context-aware tools with a single tap, and tucked technical intent strings behind `[ ? AUTOMATION ▾ ]`.
- **Interactive 4-Slot Top Row Matrix & Checklist Folder Manager in `[TOOLS]`:**
  - Tap any slot in `ROW 1` or `ROW 2` (`[/]`, `[|>]`, `[<>]`, `[?=]`, or `[ + ]`) to assign or swap bracketed top-row icons via a plain-text slot picker dialog — eliminating button wrapping.
  - Tap `[ + ITEMS ]` on any custom folder to check/uncheck multiple tools into that folder at once.
- **1-Tap Popular Search Presets & Visual Rule List in `[RULES]`:**
  - Added 1-tap presets (`[+REDDIT]`, `[+MAPS]`, `[+PERPLEXITY]`, `[+GITHUB]`, `[+IMDB]`, `[+CHATGPT]`), a visual placement selector (`[ IN: WEB SEARCH > ]` / `[ IN: MAIN LIST ]` / `[ IN: YOUTUBE > ]` / custom folder), and a visual list of active custom rules with `[ × ]` delete buttons.
- **Unified Popup Behavior Controls & 1-Tap Installed App Picker in `[SET]`:**
  - Toggle Dracula/Alucard theme, `1-COL` vs `2-COL` layout, `LEFT` vs `RIGHT` screen edge, and `MOST USED` vs `MANUAL` sort in one place, plus mute apps via `[ + PICK INSTALLED APP ]` without typing package names.
- **Polished `[CLIPS]` & Submenu Header Navigation:**
  - Added `[ + SAVE CURRENT CLIP ]`, replaced the literal `"(Empty)"` scratchpad text with a proper hint + `[ ✓ SAVE NOTES ]`, and made the floating popup header display `< BACK · [MODE]` in submenus.
