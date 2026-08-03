# MuxTV Lounge — Light TV Design System

Status: proposed design specification
Date: 2026-08-04
Parent product tracks: #33, #29

This document is the durable repository copy of the detailed Lounge design direction. The GitHub issue created from the same specification should remain the execution tracker; this file is the design reference that implementation PRs can cite.

## 1. Objective

Define a premium, calm, living-room-first visual system for MuxTV inspired by the approved MuxTV Lounge concept, but adapted to the actual MuxTV product and its Android TV constraints.

The target is a single refined **light** TV theme. It must not use pure white as the dominant canvas. The visual tone is a soft matte warm/cool neutral gray that avoids glare in a dark living room while retaining a premium light-product character.

“Lounge” is an internal design-system codename, not a product rename. The application remains **MuxTV**.

This design must preserve current architecture, Navigation 3, existing focus ownership, Media3 process ownership, bounded data access, redaction/privacy rules, and all accepted product constraints from #33/#29.

## 2. Visual reference: what to preserve from the approved Lounge image

The reference image is useful because of its composition rather than its literal dark palette or VOD content.

Preserve:

1. A stable left navigation rail with brand at the top and primary destinations vertically grouped.
2. A large, calm hero area that establishes hierarchy immediately and makes the screen feel like a premium living-room product.
3. Large rounded cards with restrained depth, not dense mobile-style surfaces.
4. One warm premium accent used for focus, primary actions and small brand details.
5. Strong separation between navigation, hero and secondary content rails.
6. A compact top-right utility area, primarily the clock for MuxTV.
7. Content-forward composition: chrome stays quiet until focused.
8. Comfortable spacing and deliberate negative space.
9. A clear primary action inside hero/detail surfaces.
10. Consistent component geometry across Home, discovery and settings.

Do **not** copy from the reference image:

- dark/navy background;
- Movies / Series / VOD catalogue taxonomy;
- cinematic posters or programme artwork as a dependency;
- “Featured for You” recommendations;
- notification center;
- user profile/account UI;
- 4K/Dolby badges unless MuxTV has real, typed capability data;
- permanent preview panes in Channels;
- fake programme images or dynamic dominant-color backgrounds.

MuxTV must degrade cleanly when a playlist has only channel names and no logos/EPG.

## 3. Product mapping

Reference-image concept -> actual MuxTV surface:

- Lounge/Home -> `Главная`
- Live TV -> `Эфир`
- Movies/Series -> **not present**
- Favorites -> `Избранное` filter/section from #29
- Browse -> `Программа` + `Поиск`
- Profile/preferences -> `Настройки`
- technical source management -> `Настройки > Источники`
- title detail -> programme/channel context where real data exists; no invented VOD details

Top-level destinations after #29 real surfaces exist:

1. Главная
2. Эфир
3. Программа
4. Поиск
5. Настройки

`Источники` moves under Settings as already required by #33.

## 4. Design principles

### 4.1 Calm light, not bright white

The application must look light in screenshots but remain comfortable on a TV in a dark room. Avoid large #FFFFFF planes. Use a slightly gray canvas and slightly lighter but still non-white surfaces.

### 4.2 Living-room premium, not streaming clone

The reference image feels premium because of hierarchy, spacing, surface quality and a warm accent—not because of movie posters. MuxTV should reproduce that feeling using real channel/EPG data.

### 4.3 TV first

Every screen is designed for D-pad navigation and approximately 3 m / 10 ft viewing distance. Focus must be immediately visible. No interaction may depend on touch, hover, drag or pointer precision.

### 4.4 Geometry must remain stable

Focused list rows and Guide cells must not resize or displace siblings. Scale is allowed only where a reserved focus envelope exists (cards/rail buttons). Dense list/grid components use border/tone/elevation rather than scale.

### 4.5 Real data only

Do not render a progress bar, artwork, quality capability, favourite/recent status, programme detail or source health state unless the corresponding product boundary provides it.

### 4.6 One design system

Do not create a separate Lounge UI framework. Extend `core:designsystem` and `core:ui`; add components only when a production consumer exists.

### 4.7 Performance is part of visual design

No mandatory runtime blur, shader-heavy glass, parallax, dynamic dominant-colour extraction or unbounded image effects. The same visual language must be viable on low-RAM Android TV / Fire TV class devices.

## 5. Colour system — Lounge Light

The palette should be treated as semantic tokens, not hard-coded per screen.

