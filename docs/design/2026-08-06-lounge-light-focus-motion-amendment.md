# MuxTV Lounge Light — Focus and Motion Amendment

Status: normative amendment to `2026-08-04-muxtv-lounge-light-spec.md`
Date: 2026-08-06
Parent tracks: #93, #111, #29, #33

This amendment supersedes the focus/motion defaults in sections 4.4, 10, 11 and any component-specific scale guidance in the 2026-08-04 Lounge Light specification where those rules conflict with the contract below. The older document remains authoritative for palette, typography, hierarchy, surface language, product mapping, privacy and implementation sequencing.

## Source priority

1. Android TV/Compose TV platform semantics remain mandatory for focus ownership, key routing, accessibility and device behavior.
2. Primary UI craft reference: `https://github.com/emilkowalski/skills`, especially `emil-design-eng` and animation-review guidance.
3. Open Design is secondary for composition, visual alternatives and prototype exploration.
4. MuxTV product constraints override literal imitation of any reference image or web interaction pattern.

The relevant craft rule is frequency-first: keyboard/high-frequency interactions should not carry decorative animation. D-pad movement is a keyboard-class interaction for MuxTV and must feel immediate rather than visually interpolated.

## Dense TV focus is immediate

The default for repeated D-pad focus movement is:

- scale: `1.0`;
- position/layout motion: none;
- focus duration for geometric movement: `0 ms`;
- no queued keyframe/spring/tween response under repeated directional input;
- component bounds remain stable;
- focused state is visible immediately through at least two non-geometric cues.

Recommended cues:

1. 3 dp bronze/accent outline or leading edge;
2. neutral surface-tone/luminance/elevation change.

A third persistent semantic cue is required where focus overlaps another state such as selected, playing, favourite or disabled.

This rule applies by default to:

- Channels rows;
- Guide channel/programme cells;
- navigation items and rail destinations;
- Settings rows;
- Sources rows/cards in ordinary vertical navigation;
- search results;
- dialogs/lists with repeated directional navigation;
- compact buttons used repeatedly inside dense operational flows.

## Sparse visual scale is explicit opt-in, not a token default

Scale may be considered only for a genuinely sparse surface such as a Home hero or low-density showcase card when all of these are true:

- the interaction is not repeated rapidly in normal use;
- the component reserves a focus envelope so neighbors never move or clip;
- scale communicates emphasis/feedback rather than merely looking animated;
- API26/current-low-RAM rendering remains smooth;
- reduced-motion mode removes the transform;
- screenshot/device review confirms the effect improves rather than weakens 10-foot legibility.

No numeric scale value is globally prescribed. A future component must justify and test its own value. The old `1.025–1.04` card, `<=1.025` navigation and `1.02–1.035` button suggestions are therefore non-normative and must not be copied into production as defaults.

## Motion decision framework for MuxTV

Before adding motion, answer in order:

### 1. How frequently is the action triggered?

- repeated D-pad/keyboard action: no decorative animation;
- frequent list/filter state change: remove motion or keep only a near-instant non-geometric state change;
- occasional drawer/modal/overlay: bounded motion may be appropriate;
- rare onboarding/explanatory transition: more expressive motion may be appropriate if it remains interruptible and accessible.

### 2. What does the motion explain?

Accepted purposes for MuxTV include:

- spatial continuity when a rail/drawer/overlay enters or leaves;
- explaining ownership/context when a details surface opens from a source item;
- preventing a genuinely jarring rare content replacement;
- press feedback on a deliberately activated sparse control where native TV semantics do not already provide sufficient feedback.

“Premium feel” or “looks smoother” alone is not enough for a high-frequency interaction.

### 3. Can the user interrupt/reverse it?

Rapidly reversible surfaces must retarget cleanly rather than queue old transitions. Motion must never delay a new focus target or activation intent.

## Allowed transition classes

The following remain candidates, not guaranteed animation requirements:

- navigation rail expand/collapse;
- details/modal enter/exit;
- Player control overlay show/hide;
- rare Home hero content replacement;
- onboarding step transition where spatial explanation helps.

For UI transitions, keep ordinary durations below 300 ms unless there is measured justification. Enter/exit timing may be asymmetric where that improves perceived responsiveness. The exact Android easing/animation primitive should be chosen at implementation time rather than copying CSS/web curves literally.

## Reduced motion

Reduced-motion mode:

- removes scale, translation, parallax and decorative motion;
- preserves immediate outline/tone/state feedback;
- may retain a very short opacity/tone transition only when it does not delay usability;
- must not change focus order, reachability or semantic state.

The normal dense D-pad path is already geometry-motion-free, so reduced motion should not require a second focus architecture.

## Focus vs selected vs playing

Do not encode every state as bronze/gold.

- focused: outline/edge + neutral raised/tone cue;
- selected: persistent tonal fill + marker/check/state icon;
- playing: persistent play glyph/accent edge or another non-focus marker;
- disabled: reduced emphasis plus disabled semantics, but still legible;
- playing + focused / selected + focused: both semantic and focus cues remain visible simultaneously.

No state distinction may rely on color alone.

## Guide-specific rule

Programme-cell width represents time and is therefore functional geometry. Guide programme/channel cells must never scale, translate or resize on focus. Focus changes only paint/outline/tone/elevation within stable bounds.

Horizontal timeline movement, if introduced later, is a navigation/data-window operation rather than decorative focus motion and requires its own bounded/interruptible behavior and device evidence.

## Channels-specific rule

Channel-row focus must preserve row height, neighboring positions, list scroll anchor and Player→Back restoration. The shared dense focus primitive may alter paint/tone only; it must not create a transform that changes perceived list geometry under rapid zapping/navigation.

## Navigation rail-specific rule

Directional movement between rail items is immediate. If the rail itself expands/collapses, that container transition is separate from focus movement:

- entering a different rail item must not restart/queue the rail animation;
- selected destination remains distinct from focused destination;
- `Right` back to content must not wait for decorative collapse before focus can become usable;
- Back/collapse behavior must remain deterministic.

## Verification before production visual claims

No visual/motion rule is accepted merely because the static code matches this document. Relevant implementation PRs must record, where applicable:

- API26 and current API D-pad journey behavior;
- rapid repeated directional input;
- short press / long press ownership;
- 720p and 1080p screenshots with long Russian strings;
- focused + selected + playing state combinations;
- reduced-motion path;
- low-RAM/device performance for any nontrivial transition;
- preservation of existing focus anchors and Player/Back restoration.

## Current implementation relationship

The offline #111 branch `work/tv-design-craft-111` authors a dense shared-focus change consistent with this amendment: scale `1.0`, geometric focus duration `0 ms`, immediate outline/tone, no preview-key click synthesis. That branch is not accepted until its exact-head unit/compile/device evidence exists.

Guide #29 already follows the same geometry-stable focus direction and must remain stacked behind accepted Guide data rather than treating this amendment as permission to merge unverified UI.
