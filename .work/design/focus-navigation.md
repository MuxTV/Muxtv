---
status: accepted
last_reviewed: 2026-08-06
owners: [ui, design, accessibility]
reference_repositories:
  - android/tv-samples
  - jellyfin/jellyfin-androidtv
  - Davidona/StreamVault-IPTV
primary_craft_reference: https://github.com/emilkowalski/skills
secondary_visual_reference: Open Design
---

# Focus, D-pad и remote navigation

## 1. Главный принцип

На телевизоре focus является курсором и основным состоянием интерфейса. Любой focusable элемент обязан:

- иметь видимое focused state;
- находиться в предсказуемом графе навигации;
- сохранять/восстанавливать позицию;
- корректно обрабатывать Back;
- не требовать touch/mouse;
- иметь доступное имя и действие.

Красота без надёжного focus считается дефектом.

Для interaction craft после Android TV platform semantics основной референс — `emilkowalski/skills`; Open Design используется как вторичный источник визуальных/composition-приёмов. Подробный проектный адаптер правил: `.work/design/craft-principles.md`.

## 2. Информационная архитектура

Primary navigation:

```text
Главная
Каналы
Программа
Поиск
Профиль/Настройки
```

В simple mode технические sections не находятся в primary navigation. Expert tools доступны через Settings:

```text
Источники
EPG
Smart Channels
TV Doctor
Расширения
Диагностика
Backup
Обновления
```

## 3. Back contract

`Back` выполняет одно наиболее локальное действие:

1. закрыть modal/context menu;
2. закрыть keyboard/search editing и вернуть focus на search result/input owner;
3. закрыть player overlay, не останавливая playback;
4. выйти из full-screen player в предыдущий экран/контекст;
5. вернуться к parent route с восстановлением focus;
6. на root — показать системное/приложенческое подтверждение выхода только если это соответствует Android TV convention.

Back не должен:

- оставлять focus внутри скрытого TextField;
- перескакивать на начало EPG/rail;
- останавливать playback при закрытии overlay;
- открывать неожиданный root screen;
- требовать двойного нажатия из-за потерянного focus.

## 4. Focus restoration

Каждый route хранит `FocusBookmark`:

```text
route key
stable item key
container key
index fallback
scroll offset/time anchor
last interaction timestamp
```

Restoration order:

1. same stable item key;
2. nearest surviving sibling in same container;
3. container default;
4. route default.

Позиция никогда не восстанавливается только по index, если список мог измениться.

Примеры:

- после просмотра каналов guide возвращается к текущему/выбранному каналу и прежнему времени;
- после редактирования source focus возвращается на этот source;
- после удаления профиля focus переходит к следующему профилю, затем primary;
- после обновления playlist исчезнувший channel заменяется ближайшим visible canonical channel.

## 5. Directional navigation

### Rails

- Left/Right перемещает внутри rail;
- Up/Down между rails;
- край rail не вызывает неожиданный переход к sidebar, если пользователь не достиг явной boundary;
- scroll следует за focus без скачков layout;
- returning rail remembers item and offset.

### Sidebar/navigation

- Left from content enters sidebar only from defined left boundary;
- Right restores last content focus for selected section;
- sidebar selected и focused states различаются;
- collapsing/expanding sidebar не меняет logical focus target.

### Grid/EPG

- Up/Down keeps time anchor as close as possible;
- Left/Right moves programme cells by time;
- crossing viewport lazily loads interval;
- fixed channel column and time header synchronize with content;
- pressing `OK` on empty gap has defined no-op/detail behavior;
- «Сейчас» returns to current time and current/last channel without losing row context.

## 6. Remote keys

| Key | Global/Context behavior |
|---|---|
| DPAD | focus/navigation |
| CENTER/ENTER | activate; long press opens context menu where documented |
| BACK | local unwind contract |
| PLAY_PAUSE | control current playback; optional global resume only if explicitly enabled |
| STOP | stop current session, never generic Back |
| NEXT/PREV_CHANNEL | channel zap when available |
| NUMERIC 0–9 | buffered channel-number entry in player/live screens |
| MENU | context actions on Fire/compatible remotes; fallback long press OK |
| FF/REW | seek/catch-up/timeshift only when capability exists |
| INFO | programme/channel info where key available |
| COLOR KEYS | optional remapping, never only path to feature |

Every action has D-pad fallback. Vendor keys are enhancements only.

## 7. Numeric channel entry

- numeric buffer visible and times out after configurable ~1.5–2.5 s;
- `OK` confirms immediately;
- Back removes digit/then closes;
- exact match may switch immediately only after setting;
- duplicate profile numbers are prevented or resolved at edit time;
- hidden/restricted channel returns safe message instead of switching;
- leading zeros handled deterministically;
- input ignored in text fields unless field owns numeric input.

## 8. Long press

Long press is not the only access path for essential actions.

- threshold consistent app-wide;
- press feedback begins immediately;
- action fires once;
- key repeat does not cause repeated destructive action;
- accessibility alternative exists in «Ещё» menu;
- instructions are discoverable contextually, not shown constantly.

Shared components must not synthesize ordinary short clicks from `onPreviewKeyEvent` if doing so consumes the native DOWN/UP sequence needed by long-press semantics.

## 9. Search and keyboard