### Core neutrals

| Token | Proposed value | Role |
| --- | --- | --- |
| `canvas` | `#F1F2F4` | primary app background; deliberately not white |
| `canvasMuted` | `#E9EBEE` | rail/background separation |
| `surface` | `#F7F7F5` | cards and primary surfaces |
| `surfaceRaised` | `#FAFAF8` | focused/raised cards; still not pure white |
| `surfaceInset` | `#ECEEF1` | fields, secondary containers, inactive chips |
| `surfacePressed` | `#E2E4E8` | pressed/active neutral state |
| `divider` | `#D6D9DE` | separators and subtle borders |
| `dividerStrong` | `#C3C7CD` | dense structural lines |

### Text

| Token | Proposed value | Role |
| --- | --- | --- |
| `textPrimary` | `#181A1F` | main text |
| `textSecondary` | `#5D626A` | metadata |
| `textTertiary` | `#858B94` | low-priority labels |
| `textDisabled` | `#A8ADB5` | disabled controls |

### Lounge accent

Keep the warm premium character of the reference, but make it darker and more restrained for a light background.

| Token | Proposed value | Role |
| --- | --- | --- |
| `accent` | `#9B6A32` | primary brand/accent |
| `accentStrong` | `#7F5428` | focus border / text where high contrast is required |
| `accentSoft` | `#EADDCB` | selected/focused tonal fill |
| `accentSoft2` | `#F1E8DC` | hero/chip background accent |
| `onAccent` | `#FFF9F1` | text/icon on solid accent action |

Gold/bronze must not be used as ordinary body text on the gray canvas. It is reserved for focus, primary action, playing marker, current-time marker and small brand details.

### Status colours

Status colours remain semantic and subordinate to the Lounge accent:

- success: muted deep green;
- warning: amber/brown distinct from brand accent;
- error: muted dark red;
- info: restrained slate/blue.

Do not let status colours become secondary brand colours.

## 6. Surface/elevation model

The reference image uses deep dark layers. In the light version, separation comes from **tone + border + restrained shadow**, not from large contrast jumps.

Levels:

- L0 Canvas: `canvas`.
- L1 Rail / grouped region: `canvasMuted` or `surfaceInset`.
- L2 Card: `surface` + 1 dp tonal/border separation.
- L3 Focused/raised card: `surfaceRaised` + focus outline + subtle shadow.
- L4 Modal/details surface: `surfaceRaised` + stronger edge separation.

No mandatory blur. A “frosted” appearance should be achieved with opacity/tone first.

Suggested shadow philosophy:

- default card: almost none;
- focused card: soft short shadow only;
- overlay/modal: slightly stronger but still diffuse;
- Guide cells/list rows: no shadow.

## 7. Shape system

The approved concept has friendly rounded geometry. Preserve it, but reduce mobile-like pill excess.

Suggested tokens:

- hero corner: 28 dp;
- large card: 22 dp;
- standard card: 18 dp (compatible with current token);
- list row: 16–18 dp;
- button: 14 dp (compatible with current token);
- filter chip: 999 dp only for true compact filters/status chips;
- dialog/details: 24 dp;
- logo tile: 14–16 dp.

Do not round every rectangle into a pill.

## 8. Spacing/grid

Existing spacing tokens (8/12/20/32/48 dp) are a good base. Extend rather than replace.

Add only if real layouts consume them:

- 4 dp micro gap;
- 16 dp compact gap;
- 24 dp component gap;
- 40 dp section gap;
- 56 dp screen inset;
- 64 dp large safe/header inset.

### 1080p reference geometry

Treat 1920x1080 as the primary composition reference while remaining density-independent.

- safe outer region: target >= 5% on legacy/overscan-sensitive displays; repository UI tests may use the existing 56 dp content inset where it satisfies this safely;
- collapsed navigation rail: ~88 dp;
- expanded rail: ~248–272 dp;
- rail/content gutter: 28–36 dp;
- top utility line: ~64–72 dp;
- main content max width: remaining safe width; no center desktop-style 1200px cap;
- vertical section gap: 32–40 dp;
- card internal padding: 20–28 dp.

Expanded navigation should overlay or transiently occupy additional width rather than permanently shrinking the normal content canvas.

## 9. Typography

Do not add a network font. Prefer the Android/system typeface first; brand font work can be a separate evidence-driven decision.

1080p reference scale (subject to screenshot/device validation):

