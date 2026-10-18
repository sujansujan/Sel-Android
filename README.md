# `[sel_]` — Plain-Text Vertical Selection Palette for Android

A minimal, zero-bloat (<1 MB) PopClip + Google Lens plain-text alternative for Android built with pure native views and `TYPE_ACCESSIBILITY_OVERLAY`, inspired by the clean ASCII scoreboard aesthetic of [PlainTextSports.com](https://plaintextsports.com/).

## v1.8 Highlights
- **Strict Above/Below Selection Positioning (Zero Overlap):** Uses Android `EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY` character-level bounds + proportional multi-line slicing and pins the popup strictly above (`Gravity.BOTTOM`) or strictly below (`Gravity.TOP`) the selection so it never covers highlighted text.
- **Dynamic Popup Sizing:** Automatically scales popup scroll height and width based on the exact vertical space available above or below the selection.
- **Oversized `[ × ]` Close Button & Immediate Outside-Tap Dismissal:** Features a large `46dp × 38dp` framed `[ × ]` close button in the header and immediately hides when tapping anywhere outside the popup (`ACTION_OUTSIDE` + `TYPE_VIEW_CLICKED`).
- **PlainTextSports.com-Inspired Boxed Rows & Generous Spacing:** Every command row and dashboard item features tabular `│` column dividers, bordered card framing, `14dp × 12dp` internal padding, and `4dp` vertical gaps between items.
- **5-Tab Terminal Dashboard + Full JSON Backup/Restore:** Organized into `01:HOME`, `02:TOOLS`, `03:RULES`, `04:CLIPS`, and `05:BACKUP`.
