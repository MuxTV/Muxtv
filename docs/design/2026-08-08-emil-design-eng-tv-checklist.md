# Emil Design Engineering — Muxtv Android TV checklist

**Source:** [`emilkowalski/skills`, `skills/emil-design-eng/SKILL.md`](https://github.com/emilkowalski/skills/blob/de33dbed000212b54400a33767d1e4d03654db2a/skills/emil-design-eng/SKILL.md)

**Pinned revision:** `de33dbed000212b54400a33767d1e4d03654db2a`

**Upstream license:** MIT

**Adaptation date:** 2026-08-08

This checklist adapts the pinned upstream design-engineering review method to remote-first Android TV. It is a review aid, not permission to copy a mobile interaction model or to override Muxtv product, accessibility, privacy, architecture or performance contracts. Updating the source revision requires a separate PR.

## Required review order

1. Inspect the real implemented screen and state; do not review a placeholder as a finished product.
2. Review structure, hierarchy and state clarity before visual polish.
3. Verify D-pad, OK, Back, key repeat, focus restoration and screen recreation before animation.
4. Verify 1280×720 and 1920×1080 reachability, long labels, large text, safe margins and viewing-distance contrast.
5. Measure frame timing and allocations for any new motion/effect on the supported physical device class.
6. Record every accepted change using the table below.

## Android TV interaction rules

- Dense directional focus is immediate: no scale, translation, fade delay or queued animation.
- Focus, selected, playing, favourite and disabled states remain independently understandable and composable.
- Focus/selection/status never depends on color alone; use at least one additional cue such as outline, tone, icon or label.
- OK produces exactly one activation. Held/repeated keys do not multiply clicks. Direction and Back remain platform-owned unless a route has one explicit local unwind step.
- Back closes the nearest transient layer first, then returns to the restored origin/focused item.
- Player controls do not recreate the player/controller. The hidden overlay owns no focusable descendants.
- Rare overlays and dialogs may enter in 140–220 ms ease-out and exit in 100–160 ms.
- Motion may use alpha, small scale or translation only when it explains state or spatial relationship. It must be interruptible.
- Reduced motion removes decorative scale/translation/overshoot without weakening state feedback.
- No blur, animated layout size, heavy shader, bounce or decorative delay on the TV path.

## Layout and content rules

- Home exposes real Recent/Continue, favourites now-on, source health and Guide entry; no fake content.
- Primary navigation remains compact and stable across Home, Guide, Channels, Favorites, Search, Sources, Doctor and Settings.
- Empty states appear only for actual absence of data and always offer the next meaningful action.
- Lists use stable identity and preserve scroll/focus after return, refresh and item removal.
- Long content remains scrollable and every action is reachable at 720p without pointer/touch.
- Typography is system/platform-first; hierarchy comes from size, weight, leading and spacing.
- Text, target sizes and contrast are judged at TV viewing distance, not only on a desktop preview.

## Performance rejection criteria

Reject or revise a visual change when it introduces app-owned allocation stacks on each static frame, animated remeasurement in a frequent path, focus-response latency over the MVP ceiling, frozen frames, retained controller/player instances, or an effect that cannot be disabled under reduced motion.

## Mandatory UI review record

| Before | After | Why |
| --- | --- | --- |
| Describe the observed implementation and evidence. | Describe the bounded change and resulting state. | Tie the change to hierarchy, interaction, accessibility, performance or product meaning. |

The PR must also name the tested viewport/API/device, D-pad journey, reduced-motion state, relevant semantic assertions, frame/allocation evidence when applicable, and any intentionally deferred state.