- hero title: 44–52 sp, medium/semibold;
- screen title: 36–44 sp;
- section title: 26–30 sp;
- card/channel title: 20–24 sp;
- body: 18–20 sp;
- metadata: 15–17 sp;
- compact Guide metadata: 14–16 sp minimum where needed.

Rules:

- no light/thin weights for critical TV text;
- Russian strings are first-class test data;
- channel/programme names use ellipsis only after preserving essential geometry;
- time values use tabular figures where available/appropriate;
- avoid uppercase paragraphs; uppercase may be used for tiny brand/section micro-labels only.

## 10. Focus system

Focus is the most important visual state on TV.

### State model

`default`, `focused`, `pressed`, `selected`, `playing`, `disabled`, plus valid combinations such as `playing + focused`.

### Global focus treatment

Focused interactive element should use at least two signals:

1. a strong warm bronze outline/edge;
2. a surface/elevation/tone change.

Scale is optional, not universal.

### Proposed focus values

- outline: 3 dp base; consider 4 dp only if 720p/3 m testing proves 3 dp insufficient;
- card scale: 1.025–1.04 with reserved envelope;
- navigation item scale: <= 1.025;
- channel list row scale: **1.0**;
- Guide cell scale: **1.0**;
- button scale: 1.02–1.035 where no neighbour displacement occurs;
- focus transition: keep current ~140 ms baseline;
- screen transition: keep current ~240 ms baseline;
- reduced-motion mode: remove scale and use instantaneous/short colour+outline transition.

The current global `1.06` token is too coarse to apply to every Lounge surface. Keep backward compatibility while introducing component-specific focus treatment rather than globally increasing/decreasing all components.

### Distinguish focus/selection/playback

Never encode all three as “gold”.

- focused: bronze outline + raised neutral surface;
- selected filter/state: persistent `accentSoft` fill + selected icon/check marker;
- currently playing: compact persistent play indicator / accent edge;
- playing + focused: retain both play marker and focus outline.

## 11. Motion

Lounge should feel smooth, not animated for its own sake.

- focus: 120–160 ms;
- rail expand/collapse: 180–220 ms;
- screen content transition: 220–260 ms;
- hero content replacement: ~220–280 ms crossfade/short slide if necessary;
- overlay show/hide: ~180–240 ms;
- no parallax requirement;
- no endless ambient animation;
- no animation that delays first usable focus.

Reduced motion removes scale/parallax and shortens transitions while preserving clear state changes.

## 12. Application shell / navigation rail

This is the strongest structural carry-over from the reference image.

### Collapsed state

Approx. 88 dp wide, always visible on non-Player / non-Add-Source top-level screens.

Contains:

- MuxTV mark/logo at top;
- icon-only destinations;
- active destination has a persistent small marker/tinted surface distinct from focus;
- settings at bottom or in the same consistent primary stack depending final navigation ergonomics.

### Expanded state

When focus enters the rail, expand to ~248–272 dp and reveal Russian labels:

- Главная
- Эфир
- Программа
- Поиск
- Настройки

Do not add Notifications/Profile because the product has no such surface.

### Focus behaviour

- `Left` from first focusable content at a valid boundary enters rail.
- `Right` from rail returns to remembered focus in current destination where existing focus ownership permits.
- expanded rail must not destroy the destination ViewModel/back-stack state.
- `Back` while rail is expanded collapses it before navigating away where that matches current route semantics.
- selected destination and focused item remain visually distinguishable.

### Timing/order constraint

Do not replace the current horizontal top navigation with the full final rail until Guide/Search are real, operable destinations. This preserves #33's “no dead destinations” intent.

## 13. Top utility area

Retain the calm top-right clock from the reference.

Initial scope:

- clock only;
- optional small app/system state only if backed by a real typed product contract later.

Do not add fake notification/user/network icons.

Clock is secondary chrome and must not compete with the screen title.

## 14. Home — Lounge composition

Home should be the most spacious screen and carry the strongest Lounge personality.

### 14.1 Hero

The reference image uses a cinematic living-room hero. MuxTV must achieve the same hierarchy without requiring programme artwork.

Hero is a large rounded surface occupying roughly the upper 42–48% of content height on 1080p.

Allowed hero data, in priority order as capabilities land:

1. current active playback session + real current programme;
2. most recent successfully played channel from #29 + real Now/Next;
3. deterministic generic `Эфир` entry state if no recent/current playback exists.

Hero may contain:

