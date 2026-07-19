---
status: accepted
last_reviewed: 2026-07-19
owners: [design, ui, accessibility, performance]
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
- red is reserved for live/recording/error semantics and must remain distinguishable.

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
- focus scaling must stay inside clipping bounds.

## 6. Spacing and sizing

Use a small semantic scale, for example:

```text
space2, 4, 8, 12, 16, 24, 32, 48, 64
```

Actual token values are calibrated on TV screenshots and viewing distance.

Component constraints:

- focusable targets have minimum visible/selectable area;
- card gaps leave room for focus scale/glow;
- sidebar width supports longest common localization;
- player controls remain reachable without covering critical subtitles/content;
- EPG row height balances programme readability and channel count;
- dialogs avoid mobile-width narrow columns.

## 7. Shape and depth

- moderate rounded corners, consistent by component class;
- focus elevation/scale communicates cursor without excessive zoom;
- shadow/glow effects have low-end fallback;
- no permanent glass blur dependency;
- overlays use layered scrims rather than expensive real-time blur where possible;
- focused item must not trigger remeasurement or neighbor movement.

## 8. Motion

Motion groups:

```text
focus transition
route transition
overlay reveal/hide
rail item insertion/removal
player status transition
loading/progress
```

Budgets:

- focus feedback begins immediately and completes quickly;
- route transition never delays input readiness;
- player overlay appears faster than decorative home transitions;
- reduced-motion profile removes scale/parallax and uses opacity/outline;
- repeated D-pad input coalesces/interrupts animations;
- no autoplaying hero preview by default on weak device profile;
- background video disabled by setting and device capability.

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
- no dead space requiring excessive D-pad travel.

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
- selected programme can reveal detail pane without losing grid context.

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
- design token changes require broad screenshot review.

## 18. Critical lessons from reference apps

- official Compose TV samples define current component/focus mechanics but are demos, not complete product architecture;
- Jellyfin demonstrates mature device/release support, while long-lived issues expose focus restoration, slow image loading and player/UI lifecycle regressions;
- StreamVault demonstrates visually rich TV-first capabilities, but focused-button contrast and unreachable restore action show why screenshots alone are insufficient;
- M3UAndroid's simpler positioning is a reminder that polished simplicity can be stronger than exposing every technical capability.

## 19. Acceptance criteria

- core flow passes 720p/1080p/4K screenshot reviews;
- focused text/control remains clearly readable;
- high contrast and reduced motion are complete variants, not partial overlays;
- no component changes layout size when focused;
- rapid D-pad navigation remains responsive under image load;
- EPG and live browser meet performance budgets;
- no essential information depends only on color/artwork;
- UI remains coherent with missing logos, programme data and network images.