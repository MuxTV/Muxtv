---
status: accepted
last_reviewed: 2026-08-06
owners: [design, ui, accessibility, performance]
primary_craft_reference: https://github.com/emilkowalski/skills
secondary_visual_reference: Open Design
---

# MuxTV Design System

## 1. Product character

MuxTV uses a cinematic but restrained TV-first visual language. The interface may feel premium, but its hierarchy is driven by live television, guide context and fast remote operation rather than by oversized promotional artwork.

Principles:

1. focus before decoration;
2. readable from normal viewing distance;
3. channel switching and guide remain faster than visual effects;
4. current programme/context is always understandable;
5. advanced diagnostics do not leak into simple mode;
6. visual state is deterministic across weak and powerful devices.

For visual craft and micro-interaction decisions after Android TV platform correctness, the primary reference is `emilkowalski/skills`. Open Design is a secondary source for composition exploration and prototypes. Project-specific adaptation lives in `.work/design/craft-principles.md`.

## 2. Token groups

```text
ColorTokens
TypographyTokens
SpacingTokens
SizeTokens
ShapeTokens
FocusTokens
MotionTokens
ElevationTokens
ScrimTokens
ImageTokens
GridTokens
```

Feature modules use semantic tokens, not raw colors/dimensions.

## 3. Color

Semantic roles:

```text
background / backgroundRaised
surface / surfaceRaised / surfaceOverlay
textPrimary / textSecondary / textMuted
accent / onAccent
focusRing / focusGlow
selectedSurface
success / warning / error / info
liveIndicator / recordingIndicator
scrimStrong / scrimMedium / scrimSoft
```

Rules:

- focus and pressed states meet contrast requirements against every supported surface;
- focused text may not become lower contrast than default text;
- selected state is not represented only by accent color;
- background artwork always receives deterministic scrim based on measured luminance or preset;
- provider logos do not define application palette automatically;
- red is reserved for live/recording/error semantics and must remain distinguishable;
- unfocused structural borders remain quieter than the active focus ring; avoid equal-strength outlines on every cell.

## 4. Typography

Families use system/redistributable fonts only; font binaries are not committed unless license and need are explicitly approved.

Roles:

```text
DisplayLarge      hero/title, sparse use
HeadlineLarge     screen title
HeadlineMedium    rail/section
TitleLarge        programme/channel
TitleMedium       card primary
BodyLarge         programme description
BodyMedium        secondary metadata
LabelLarge        buttons/actions
LabelMedium       badges/status
NumericDisplay    channel number/time where needed
```

Constraints:

- minimum normal body size designed for 10-foot viewing, not mobile density;
- line height prevents clipping Cyrillic/diacritics;
- max lines and ellipsis explicit per component;
- marquee disabled by default and never required to understand item;
- large text preset increases typography and control dimensions together;
- long Russian/German/localized labels tested.

## 5. Layout and safe areas

Baseline canvases:

```text
1280×720
1920×1080
3840×2160
```

Compose uses dp/sp, but screenshot/reference layouts cover these resolutions and density variations.

- primary content remains inside TV-safe margins, baseline approximately 5% where platform/overscan requires;
- no essential control placed at extreme edges;
- width classes distinguish compact TV, standard TV and ultra-wide/4K presentation, not phone breakpoints;
- content density may increase on 4K but touch-sized mobile patterns are not introduced;
- focus treatment stays inside clipping/safe bounds and does not move neighboring layout.

## 6. Spacing and sizing

Use a small semantic scale, for example:

```text
space2, 4, 8, 12, 16, 24, 32, 48, 64
```

Actual token values are calibrated on TV screenshots and viewing distance.

Component constraints:

- focusable targets have minimum visible/selectable area;
- card gaps leave room for focus ring/static depth;
- sidebar width supports longest common localization;
- player controls remain reachable without covering critical subtitles/content;
- EPG row height balances programme readability and channel count;
- dialogs avoid mobile-width narrow columns.

## 7. Shape and depth

- moderate rounded corners, consistent by component class;
- dense D-pad surfaces communicate focus through immediate outline/tone/luminance, not animated zoom;
- shadow/glow effects have low-end fallback and remain visually subordinate to the focus ring;
- no permanent glass blur dependency;
- overlays use layered scrims rather than expensive real-time blur where possible;
- focused item must not trigger remeasurement or neighbor movement;
- sparse hero/poster scale treatment, if ever used, is explicit opt-in rather than a global focus default.

## 8. Motion

Motion is frequency-aware and purpose-driven. The primary craft rule is that high-frequency keyboard/D-pad actions should not animate.

Motion groups:

```text
route transition
overlay reveal/hide
rare state explanation
player status transition
loading/progress
```

`focus transition` is intentionally **not** a default animated group for dense TV navigation.

Budgets and rules:

- D-pad focus feedback is immediate: no scale/position animation for navigation, Channels, Guide, Search or settings lists;
- repeated D-pad input must never queue animation or make the cursor feel delayed;
- route/overlay transition exists only when it explains spatial/state context and never delays input readiness;
- entry/exit motion, when justified, should feel responsive from the first frame; avoid slow-start `ease-in` UI motion;
- moving/morphing content may use an interruptible ease-in-out/spring only when the interaction actually benefits from continuous motion;
- ordinary UI motion stays below roughly 300 ms;
- reduced-motion profile removes scale/parallax/position motion and uses opacity/outline/tone;
- no autoplaying hero preview by default on weak device profile;
- background video disabled by setting and device capability;
- animate cheap render properties only; do not animate width/height/padding in performance-sensitive TV lists/grids.

## 9. Images