- channel logo if available;
- channel name/number;
- current programme title and time if READY;
- compact progress only when real start/end data supports it;
- primary action `Смотреть` / `Продолжить`;
- secondary `Программа` when real Guide route exists.

Hero background:

- neutral gray gradient / static MuxTV brand artwork;
- optional logo treatment;
- no programme-artwork requirement;
- no dominant-colour extraction;
- no credentialed image loads.

### 14.2 Home secondary sections

Replace “Featured for You” from the reference with real #29 data:

- `Избранное`;
- `Недавние`;
- optional bounded `Сейчас в эфире` only if there is a deterministic, product-approved query.

No recommendations/ML rail.

### 14.3 Card format

Home channel cards should be landscape, not movie posters.

Suggested card:

- 260–320 dp wide;
- 130–160 dp high;
- logo tile left/top;
- channel name;
- current programme;
- small current time/progress when real;
- no more than 2 lines of metadata.

Focus scale may be 1.03–1.04 because the carousel reserves enough envelope.

### 14.4 Empty states

No configured source:

- premium calm hero copy;
- one main action `Добавить источник`;
- no fake cards.

Configured but no favourites/recent:

- omit empty decorative rails or show one compact actionable empty state; do not fill the screen with placeholders.

## 15. Channels / Эфир

Channels is the daily high-speed surface; it should be less decorative than Home.

### Layout

- single main content column;
- screen title and compact filter row;
- filters `Все каналы` / `Избранное` when accepted Favorites is available;
- full-width channel rows;
- no permanent preview panel;
- no group sidebar until a real group API warrants it.

### Lounge channel row

Reference height target: 92–108 dp depending final typography.

Zones:

1. channel number: fixed narrow column;
2. logo/fallback tile: fixed geometry ~56–64 dp;
3. identity: channel name + group/variant metadata;
4. programme: current title + optional next title;
5. progress/time region when real;
6. persistent playing/favourite indicators.

Focus:

- no row scaling;
- bronze 3 dp outline or leading focus edge;
- raised `surfaceRaised`;
- subtle shadow allowed;
- focused text remains dark, not inverted to a giant gold block.

Playing:

- small play glyph / narrow accent edge;
- cannot disappear when row loses focus.

Favourite:

- compact star marker;
- favourite state does not change row size.

`OK` starts playback immediately.

### Long strings

Must remain stable with:

- long Russian channel name;
- missing number;
- missing logo;
- long group name;
- current/next programme missing independently.

## 16. Guide / Программа

Guide is the densest surface and intentionally shifts from “relaxed Lounge” to “precision Lounge”.

### Structure

- fixed/sticky time axis;
- fixed/sticky channel identity rail;
- horizontally bounded/lazy programme window;
- vertically lazy channel rows;
- current-time indicator using `accent`/`accentStrong`;
- focused programme detail strip/panel only if it does not become a permanent second preview column.

### Programme cells

- neutral surface tones;
- subtle divider lines;
- focus = outline/tone, never scale;
- selected/current programme states distinct from focus;
- extremely short programme cells may show abbreviated title while complete title appears in the focused detail region;
- NO_GUIDE / SOURCE_CONFLICT represented with calm typed UI, not generic exception text.

### Density

Guide should show enough rows/time to be useful from a distance; Lounge spacing must not produce giant cards here.

## 17. Search

Search should inherit Lounge shell but remain operationally simple.

### Entry

- large search field at top;
- system TV keyboard/IME is preferred unless a separate keyboard implementation is justified;
- recent queries are not stored unless a product/privacy contract explicitly adds them.

### Results

Group real results:

1. channels;
2. programmes.

Use the same channel row / compact programme-card primitives rather than a poster grid.

### States

- idle/instructions;
- typing/debounced loading;
- results;
- no results;
- error.

Never load full catalogue/guide into Compose memory.

## 18. Player

Player is visually different because video is the canvas, but controls use Lounge Light surfaces.

### Default

- full-screen video;
- controls hidden;
- no permanent app chrome.

### On OK / media key interaction

Reveal a bottom/low overlay using a translucent soft-gray surface (`surfaceRaised` around 90–96% opacity). A subtle video scrim may be used behind the panel for contrast; it is functional contrast treatment, not a dark-theme surface.

Overlay content:

- channel identity;
- current programme if real;
- playback state;
- progress/time only when semantically correct for the stream/programme;
- Play/Pause;
- compact `Ещё`;
- favourite action once #29 mutation is accepted;
- typed approval/failure recovery actions when required.

