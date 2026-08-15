# MuxTV Lounge Light — полный TV-редизайн (M6-R)

- Дата: 2026-08-15
- База: `feat/external-player-ep04-06` @ `d9b97509` (PR #166, open; стек поверх его плеерной работы)
- Ветка: `feat/lounge-light-tv-redesign`
- PR: в конце — цель `feat/external-player-ep04-06` (стек); после merge #166 в `main` — ретаргет, затем автоматически срабатывают PR-триггеры CI.
- Визуальный контракт: утверждённый макет (композиция в.1 + тёплая палитра в.4) и полный текст спецификации #93 (`docs/design/2026-08-04-muxtv-lounge-light-spec.md`).
- Контракты issue: #33 (оболочка/UX), #111 (D-pad/focus семантика), #93 (дизайн-референс).
- CI: GitHub self-hosted runner. PR-события триггерятся только на `main`, поэтому для ветки запускаем `workflow_dispatch` (`Fast` → `Full` → `DeviceCurrent` на `muxtv-android`/`muxtv-device`); после ретаргета PR на `main` автоматически идут `Full` + `android-tv-product-device-matrix` (API 26/36).

## Зафиксированные допущения

1. Рабочее дерево чистое: «четыре незакоммиченных файла» из ТЗ уже в HEAD `d9b97509` (EP-07 fix-коммит). База = текущий HEAD; ничего пользовательского не перезаписываем.
2. Коммит `f314753e` («paused design preview deployment») и `agent_docs/*` не трогаем.
3. `agent_docs/` в текущем дереве отсутствует — не воссоздаём.
4. Иконки rail: добавляем `androidx.compose.material:material-icons-core` (входит в Compose BOM) — 5 outline-иконок для rail + глифы play/star для маркеров playing/favorite.
5. Настройки: новый модуль `:feature:settings` (CI path-фильтры его уже ожидают).
6. 720p-визуальная сверка — локальный эмуляторный проход (`wm size 1280x720` + скриншоты в `.work/evidence`); 1080p — CI-эвиденс (AVD `tv_1080p`, API 26/36 через DeviceMatrix при ретаргете; для ветки — DeviceCurrent API 36).
7. Палитра: точные значения из #93 (canvas `#F1F2F4` … accent `#9B6A32`); «пергаментность» задана тёплыми surface-тонами `#F7F7F5`/`#FAFAF8`/`#EADDCB`.

## Не-цели (не делаем)

- Без theme-engine/picker, blur, parallax, сетевых шрифтов, artwork, VOD/профилей/рекомендаций/уведомлений, фейковых capability-бейджей, новых focus-движков, новых state-владельцев, лого-загрузчика (D5/L8 отложен — монограммный fallback).
- Публичные domain/data API, схема источников, ключи маршрутов — без изменений. Только внутренние semantic-токены + переиспользуемые TV-компоненты (≥2 экранов).
- Механика плеера не меняется: OK показывает controls, автокрытие ~6 с, Back сначала скрывает overlay. Только тёплый рестайл (все цвета плеера уже семантические — большая часть приходит от темы автоматически).

---

## Стадия 0 — Фундамент: тема и общая оболочка (L1 + shell)

### 0.1 Палитра

`core/designsystem/MuxTvTheme.kt`: заменить `darkColorScheme` на `lightColorScheme` (tv-material3 1.1.0) с семантическим маппингом:

| Слот | Значение | Роль |
| --- | --- | --- |
| `primary` | `#9B6A32` | bronze accent: focus-обводка, CTA, timeline, current-time marker |
| `onPrimary` | `#FFF9F1` | текст на accent |
| `primaryContainer` | `#EADDCB` (accentSoft) | focused/selected тональная поверхность |
| `onPrimaryContainer` | `#7F5428` (accentStrong) | текст на accentSoft |
| `secondary` | `#2F7D3E` | live/playing/progress (только!) |
| `onSecondary` | `#FFFFFF` | |
| `secondaryContainer` | `#DFEEE1` | прогресс-трек, playing-поверхности |
| `onSecondaryContainer` | `#1C4B26` | |
| `background` | `#F1F2F4` (canvas) | основной фон |
| `onBackground` | `#181A1F` (textPrimary) | |
| `surface` | `#F7F7F5` | карточки |
| `onSurface` | `#181A1F` | |
| `surfaceVariant` | `#E9EBEE` (canvasMuted) | rail/группированные регионы |
| `onSurfaceVariant` | `#5D626A` (textSecondary) | |
| `outline`/`border` | `#D6D9DE` (divider) | границы |
| `outlineVariant` | `#E4E7EA` | слабые границы |
| `error`/`errorContainer` | M3 light по умолчанию | семантика ошибок/конфликтов не брендируется |

Статусные цвета (error/warning/success/info) остаются семантическими, не становятся брендовыми. Bronze — не в обычном тексте.

### 0.2 TvTokens: семантические роли (все токены имеют потребителя)

Расширить `TvTokens` (без ломки существующих имён):

- `Color`: `surfaceRaised #FAFAF8`, `surfaceInset #ECEEF1`, `surfacePressed #E2E4E8`, `dividerStrong #C3C7CD`, `accentSoft2 #F1E8DC`, `liveGreen = secondary`-ссылка.
- `Shape`: `heroCorner 28`, `largeCardCorner 22`, `rowCorner 16`, `detailsCorner 24`, `logoCorner 14`, существующие card/button остаются.
- `Spacing`: добавить `micro 4`, `screenInset 56`, `gutter 28`, `sectionGap 40`, `railCollapsed 88`, `railExpanded 248`.
- `Typography`: TV-шкала `screenTitle 36sp`, `sectionTitle 26sp`, `cardTitle 20sp`, `body 18sp`, `metadata 15sp` (потребители: scaffold-заголовки, section header, карточки).
- `Focus`: сохранить `scale=1f` для строк/грида; добавить `cardScale=1.03` (Home-карточки, с зарезервированной focus-оболочкой), `outlineWidth 3dp`, `focusDurationMillis 140`.
- `Motion`: `screenDurationMillis 240` (сохранить), `overlayInMillis 200 / overlayOutMillis 140`.

### 0.3 Компоненты `core/designsystem` (только с реальными потребителями ≥2 экранов)

- `MuxTvScreenScaffold`: единый padding-каркас (56dp inset, верхняя строка: заголовок экрана + `MuxTvClock` справа), консистентная геометрия. Потребители: Home/Channels/Guide/Search/Settings/Sources/Doctor.
- `MuxTvClock`: часы «HH:mm» (tabular figures) — shell utility, на всех не-Player экранах.
- `MuxTvNavigationRail` + `MuxTvNavigationItem`: collapsed 88dp (icon-only), expand 248dp (label), selected-marker ≠ focus, Back при expanded → collapse, Right → возврат фокуса в контент. Потребитель: `AppNavigation` (все 5 назначений).
- `MuxTvChannelLogo`: монограммный тайл (14–16dp corner), детерминированный fallback из имени. Потребители: hero, channel row, channel card.
- `MuxTvProgressTrack`: тонкая полоса прогресса текущей передачи (green `secondary`, только при READY+valid timing). Потребители: channel row, home card, hero.
- `MuxTvFocusSurface`: рестайл под L2/L3 (surface + 1dp разделитель, focused: `surfaceRaised` + bronze outline + мягкая тень; без scale). Потребители: hero, карточки Home.
- `MuxTvActionButton`: рестайл — нейтральная поверхность `surfaceInset`, focused: bronze outline + `accentSoft`, selected-параметр с «• »-маркером (не color-only). Потребитель: все экраны.
- `MuxTvFilterChip`: selected/focused состояния раздельно (accentSoft fill + marker). Потребитель: Channels filters.
- `MuxTvSectionHeader`: заголовок секции rail/card-секций. Потребители: Home rails.
- `MuxTvEmptyState`: спокойное пустое состояние (иконка, текст, одно действие). Потребители: Home (нет источников/нет избранного), Sources.

### 0.4 Shell: замена горизонтальной навигации на rail

- `AppDestination.topLevel = [Home, Channels, Guide, Search, Settings]`; `Sources`/`Doctor` остаются NavKey, но больше не top-level (доступ только из Settings). `AddSource` — внутри workspace Настроек, rail скрыт (как сейчас).
- `AppNavigation`: `Row { MuxTvNavigationRail; NavDisplay(weight(1f)) }`; rail скрыт на `Player`/`AddSource`.
- Rail-элемент: иконка + label при expand, selected-маркер (accentSoft + bronze edge) отличается от focus.
- Back при expanded rail → сначала collapse (где уместно), затем route-back.
- Общий `railFocusRequester` передаётся маршрутам (как текущий `topNavigationFocusRequester`): левый край контента (`focusProperties.left`) ведёт в rail; Right из rail возвращает фокус в контент (запомненный target).
- Initial focus: первичный элемент контента назначения (не rail), rail доступен Left-переходом; у Home/Settings/Channels/Guide/Search — детерминированный initial focus (как сейчас).

### Проверка стадии 0

`gradlew :core:designsystem:testDebugUnitTest :app:tv:testDebugUnitTest :app:tv:assembleDebug` зелёные; TvTokensTest и AppNavigationModelTest обновлены; существующие journey-тесты адаптированы к rail (nav-теги `nav-home`… сохраняются как теги rail-элементов; `nav-sources`/`nav-doctor` удаляются из top-level — тесты `AppNavigationSourceJourneyTest` переводятся на путь через Settings).

---

## Стадия 1 — Главная (Home, реальные данные)

- `HomeViewModel` (feature/home): комбинирует
  - `playbackSessionStateSource` (текущий канал),
  - `RecentChannelsRepository.observeRecent(limit=10)` (последний просмотр + rail «Недавние»),
  - `ChannelBrowseRepository(filter=FAVORITES)` paging snapshot (rail «Избранное»),
  - `EpgGuideRepository.getNowNext` (bounded ≤200 id, обновление по таймеру 60 s) — now/next + прогресс,
  - сигнал «есть источники» (`SourceRefreshStore.observeOverviews()` → не Empty).
- `HomePresentation.kt` — чистые преобразования (unit-testable): выбор hero-канала (current → последний recent → null), текущая передача, прогресс только при valid timing, длинные русские названия (maxLines + ellipsis без слома геометрии), пустые rail → null.
- UI: hero 42–48% верхней высоты, `heroCorner 28dp`, logo tile + номер + имя, now/next + green progress, кнопки `Смотреть/Продолжить` (→ Player) и `Программа` (→ Guide). Пустое состояние: спокойная копия + одно действие `Добавить источник` (→ Settings > Источники > AddSource). Rails «Избранное»/«Недавние» — `LazyRow` landscape-карточек (~300×140dp), focus scale 1.03 в зарезервированной оболочке, OK → Player, Up → hero.
- D-pad: rail ↔ hero, hero Down → первый rail, карточка Up → hero, карточка Left → rail на левом краю; Back из Player → восстановление фокуса (существующая механика маршрутов).
- Unit: `HomePresentationTest` (текущий канал, нет EPG, пустые rails, длинные RU-названия, нет источников).
- Journey: `HomeJourneyTest` (initial focus, rail↔hero, hero→rail, empty state, OK-навигация).

---

## Стадия 2 — Эфир (Channels)

- `MuxTvChannelRow` (в feature/channels или designsystem? — потребители: только Channels → в feature/channels): высота ~96dp фиксированная, зоны: номер (фикс. колонка), лого-тайл 56–64dp, имя + group/variant метаданные, текущая + следующая передача, время + green-прогресс (при READY), постоянные маркеры playing (▶ green) / favorite (★ accent) — маркеры не меняют геометрию.
- Focus: **без scale**, bronze 3dp outline + `surfaceRaised`, мягкая тень; строка не смещает соседей. OK → Player напрямую. FocusAnchor, nearest-previous fallback, Player/Back restoration — без изменений. `focusProperties.left = railFocusRequester`.
- Selected-фильтры: `MuxTvFilterChip` (selected ≠ focused).
- Unit: форматтер now/next времени/прогресса + обрезка длинных названий (`ChannelsPresentationTest`).
- Journey: существующий `ChannelsFocusRestorationTest` остаётся зелёным; добавить кейс rail↔rows.

---

## Стадия 3 — Программа (Guide)

- Precision-рестайл по Lounge: viewport на `surfaceInset`, channel-rail-ячейки `surface`, фокус — outline+tone **без scale** (уже так), current-time marker — bronze (`primary`), focused programme cell — `primaryContainer`+`primary` (уже семантично). Радиусы из токенов, плотность сохранена (72dp строки), sticky-ось без изменений.
- `NO_GUIDE`/`SOURCE_CONFLICT` — существующие typed-состояния, только рестайл.
- ViewModel/focus-anchor/владельцы состояния не трогаем. `GuideFocusJourneyTest` остаётся зелёным.
- 720p-проверка: строки/ячейки не выходят за safe-area.

---

## Стадия 4 — Поиск (Search)

- Рестайл поля (surfaceInset, bronze cursor), состояния idle/loading/results/empty/error — уже typed, выравнивание под scaffold. Тэги и focus-якоря не меняются. `SearchFocusRestorationTest` зелёный.

---

## Стадия 5 — Настройки (Settings workspace)

- Новый модуль `:feature:settings`: секции `Источники`, `Диагностика` (single-column Lounge-строки: иконка + label + описание). Focus: initial на первую секцию, восстановление фокуса секции после Back.
- `AppDestination.Sources`/`Doctor` вложены: открытие секции → route; Back → Settings с восстановленным фокусом. Rail остаётся видимым на Settings/Sources/Doctor.
- **SourcesRoute рестайл (D4)**: карточка = имя, safe-статус, refresh/revision-сводка, максимум два действия `Обновить сейчас` + `Настроить`; остальное (расписание/сеть/зарядка/безопасность) — bounded details surface; закрытие деталей восстанавливает фокус на исходной карточке. Доменные контракты `SourceRefreshStore` не трогаем.
- **AddSourceRoute**: presentation-only — широкий центрированный Lounge-панель, двухшаговый визуальный поток (ввод → подтверждение/approval); `SourceEntrySession`, security-границы, D-pad-обработчики, тэги — без изменений.
- **DoctorRoute**: рестайл под настройки, тэги сохраняются.
- `tools/verify-local.ps1`: добавить `:feature:settings:testDebugUnitTest` и `:feature:settings:lintDebug` в списки; `settings.gradle.kts` — include.
- Journey: `SettingsJourneyTest` (rail → Settings → Источники → карточка → details → закрытие → фокус; AddSource flow; Back-цепочка с восстановлением). `AppNavigationSourceJourneyTest` переводится на новый путь.

---

## Стадия 6 — Плеер (тёплый рестайл)

- `PlayerSurfaceContent`: оверлей — полупрозрачный `surfaceRaised` ~94% alpha + функциональный scrim (не «тёмная тема»); SeekHud — тот же материал; TrackSelectionSheet — surface + scrim 0.45. Кнопки — через обновлённый `MuxTvActionButton`. Rail не добавляется; плеер остаётся полноэкранным.
- Механика без изменений: hidden-by-default, OK reveal, автокрытие 6 s, Back → overlay → route, focus не остаётся на скрытых контролах, seek/track/favorite-контракты EP-04..07 не трогаются.
- Все существующие journey-тесты плеера (`PlayerOverlayJourneyTest`, `PlayerSurfaceContentJourneyTest`, `SeekHudJourneyTest`, `TrackSelectionSheetJourneyTest`) остаются зелёными без правок (кроме адаптаций к рестайлу кнопок, если понадобятся).

---

## Стадия 7 — Сквозные тесты и проверки

### Unit-тесты presentation-преобразований
- `HomePresentationTest` (текущая передача, отсутствие EPG, пустые избранное/источники, длинные русские названия).
- `ChannelsPresentationTest` (now/next, прогресс, обрезка).
- `TvTokensTest`, `AppNavigationModelTest` — обновление.

### Compose/D-pad journeys (app:tv androidTest, по одному на маршрут)
- initial focus, rail ↔ content, перемещение, OK, Back и восстановление фокуса:
  `RailNavigationJourneyTest`, `HomeJourneyTest`, обновлённые `ChannelsFocusRestorationTest`, `GuideFocusJourneyTest`, `SearchFocusRestorationTest`, `SettingsJourneyTest` (вкл. Sources details + AddSource), `AccessibilityJourneyTest` (fontScale 1.3 + reduced motion — доступность rail/строк без обрезанного контента).
- Плеер: существующие 4 journey-класса.
- Скриншоты экранов (captureToImage → device storage → pull в `.work/evidence/screenshots` в harness при Device-режиме) для визуальной сверки с макетом: Home, Guide, Settings, Player — 1080p (CI) + локальный 720p-проход.

### Локальные гейты
- `pwsh -File .\tools\verify-local.ps1 -Mode Fast -NoDaemon` после каждой стадии (или точечные task-вызовы), `-Mode Full` перед финалом.
- CI self-hosted: `workflow_dispatch` `Fast` → `Full` (muxtv-android) → `DeviceCurrent` (muxtv-device, API 36, все connected-тесты + evidence).

### M6-R-гейт (quality-gates.md UI/design)
- deterministic focus graph и Back/focus-restoration — journey-тестами;
- screenshot matrix (1080p CI + 720p локально; large text; reduced motion; empty/конфликтные состояния);
- selected/focused/pressed/disabled различимы (≥2 сигнала, не color-only);
- контраст bronze/текста по палитре #93; отсутствие обрезанного контента.

---

## Финальные шаги

1. `git push -u origin feat/lounge-light-tv-redesign`.
2. CI: `gh workflow run self-hosted-validation.yml --ref feat/lounge-light-tv-redesign -f validation_mode=Fast` → `Full` → `DeviceCurrent`; собираем evidence-artifacts.
3. PR: `gh pr create --base feat/external-player-ep04-06` с полным описанием (этапы, evidence, отклонения, вне scope).
4. Обновить `.work/CURRENT-STATE.md` (M6-R пакет: shell/theme/Home/Settings рестайл — статус по PR).

## Порядок выполнения и точки проверки

| # | Стадия | Проверка (локально) |
| --- | --- | --- |
| 0 | Тема/токены/компоненты/shell+rail | `:core:designsystem:testDebugUnitTest`, `:app:tv:testDebugUnitTest`, assemble, правки journey-тегов |
| 1 | Home | `:feature:home:testDebugUnitTest` + HomeJourney |
| 2 | Channels | `:feature:channels:testDebugUnitTest` + ChannelsFocusRestoration |
| 3 | Guide | `:feature:guide:testDebugUnitTest` + GuideFocusJourney |
| 4 | Search | `:feature:search:testDebugUnitTest` + SearchFocusRestoration |
| 5 | Settings (+Sources/Doctor/AddSource) | `:feature:settings:testDebugUnitTest` + SettingsJourney + AppNavigationSourceJourney |
| 6 | Player рестайл | 4 player journey-класса |
| 7 | Сквозной прогон + CI | verify-local Full, DeviceCurrent, PR |

## Риски и смягчения

- tv-material3 1.1.0 `lightColorScheme` без слота `border` → используем `outline`/`outlineVariant` или явный token; проверяется компиляцией на стадии 0.
- Много существующих journey-тестов завязаны на nav-кнопки (`nav-*`): rail сохраняет те же тэги на элементах, тесты переносятся без изменения их ассертов кроме навигации Sources/Doctor.
- Обновление 17-задачного lint-списка в `tools/verify-local.ps1` — единая правка + `Test-TvHarnessSyntax` в CI её проверяет.
- 720p: harness провижинит только `tv_1080p`; 720p-сверка — локальный проход с `wm size`/`wm density` и скриншотами (эвиденс в `.work/evidence`), CI остаётся 1080p.