- focus enters TextField only by explicit activation;
- Back first dismisses IME/editing, then returns to last result/control;
- keyboard appearance does not hide current field/actions;
- query results update without moving focus unpredictably;
- empty/error/loading states have deterministic default focus;
- voice input is optional system integration, not dependency;
- transliteration and normalized search happen in data layer, not keyboard hacks.

## 10. Player overlay

Default mapping:

```text
OK       show/hide primary controls
Up       channel/program info or compact guide
Down     quick channel rail
Left/Right seek only if seekable; otherwise recent channels/guide according to mode
Back     hide overlay, second Back exits player
Long OK  channel actions
```

Mapping must avoid accidental seek on non-seekable live streams. Context capability determines labels and behavior.

Overlay requirements:

- focus starts at last-used control or primary safe control;
- auto-hide pauses while interacting/accessibility reading;
- focus never remains on a removed control after capability change;
- error/recovery overlay remains cancellable;
- quick channel rail includes favorite/current/programme indicators but stays lightweight.

## 11. Profiles

- picker absent when only primary profile exists unless explicitly enabled;
- profile switcher reachable from profile area, not permanently blocking startup;
- primary profile visually marked, not hardcoded as a demographic role;
- creating profile asks only name initially;
- PIN prompt returns focus to initiating item after success/cancel;
- switching profile restores allowed route/focus and cannot leave restricted playback visible.

## 12. Focus visual state

Для плотной TV-навигации (navigation, Channels, Guide, Search, settings lists) focused state должен быть **мгновенным**, без декоративной focus-анимации.

Minimum cues use more than color alone:

- immediate outline/ring;
- surface/luminance or tone change;
- optional low-cost static depth/glow where it does not affect reachability;
- text/icon remains readable;
- no layout reflow or neighbor displacement.

Default dense-navigation rules:

- focus scale = `1.0`;
- no `animateFloatAsState`/tween/spring tied directly to D-pad focus movement;
- no animated position/size/geometry on focus;
- no queued animation under rapid key repeat;
- the cursor becomes visually obvious immediately;
- focus styling does not distort EPG programme-width = time mapping.

Sparse poster/hero components may later opt into a distinct visual treatment only with explicit purpose, screenshot/device evidence and reduced-motion behavior. It is not a global focus default.

Selected != focused:

```text
selected: current navigation/category/profile state
focused: current remote cursor
pressed: active key press
playing: current playback state
```

These states remain distinguishable even in combinations such as focused+selected or focused+playing.

## 13. Motion on remote-driven UI

The primary craft rule is frequency-aware: repeated keyboard/D-pad actions should not animate because motion adds latency and disconnects visual response from the key press.

Therefore:

- ordinary focus move: 0 ms positional/scale animation;
- ordinary D-pad activation: native/instant pressed state, no decorative transform animation;
- repeated rail/grid traversal: no stagger;
- route/overlay animation is allowed only when it explains spatial/state context and never delays input readiness;
- if justified, entry/exit should feel immediately responsive; avoid slow-start `ease-in` UI motion;
- ordinary UI motion stays under ~300 ms;
- reduced motion removes scale/position movement and keeps useful tone/opacity/state feedback.

## 14. Accessibility

- target sizes suitable for 10-foot UI;
- focus order matches visual order;
- semantic role/state/action exposed;
- high-contrast preset;
- reduced motion;
- text scaling without clipping;
- no information only by color;
- timed overlays extend/pause for accessibility;
- TalkBack/VoiceView testing on representative devices;
- RTL mirroring tested separately; playback time direction and media controls follow platform convention.

## 15. Testing

### Deterministic navigation tests

For each screen:

- initial focus;
- every directional edge;
- Back chain;
- route leave/return restoration;
- list item removed/reordered;
- empty/loading/error transitions;
- modal open/close;
- keyboard enter/exit;
- profile switch;
- restricted item;
- long press and key repeat;
- rapid D-pad repeat does not leave a delayed focus-animation queue.

### Screenshot states

```text
default
focused
pressed
selected
playing
focused+selected
focused+playing
disabled
loading
error
high contrast
large text
720p/1080p/4K
```

### Reference lessons

- Android TV official samples are primary for current Compose focus APIs and accessibility demos.
- `emilkowalski/skills` is the primary craft reference after platform correctness: frequent keyboard actions stay immediate; every animation needs a concrete purpose; invisible detail and responsive feedback matter more than decorative motion.
- Open Design is secondary for visual composition/prototype alternatives, not remote semantics.
- Jellyfin issues show real failures: focus trapped in search, guide returning to wrong position, too many remote steps and player lifecycle interactions.
- StreamVault issues show focused text contrast and restore screens with no visible continuation control; visual polish must be checked together with focus reachability.

## 16. Acceptance criteria

- all user journeys complete using only five-button D-pad and Back;
- focus always visible;
- dense D-pad focus movement has no scale/position animation delay;
- Back never traps user in TextField/overlay;
- route return restores stable item context;
- EPG returns to current/selected channel and time;
- no essential action depends solely on long press/vendor key;
- selected/focused/playing states are distinguishable;
- profile picker behavior follows actual number of profiles;
- automated focus graph tests cover every route edge;
- rapid key repeat does not queue stale visual focus transitions.