Do not add arbitrary seek/quality/subtitle/audio controls unless capabilities genuinely exist for the active playback contract.

### Focus

- warm focus ring;
- no tiny controls;
- hidden controls must not retain focus;
- `Back` hides overlay first, then route navigation;
- media keys remain consistent with Media3/session ownership.

Media3 Compose/player primitives should be reused where they fit; do not replace stable playback/focus behaviour merely to mimic the mockup.

## 19. Settings

The reference profile screen maps to MuxTV Settings, not to an account/profile system.

Suggested sections:

- Воспроизведение (only real user-facing settings)
- Интерфейс (only real settings; do not add a theme picker yet)
- Источники
- Диагностика
- О приложении

Use one-column Lounge rows/cards by default. A second detail column is allowed only when it materially improves a real settings flow and remains D-pad predictable.

## 20. Sources

Move under Settings in the final shell.

Source card:

- source display name;
- safe typed status;
- last refresh/revision summary when real;
- at most two direct actions: `Обновить сейчас`, `Настроить`;
- all secondary/security/network scheduling controls live in a bounded details surface.

Lounge visual:

- large calm card;
- subtle source/status iconography;
- no locator/URL in card, semantics or screenshot;
- no raw exception text;
- focus returns to originating card after details closes.

## 21. Add Source / onboarding

Keep the existing state machine. Only presentation changes.

Use a centered/wide Lounge setup panel with a two-step visual flow:

1. source entry;
2. confirmation/approval.

States such as editing, HTTP approval, confirmation, failure and completion retain deterministic focus.

Do not add QR/companion setup to this design package.

The source locator remains outside navigation, saved state, semantics and screenshot-visible diagnostics according to the existing security boundary.

## 22. Logo loading and imagery

Channel logos are useful to the Lounge visual direction but remain an isolated, credential-free feature as in #33 D5.

Rules:

- one application-scoped loader;
- credential-free image HTTP client;
- bounded decode size;
- stable tile geometry whether image succeeds or fails;
- bounded visible-window prefetch and cancellation;
- fallback is monogram/neutral tile;
- no dominant colour extraction;
- no programme artwork requirement;
- no source credential/header reuse for logos.

Home/Channels must look complete before logos are implemented.

## 23. Iconography

Use a single icon family consistent with Android/Material where possible.

Rules:

- 24–28 dp navigation/action icons as baseline; increase where 3 m testing requires it;
- outline icons default, filled/tonal variation may signal selected state;
- do not rely on icon shape alone for destructive/recovery states;
- do not introduce decorative mixed icon sets;
- no fake notification/profile icons from the reference.

## 24. Component inventory

Add components incrementally with real consumers.

Likely final primitives:

- `MuxTvLoungeShell`
- `MuxTvNavigationRail`
- `MuxTvNavigationItem`
- `MuxTvHero`
- `MuxTvSectionHeader`
- `MuxTvChannelCard`
- `MuxTvChannelRow`
- `MuxTvChannelLogo`
- `MuxTvProgrammeSummary`
- `MuxTvFilterChip`
- `MuxTvSearchField`
- `MuxTvGuideGrid`
- `MuxTvGuideChannelCell`
- `MuxTvGuideProgrammeCell`
- `MuxTvGuideTimeHeader`
- `MuxTvCurrentTimeIndicator`
- `MuxTvPlayerOverlay`
- `MuxTvPlayerAction`
- `MuxTvSettingsRow`
- `MuxTvSourceCard`
- `MuxTvDetailsSurface`
- `MuxTvEmptyState`
- `MuxTvLoadingState`
- `MuxTvErrorState`

Names are illustrative until implementation; do not create all of these in one speculative design-system PR.

## 25. Semantics/accessibility

- focus must remain visible without colour-only discrimination;
- important selected/playing/favourite states include shape/icon/text cues;
- semantics must never contain sensitive source locator/query/header/credential data;
- support large text without destroying focus order;
- minimum interaction size should remain remote-friendly;
- test reduced motion;
- test grayscale/colour-deficiency robustness for state distinction;
- do not make bronze/gold the only state signal.

## 26. Privacy/security visual rules

The design may expose only public product data already allowed by existing boundaries.

Never render into screenshots/semantics/loggable UI text:

- playlist/XML locators;
- query values;
- cookies;
- credential references/values;
- sensitive headers;
- provider/source internal identities where repository policy forbids them;
- raw exception strings.

