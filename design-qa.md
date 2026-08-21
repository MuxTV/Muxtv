# Lounge Light Home — design QA

## Source contract

- Reference: `C:\Users\Dmitry\AppData\Local\Temp\codex-clipboard-b99caf17-ce2c-4ff0-b1f5-427244d1c1e9.png`
- Implementation capture: `.work/evidence/screenshots/home-rail-reference-1080p.png`
- Target viewport: 1920×1080, Android TV API 36.
- Reference bitmap: 1672×941 (16:9), compared after normalization to 1920×1080.
- Device contract: 320 dpi, therefore 1920×1080 physical pixels = 960×540 dp.
- State: Home with permanent labelled rail; card focus is visible. The live clock and channel/programme data are dynamic and are compared by role and geometry, not literal value.

## Comparison history

### Pass 1 — blocked

- P0: rail is 496 px (`248dp`) versus ≈276 px in the normalized reference. Home starts at ≈608 px instead of ≈338 px and loses essential horizontal composition.
- P1: hero is visibly shorter while hero typography and cards are substantially larger and sparser than the reference.
- P1: evidence fixture has only one favorite and one recent item with no guide data, so it cannot validate the six-card rows or the Now/Next hero composition.
- P2: the implementation clock is live and monogram channel logos are the accepted offline fallback; these are annotated state/content differences, not visual regressions.
- Match: the generated local lake raster, warm parchment/bronze palette, bronze focus, and green-only progress direction agree with the approved reference.

### Pass 2 — blocked

- Capture: `.work/evidence/screenshots/home-rail-reference-1080p-pass2.png`.
- Match: the permanent rail is now 276 px (`138dp`), matching the normalized reference, and the Home focus journey keeps stable content bounds.
- P1: Home still begins at ≈388 px instead of ≈338 px because the existing 56dp screen inset was calibrated as if it were physical pixels.
- P1: hero height is ≈407 px instead of ≈513 px; the large title, fallback logo, cards, and section labels are roughly twice the visible reference scale.
- P1: card geometry allows only two full cards and pushes the recent row below the viewport, while the reference shows six favorites and six recent items.
- P1: brand and all rail labels ellipsize inside the now-correct rail width because their internal icon, spacing, and type scale still reflect the old 248dp rail.
- P2: focus outline and corner radii are heavier than the reference.

### Pass 3 — blocked

- Capture: `.work/evidence/screenshots/home-rail-reference-1080p-pass3.png`.
- Match: rail, hero frame, favorites, and recent row now occupy the same normalized 1080p grid as the source; all six items are visible.
- P1: the hero fixture still lacks reference programme timing/progress hierarchy and the CTA copy/icon.
- P1: rail background remains too cool and labels/cards retain heavier focus treatment.

### Pass 4 — 1080p passed, 720p follow-up blocked

- Capture: `.work/evidence/screenshots/home-rail-reference-1080p-pass4.png`.
- Match: 138dp persistent rail, warm selected/rail surfaces, hero frame, programme hierarchy, current/next timings, progress, CTA placement, six-card rows, compact recent row, and bronze/green semantic roles align with the normalized source.
- Accepted content substitutions: live clock values are intentionally dynamic; generated local lake art replaces the unavailable source artwork; channel logos remain the documented monogram fallback until a product logo-loading contract exists.
- 720p capture: `.work/evidence/screenshots/home-hero-rails-720p.png`; D-pad rail restoration and Home journey both pass, both sections remain within the viewport.
- P1 at 720p: the long primary CTA label is squeezed by the fractional hero text column. A fixed 300dp content column has been applied from the failed visual gate, but the final rebuild/recapture is pending Android SDK access outside the sandbox.

Final result: blocked — 1080p is accepted; the 720p CTA fix still needs one rebuilt device recapture.
