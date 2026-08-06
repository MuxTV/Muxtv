---
status: accepted
last_reviewed: 2026-08-06
owners: [design, ui, accessibility, performance]
primary_craft_reference: https://github.com/emilkowalski/skills
secondary_visual_reference: Open Design
---

# MuxTV UI craft principles

## 1. Source priority

MuxTV separates platform correctness from visual craft.

1. **Android TV / Compose TV platform contracts are non-negotiable** for focus, key routing, accessibility, lifecycle and performance.
2. **`emilkowalski/skills` is the primary craft reference** for interaction polish, motion decisions, component feel and review discipline.
3. **Open Design is secondary**: use it for composition exploration, visual alternatives, spacing/surface ideas and prototype comparison, not as the owner of remote semantics or navigation behavior.
4. MuxTV-specific product constraints remain authoritative: 10-foot readability, weak-device performance, deterministic D-pad reachability, Cyrillic/localization and bounded Guide/Channels state.

When references conflict, preserve platform correctness and TV speed first, then choose the higher-craft solution that does not weaken those constraints.

## 2. The TV adaptation of Emil Kowalski's design-engineering rules

The source skill emphasizes that details compound, animation needs a concrete purpose, frequent keyboard actions should not animate, UI movement should feel immediately responsive, and design review should compare concrete before/after choices rather than vague preference.

For MuxTV this becomes:

- **D-pad focus movement is immediate.** Do not animate focus scale, position or geometry for dense repeated navigation.
- **Never queue visual motion behind rapid key repeat.** Focus feedback must be visible in the same interaction frame whenever possible.
- **Use focus tone + outline + luminance + optional low-cost depth**, not a moving layout, as the default TV cursor language.
- **Selected != focused != playing != pressed.** Each state needs at least two cues across tone, outline, icon/badge, luminance or typography.
- **No animation because it “looks premium.”** Motion exists only for spatial explanation, state change, rare overlay/route context or deliberate feedback.
- **Keyboard/D-pad activation does not receive decorative press animation.** Native TV pressed state or instantaneous tonal/depth feedback is enough.
- Pointer/touch-only interactions may use subtle press scale when they exist, but that is not the TV remote default and must not leak into D-pad focus behavior.
- If an enter/exit animation is justified, prefer a strong responsive ease-out for entry/exit and ease-in-out for on-screen relocation/morphing. Avoid ease-in UI starts.
- Keep ordinary UI motion under roughly 300 ms; repeated navigation should normally be 0 ms.
- Animate only cheap properties when motion is used; avoid layout-affecting width/height/padding animation on performance-sensitive TV screens.
- Reduced-motion mode removes positional/scale motion and keeps only useful opacity/tone/state feedback.

## 3. Open Design usage

Open Design is used as an exploration aid, not as a runtime architecture.

Good uses:

- compare 2–4 layout compositions before implementation;
- explore hierarchy, density, negative space and surface grouping;
- test alternate Guide/Channels information layouts;
- derive visual treatment for empty/error/detail panels;
- validate whether a screen feels coherent before polishing micro-interactions.

Do not import from visual exploration without checking:

- D-pad graph and Back behavior;
- 720p/1080p safe areas;
- focus contrast;
- long Russian/German labels;
- weak-device render cost;
- accessibility/reduced-motion;
- bounded data/state ownership.

## 4. Dense TV navigation rules

Applies to Guide, Channels, Search results, settings lists and top-level navigation.

- focus geometry is stable;
- no focus scale animation;
- no focus-driven remeasurement;
- no stagger animation during ordinary list/grid entry if it delays reachability;
- focus follows stable identity, not list index;
- remote key handling remains native unless a component explicitly owns the full gesture contract;
- no preview-key click synthesis for ordinary buttons/cells;
- horizontal/vertical movement updates the visible state immediately;
- programme/channel removal must leave a deterministic focus target.

## 5. Sparse/rare UI motion

Motion can be used for occasional overlays, modal context, onboarding or one-time explanatory state when it improves comprehension.

Before adding it, answer:

1. How often will the user see it?
2. What does it explain or confirm?
3. Can it be interrupted/reversed safely?
4. Does it remain responsive on a weak TV device?
5. What is the reduced-motion behavior?

If the answer to #2 is only “it looks nicer,” do not animate a frequent path.

## 6. Visual hierarchy

Premium TV UI should come from disciplined hierarchy rather than decorative motion.

- use one dominant screen title, not multiple competing headings;
- prefer layered dark surfaces with restrained contrast steps;
- unfocused borders should be subtle or translucent; the focus ring is the strong edge;
- reserve strong accent for current focus, active/live semantics and primary action;
- avoid making every card equally elevated;
- use spacing to group related controls before adding separators;
- keep metadata quieter than channel/programme identity;
- use badges/icons only when they encode state, not decoration;
- avoid glass blur as a baseline dependency;
- no artwork-dependent readability.

## 7. Guide-specific craft contract

- fixed channel rail remains visually quieter than the focused programme cell;
- programme width represents time; focus styling must not distort that geometry;
- current-time line is informative, not decorative;
- time ruler and programme rows share one deterministic horizontal coordinate;
- `NO_GUIDE` and `SOURCE_CONFLICT` are explicit states, visually distinct but not louder than real programmes unless focused;
- truncated/bounded-data recovery is presented as a calm actionable state, not an error wall;
- focused programme detail can become richer without replacing the grid context;
- rapid Left/Right/Up/Down should feel instantaneous even while data invalidates.

## 8. Review format

UI reviews use one concrete table:

| Before | After | Why |
|---|---|---|
| Animated 1.06 focus scale on every D-pad move | Instant outline/tone/luminance focus | Repeated keyboard navigation should not animate; preserves grid geometry and perceived speed |
| Equal-strength borders on every cell | Quiet unfocused edge, strong focus ring | Reduces visual noise and makes cursor state obvious |
| Decorative transition with no state purpose | No animation | Motion must explain space/state/feedback rather than advertise polish |
| Index-only focus restoration | Stable channel/programme identity + deterministic fallback | Dynamic IPTV data reorders/removes items |
| Touch-only recovery action | D-pad reachable recovery control | TV-first reachability is mandatory |

Any design review that cannot state a concrete “why” should not ship the change merely for visual novelty.

## 9. Acceptance checklist

- five-button D-pad completes the journey without touch;
- repeated key navigation does not wait for decorative animation;
- focused state is unmistakable at 720p/1080p without relying on scale alone;
- focused + selected/playing states remain distinct;
- no focus change alters neighbouring geometry;
- long labels remain reachable/readable;
- reduced-motion has no positional/scale motion;
- no essential meaning depends only on color;
- screenshots cover default/focused/selected/playing/error/empty states;
- real-device review checks jank during rapid focus movement and image/data refresh.