Typed safe user-facing failure copy remains authoritative.

## 27. Performance constraints

Lounge must remain viable on `current-low-ram` and old-edge class devices.

Hard design constraints:

- no mandatory runtime blur;
- no shader-heavy glass stack;
- no unbounded shadows/animations;
- no full-catalog/full-guide UI materialization;
- no giant decoded images;
- no programme-artwork cache as a baseline requirement;
- logo prefetch bounded to visible/near-visible window;
- Guide remains lazy/bounded;
- Home rails bounded;
- focus animation cannot allocate expensive per-frame resources.

## 28. Resolution/adaptation matrix

Validate at minimum:

### 720p

- no cropped rail labels/hero actions;
- no more than intended density loss;
- long Russian strings;
- Guide remains usable.

### 1080p

Primary visual reference.

### 4K

- use vector/high-quality logo/banner assets;
- do not scale bitmap assets from low-resolution placeholders;
- layout remains dp/sp-based rather than simply increasing density.

### Large text

- hero and cards adapt without overlap;
- dense Guide may reduce metadata before reducing primary title readability.

## 29. D-pad focus maps

Every screen implementation PR must document and test its focus boundaries.

Examples:

### Home

`Rail <-> Hero -> secondary action`

`Hero Down -> first Home rail`

`Home rail Up -> Hero`

### Channels

`Rail <-> filter row`

`filters Down -> first channel`

`first channel Up -> selected/nearest filter`

`channel OK -> Player`

`Player Back -> same surviving channel`

### Guide

`Rail <-> channel/time grid boundary`

`Left/Right -> programme time movement`

`Up/Down -> channel movement`

`Back -> shell/previous destination according to route state`

### Player

`OK -> show overlay + focus a deterministic primary control`

`Back -> hide overlay`

`Back again -> return to source route and restored focus`

## 30. Visual fixtures

Use secret-free deterministic fixtures for design/device evidence:

1. normal M3U + READY EPG;
2. missing logo;
3. NO_GUIDE;
4. SOURCE_CONFLICT;
5. long Russian channel name;
6. long programme title;
7. missing channel number;
8. multiple variants;
9. currently playing channel;
10. playing + favourite + focused combination;
11. empty favourites;
12. empty search;
13. typed playback failure;
14. source requiring safe user action;
15. large text;
16. reduced motion;
17. 720p / 1080p;
18. low-RAM profile.

## 31. Implementation sequence

This is intentionally split into small PRs. Do not implement the reference image as a repository-wide redesign.

### L0 — design contract only

- accept this spec;
- no production UI changes.

### L1 — Lounge Light semantic theme/tokens

- replace current temporary dark palette with one light gray semantic palette;
- extend typography/surface/focus tokens only where immediately consumed;
- keep existing navigation/route architecture unchanged;
- no theme picker.

Acceptance:

- all existing screens readable in light theme;
- state contrast works at 720p/1080p;
- no pure-white full-screen background;
- existing DeviceMatrix green.

### L2 — Lounge Channels visual package

Base on accepted Now/Next and Favorites work.

- convert channel rows to the final Lounge geometry;
- stable logo/fallback slot (network logo loading still deferred if necessary);
- proper focus/selected/playing/favourite state matrix;
- retain existing focus anchor, filter focus graph and Player/Back restoration;
- no new focus/state owner.

### L3 — Lounge Player overlay

- minimal light-gray overlay;
- hidden by default;
- typed existing playback/approval/failure states;
- favourite action when available;
- preserve Media3/session ownership.

### L4 — Lounge Sources/Add Source

- simplify source card visuals;
- max two actions;
- details surface;
- two-step onboarding presentation;
- preserve security/state-machine contracts.

### L5 — #29 Search + Recent product slices

Build the bounded product contracts first, then apply Lounge components. Do not invent UI data to finish screenshots.

### L6 — #29 bounded/lazy Guide + Lounge Guide grid

- real Guide route;
- precision Lounge density;
- no scale on cells;
- safe focus/time/channel navigation.

### L7 — final Lounge shell/navigation rail + Home composition

Only after Guide/Search are real destinations:

- replace temporary horizontal navigation with collapsed/expanded rail;
- move Sources under Settings;
- implement final Home hero and real Favorites/Recent sections;
- preserve destination state/focus.

### L8 — credential-free channel logo loading

Implement #33 D5 after geometry is stable.