- channel logos and programme artwork have separate pipelines/policies;
- placeholders preserve layout size;
- invalid dimensions/aspect ratio are sanitized before calculation;
- image decode size matches rendered bounds;
- logo padding/background policies prevent unreadable transparent/white logos;
- animated images disabled unless explicitly supported;
- remote images have size/content-type limits;
- dominant color extraction occurs off main thread and is cached;
- background image transition does not replace current item before next bitmap ready.

## 10. Core components

```text
MuxTvFocusSurface
MuxTvActionButton
MuxTvNavigationItem
MuxTvChannelCard
MuxTvProgrammeCard
MuxTvPosterCard
MuxTvHero
MuxTvRail
MuxTvSidebar
MuxTvBadge
MuxTvStatusChip
MuxTvProgress
MuxTvDialog
MuxTvSettingsRow
MuxTvSourceRow
MuxTvProfileCard
MuxTvEpgChannelCell
MuxTvEpgProgrammeCell
MuxTvPlayerOverlay
MuxTvErrorPanel
MuxTvEmptyState
MuxTvSkeleton
```

Each component documents:

- purpose and non-goals;
- slots/content model;
- semantic roles;
- default/focused/pressed/selected/disabled/loading/error states;
- focus behavior;
- size variants;
- localization constraints;
- screenshots and tests;
- performance notes.

Shared dense-navigation components additionally document whether any motion is present. The default answer for D-pad focus is none.

## 11. Home screen

Home is personalized by current profile but does not imitate a VOD storefront.

Possible sections:

- Continue/last channel;
- Favorites;
- On now;
- Recently watched;
- Categories/user groups;
- setup/diagnostic action only when needed.

Hero is optional and contextual. It may show current/recommended live programme, but cannot consume most of the first viewport or autoplay video universally.

## 12. Live browser

Recommended large-screen structure:

```text
categories/groups | channel list | preview + now/next
```

Alternative compact mode can reduce columns. Requirements:

- current playing, selected and focused channels visually distinct;
- channel number/logo/name/current programme/progress readable;
- preview does not start decoder for every rapid focus movement; debounce and setting required;
- favorites/custom groups first-class;
- no dead space requiring excessive D-pad travel;
- rapid focus traversal has no decorative focus-animation queue.

## 13. EPG

Custom high-performance timeline component, not nested unconstrained lazy lists.

- fixed channel column;
- fixed/synchronized time ruler;
- current-time line;
- programme duration maps to width with minimum focusable width;
- short programmes remain accessible via detail/stack strategy;
- lazy interval loading;
- focus retains time anchor vertically;
- past/catch-up/recordable/live states encoded with multiple cues;
- selected programme can reveal detail pane without losing grid context;
- focus styling never changes programme-width = time geometry;
- Left/Right/Up/Down focus changes are immediate and non-animated.

## 14. Player UI

- video dominates;
- channel/programme/now-next appears without obscuring excessive area;
- controls vary by live/seekable/catch-up capabilities;
- buffering/recovery subtle but understandable;
- final error provides safe actions;
- quick channel rail optimized for remote speed;
- audio/subtitle menus show actual language/codec and selected state;
- stats/diagnostics remain expert-only.

## 15. Settings and diagnostics

- categories with plain-language labels;
- expert explanations and reset-per-setting;
- no raw configuration dump as primary UX;
- dangerous actions separated and confirmed;
- restore/import preview has visible primary action in initial viewport/focus graph;
- TV Doctor summary uses readable categories and progressive disclosure.

## 16. Themes

Initial release supports one refined dark TV theme plus accessibility variants. Multiple cosmetic themes are deferred until core component coverage is complete.

Supported adaptations:

- system/default dark;
- high contrast;
- reduced motion;
- larger UI/text;
- optional accent selection later.

Avoid theme engine/plugin complexity in MVP.

## 17. Quality and performance

- every component has screenshot matrix;
- macrobenchmarks cover Home rails, channel browser and EPG scrolling;
- jank measured under image loading and rapid key repeat;
- allocations tracked for focus movement;
- gradients/scrims measured on weak reference device;
- no unbounded recomposition from player progress/clock;
- programme progress updates at appropriate cadence, not per frame;
- design token changes require broad screenshot review;
- real-device review explicitly checks whether rapid remote navigation feels instant rather than merely measuring nominal animation duration.

## 18. Critical lessons from reference apps and craft sources

- official Compose TV samples define current component/focus mechanics but are demos, not complete product architecture;
- `emilkowalski/skills` is the primary craft reference for polish: invisible details compound; frequent keyboard actions should not animate; motion requires a concrete purpose; UI review compares specific before/after choices;
- Open Design is secondary for layout/composition exploration and prototype comparison, not runtime focus/key semantics;
- Jellyfin demonstrates mature device/release support, while long-lived issues expose focus restoration, slow image loading and player/UI lifecycle regressions;
- StreamVault demonstrates visually rich TV-first capabilities, but focused-button contrast and unreachable restore action show why screenshots alone are insufficient;
- M3UAndroid's simpler positioning is a reminder that polished simplicity can be stronger than exposing every technical capability.

## 19. Acceptance criteria

- core flow passes 720p/1080p/4K screenshot reviews;
- focused text/control remains clearly readable;
- high contrast and reduced motion are complete variants, not partial overlays;
- no component changes layout size when focused;
- dense D-pad focus movement has no scale/position animation delay;
- rapid D-pad navigation remains responsive under image load;
- EPG and live browser meet performance budgets;
- no essential information depends only on color/artwork;
- UI remains coherent with missing logos, programme data and network images;
- every non-trivial animation has a documented purpose and reduced-motion behavior.