### L9 — visual/device hardening

- 720p/1080p/4K assets;
- long RU text;
- large text;
- reduced motion;
- current-low-ram;
- API26/API36 product DeviceMatrix;
- physical Android/Google TV and Fire TV before alpha claims via #31;
- launcher/banner polish.

## 32. Acceptance criteria

The Lounge design is accepted only when all of the following are true:

### Visual

- one coherent light-gray theme;
- no dominant pure-white canvas;
- reference image’s premium hierarchy/rounded surface language is recognisable without copying its dark/VOD content;
- one restrained warm accent;
- chrome remains visually quieter than content;
- Home looks premium even without programme artwork.

### TV interaction

- every actionable element reachable by D-pad;
- focus visible at every boundary;
- focused, selected and playing states distinct;
- channel rows/Guide cells do not resize/displace neighbours;
- `OK` on channel starts playback directly;
- Player `Back` hides overlay before route exit;
- Player -> Back focus restoration remains intact.

### Product truth

- no recommendation/VOD/profile/notification functionality invented from the reference image;
- no fake artwork/progress/quality/capability metadata;
- Favorites/Recent/Search/Guide use real #29 data/contracts;
- Sources remains safe and technical controls stay outside daily viewing path.

### Architecture

- no new global MVI/Redux/focus engine;
- no duplicate presentation-state owner;
- no alternate player engine;
- no full-catalog/full-guide materialization;
- design-system components added only with real consumers.

### Privacy/security

- no sensitive locator/header/credential/provider data in semantics/screenshots/error copy;
- no credential reuse for channel logos;
- typed sanitized error/approval states preserved.

### Device quality

- 720p and 1080p safe;
- 4K asset quality reviewed;
- long Russian strings reviewed;
- large-text and reduced-motion behaviour reviewed;
- low-RAM smoke evidence;
- API26/API36 product matrix remains green;
- physical-device evidence remains mandatory before alpha compatibility claims.

## 33. Explicit non-goals

This design plan does not approve:

- dark theme / theme picker;
- VOD Movies/Series catalogue;
- user profiles/accounts;
- notification center;
- ML recommendations;
- programme artwork provider integration;
- dynamic dominant-color backgrounds;
- QR/companion onboarding;
- DVR;
- multiview;
- custom global focus engine;
- another state-management framework;
- alternative player engine;
- Rust/UniFFI work;
- speculative quality/capability badges.

## 34. OpenDesign handoff

If OpenDesign is used to explore this direction, this spec is the constraint source.

Generate a MuxTV-specific `DESIGN.md` rather than blindly applying an Apple/Airbnb preset.

Prototype these real screens:

1. Home
2. Channels
3. Guide
4. Search
5. Player overlay
6. Settings
7. Sources
8. Add Source

Prototype keyboard mapping:

- arrows = D-pad;
- Enter = OK;
- Escape = Back.

Expose tweak controls for:

- 720p/1080p;
- focus ring thickness;
- focus scale for cards only;
- rail collapsed/expanded;
- text scale;
- density;
- logo present/missing;
- EPG READY/NO_GUIDE/SOURCE_CONFLICT;
- normal/long Russian strings;
- reduced motion.

The OpenDesign prototype is a visual/interaction reference only. Production remains Kotlin + Compose for TV and must reuse existing MuxTV state/data boundaries.

## 35. Reference guidance

Relevant Android TV guidance for implementation review:

- TV design / 10-foot UI: https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv
- TV focus system: https://developer.android.com/design/ui/tv/guides/styles/focus-system
- adaptive TV / overscan and D-pad: https://developer.android.com/develop/adaptive-apps/guides/tv/build-adaptive-apps-for-tv
- Compose for TV: https://developer.android.com/training/tv/playback/compose
- Media3 Compose player TV behaviour: https://developer.android.com/media/media3/ui/androidtv

## 36. Decision summary

The approved direction is not “make the dark Lounge mockup white.”

It is:

> Keep the Lounge composition, hierarchy, rounded premium surfaces, left rail and warm accent; replace the entire dark environment with a matte light-gray living-room palette; map the visual system strictly onto real MuxTV Live TV / EPG / Search / Favorites / Recent / Settings contracts; keep dense operational surfaces precise; preserve all current ownership/focus/security architecture.

This yields a product that is visually premium like the reference image but still behaves as a fast, truthful IPTV/Live-TV client rather than a fictional streaming-service clone.
