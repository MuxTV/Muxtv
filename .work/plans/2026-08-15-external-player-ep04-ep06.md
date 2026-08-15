# External Player EP-04..EP-06 — подробный план реализации

**Дата:** 2026-08-15
**База:** `feat/external-player-core` @ 25dd36de (стек EP-01..EP-03 поверх PR #163 `upd/player-overlay-tv` @ 806f8ea7)
**Рабочая ветка:** `feat/external-player-ep04-06`
**Media3:** 1.10.1 stable

## 1. Scope

| Шаг | Содержание | Acceptance (из canonical plan) |
|---|---|---|
| EP-04 | Общая capability-driven поверхность/overlay, переиспользование для external route | Catalog UI остаётся #163-совместимым; external surface скрыт по умолчанию; OK/Back/autohide единый контракт; external route возвращается в приложение-источник; нет второго владельца overlay state |
| EP-05 | Проекция аудиодорожек Media3 → immutable UI-модели; полнотекстовый селектор; TrackSelectionOverride | Длинные label'ы переносятся без ellipsis; смена дорожки не пересоздаёт media item/player; unsupported виден disabled; фокус на текущей выбранной; стабильное переключение MKV/MP4; raw label не в диагностике |
| EP-06 | Селектор субтитров на той же инфраструктуре | Нет дублированного track state machine; переключение audio→subtitle→audio не перезапускает источник; focus/back restoration |

Вне scope (остаётся на EP-07+): coalesced seek, seek HUD, интерактивный timebar, Range-фикстуры, FFmpeg, back-buffer, TorrServer REST.

## 2. Ключевые архитектурные решения

### D1. Один общий PlayerSurfaceContent в `feature/player`

Сейчас overlay state machine продублирован: `PlayerContent` (private, feature/player) и `ExternalPlaybackContent` (private, app/tv). Извлекается один публичный composable:

```kotlin
PlayerSurfaceContent(
    controller: MediaController,      // состояние + команды
    title: String,
    contentIdentity: Any,             // ключ overlay state (channelId / sessionId)
    favoriteSupported: Boolean,       // catalog=true, external=false
    favoriteAction: PlayerFavoriteAction? = null,
    stopAction: PlayerSurfaceAction? = null,   // catalog: controller.stop(); external: finish
    backAction: PlayerSurfaceAction? = null,   // label отличается ("Назад к каналам" / "Назад")
    testTagPrefix: String = "player",
)
```

- Внутри: hidden-by-default, OK reveal, autohide 6 s, Back → hide overlay → наружу, фокус surface↔primary action — ровно контракт #163.
- Audio/Subtitle действия порождаются capability-моделью внутри (см. D2), не параметрами.
- Поведение каталога и теги `player-*` сохраняются как есть (существующие journey-тесты не меняются).

### D2. Capability model — `player:api` + projection в `player:media3`

`player/api/PlaybackCapabilities.kt` — pure data class (по §10.2 плана):

```kotlin
data class PlayerCapabilities(
    val canSeek: Boolean,
    val canPause: Boolean,
    val canSetTrackSelection: Boolean,
    val hasAudioTracks: Boolean,
    val hasTextTracks: Boolean,
    val supportsFavorite: Boolean,
    val hasKnownDuration: Boolean,
    val isLive: Boolean,
)
```

Чистая функция вывода — в `player:media3` (`Media3CapabilityProjection.kt`, host-testable):

```kotlin
fun derivePlayerCapabilities(
    availableCommands: Set<Int>,
    tracks: Tracks?,
    durationMs: Long,          // C.TIME_UNSET при неизвестной
    isLive: Boolean,
    favoriteSupported: Boolean,
): PlayerCapabilities
```

Compose-обёртка `rememberPlayerCapabilities(controller, favoriteSupported)` в `feature/player` слушает `Player.Listener.onEvents` и пересчитывает модель. UI никогда не выводит capability из слова "TorrServer" или route — только из состояния контроллера.

Выводы:
- `hasKnownDuration = durationMs != C.TIME_UNSET && durationMs > 0`
- `canSeek = COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM in availableCommands`
- `canPause = COMMAND_PLAY_PAUSE in availableCommands`
- `canSetTrackSelection = COMMAND_SET_TRACK_SELECTION_PARAMETERS in availableCommands`
- `hasAudioTracks/hasTextTracks` — по `Tracks.groups` (type == C.TRACK_TYPE_AUDIO/TEXT)
- timebar/position: показываются только при `hasKnownDuration && !isLive` (у live-каталога ничего не меняется → #163-совместимо)

### D3. Треки: проекция вне Compose, без Media3-объектов в UI

`player/api/PlaybackTrackModels.kt`:

```kotlin
data class TrackKey(val groupId: String, val trackIndex: Int)

data class AudioTrackUiModel(
    val key: TrackKey,
    val selected: Boolean,
    val supported: Boolean,
    val primaryLabel: String,      // 1. Format.label → 2. Label.labels → 3. BCP-47 язык → 4. "Аудиодорожка N"
    val languageLabel: String?,    // отдельной строкой только если добавляет информацию
    val technicalLabel: String,    // "E-AC-3 • 5.1 • 48 kHz • 640 kb/s" (пустые части опускаются)
    val isDefault: Boolean,        // selectionFlags & C.SELECTION_FLAG_DEFAULT
    val isForced: Boolean,
)

data class SubtitleTrackUiModel(
    val key: TrackKey,
    val selected: Boolean,
    val supported: Boolean,
    val primaryLabel: String,
    val languageLabel: String?,
    val technicalLabel: String,    // "SRT", "VTT", "PGS", "ASS"…
    val isForced: Boolean,
)
```

- `TrackKey.groupId` = `TrackGroup.id ?: "group-$type-$index"` — session-local, человеческий label никогда не identity.
- Валидация в init: primaryLabel непустой и в пределах щедрого структурного лимита (512), key корректный.

`player/media3/Media3TrackProjector.kt` — чистые функции (host-unit-testable, без Context):

```kotlin
class Media3TrackProjector(private val locale: Locale = Locale.getDefault()) {
    fun audioTracks(tracks: Tracks, selectedGroup: TrackGroup?, selectedIndices: Set<Int>): List<AudioTrackUiModel>
    fun textTracks(tracks: Tracks, selectedGroup: TrackGroup?, selectedIndices: Set<Int>, textDisabled: Boolean): List<SubtitleTrackUiModel>
}
```

- `selected` считается по override (TrackSelectionParameters), а авторитетным становится только после события Media3 (`EVENT_TRACK_SELECTION_PARAMETERS_CHANGED` → пересчёт) — §12.7.
- `supported` = `group.isTrackSupported(i)`.
- Для каждого трека модели создаются даже при `!supported` — unsupported строки видимы, но disabled (§12.6).
- Снимок выбора из параметров: `Media3TrackSelection.snapshot(controller.trackSelectionParameters)` → (audioGroup, audioIndices, textGroup, textIndices, textDisabled). Тип `TrackSelectionParameters` не утекает в projector (не host-testable без Context).

`player/media3/Media3TrackController.kt` — тонкий адаптер применения выбора:

```kotlin
object Media3TrackController {
    fun canSetTrackSelection(controller: MediaController): Boolean
    fun selectAudioTrack(controller, groupId, trackIndex): Boolean
    fun selectTextTrack(controller, groupId, trackIndex): Boolean
    fun disableTextTracks(controller): Boolean
}
```

- Перед set — проверка `COMMAND_SET_TRACK_SELECTION_PARAMETERS` (§12.1).
- Применение строго через `trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group, index))...` — никогда не `setMediaItem/prepare/play` (§12.7).
- `selectAudioTrack` дополнительно снимает `setTrackTypeDisabled(AUDIO, false)`.
- Поиск группы по `TrackGroup.id` в текущих `currentTracks`; если группа исчезла (stale key) — false, ничего не делаем (защита от старой генерации по построению).

### D4. Селектор: одна инфраструктура, два тощих фасада

`feature/player/TrackSelectionSheet.kt` — общий вертикальный список (LazyColumn):

- ширина ~65% usable TV width (fraction + max dp), поверх overlay, без паузы;
- строка: маркер выбора `●/○` (независим от фокуса), primaryLabel (мягкий перенос, НЕТ `maxLines=1`, НЕТ ellipsis), languageLabel если не дублирует primary, technicalLabel отдельной строкой, для unsupported — строка "⚠ Не поддерживается этим устройством";
- focus стартует на выбранной строке (или первой enabled);
- Back закрывает лист и возвращает фокус в вызвавшее действие (FocusRequester из overlay);
- выбор не закрывает лист (§12.6); disabled-строка не focusable.

`AudioTrackSheet.kt` / `SubtitleTrackSheet.kt` — тонкие обёртки над `TrackSelectionSheet`:
- Audio: список `AudioTrackUiModel`, onSelect → `Media3TrackController.selectAudioTrack`.
- Subtitle: первый пункт "Выключить" (selected = textDisabled), далее `SubtitleTrackUiModel`, onSelectOff → `disableTextTracks`, onSelect → `selectTextTrack`.

`feature/player/TrackLabelFormatter.kt`:
- `compactActionLabel(models, fallback)` — для overlay-кнопок: "Аудио · <primaryLabel>" со структурным потолком (48 символов; это compact summary, полный текст — в листе; §12.5 применяется к селектору);
- `formatPlaybackTime(ms)` — "HH:MM:SS"/"MM:SS" для timeline.

### D5. External activity переиспользует shared surface

`ExternalPlaybackActivity`:
- удаляется private `ExternalPlaybackContent` (дубликат overlay);
- `ExternalUiState.Playing` → `PlayerSurfaceContent(controller, title, contentIdentity = sessionId, favoriteSupported = false, stopAction = Остановить→finish, backAction = Назад→finish, testTagPrefix = "external")`;
- теги `external-surface/overlay/primary-action/back` сохраняются;
- OK/Back/autohide — единый код с catalog route.

`PlayerRoute` (catalog): `PlayerContent` заменяется вызовом `PlayerSurfaceContent` с `favoriteSupported = favoriteAction != null`, те же теги `player-*`.

## 3. Файлы

### player:api (создать)

```
PlaybackCapabilities.kt      — PlayerCapabilities (pure)
PlaybackTrackModels.kt       — TrackKey, AudioTrackUiModel, SubtitleTrackUiModel
```

### player:media3 (создать)

```
Media3CapabilityProjection.kt — derivePlayerCapabilities(...) pure
Media3TrackProjector.kt       — Media3TrackProjector + TrackLabelFormatter (codec/channels/labels)
Media3TrackController.kt      — canSetTrackSelection/selectAudioTrack/selectTextTrack/disableTextTracks + snapshot
```

### feature:player (создать/изменить)

```
PlayerSurfaceContent.kt     — извлечённый общий surface/overlay + timeline + sheets hosting
PlayerCapabilitiesProjection.kt — rememberPlayerCapabilities (Compose)
TrackSelectionSheet.kt      — общий лист выбора дорожки
AudioTrackSheet.kt          — обёртка аудио
SubtitleTrackSheet.kt       — обёртка субтитров ("Выключить" + дорожки)
TrackLabelFormatter.kt      — compact-лейблы для overlay-кнопок + формат времени
PlayerRoute.kt              — PlayerContent → PlayerSurfaceContent
```

### app:tv (изменить)

```
external/ExternalPlaybackActivity.kt — ExternalPlaybackContent → PlayerSurfaceContent
```

### Тесты

```
player/api: PlaybackCapabilitiesTest, PlaybackTrackModelsTest
player/media3: Media3CapabilityProjectionTest, Media3TrackProjectorTest
feature/player: TrackLabelFormatterTest
app/tv androidTest:
  TrackSelectionSheetJourneyTest   — focus на выбранной, Back-restore, select-callback, unsupported disabled, полный label без ellipsis
  PlayerSurfaceContentJourneyTest  — audio/subtitle действия скрыты без треков; timeline скрыт при live/unknown duration
```

Не меняются: `MuxTvPlaybackService`, `MuxTvPlaybackSessionContract`, `ExternalPlaybackSessionContract`, lease/registry, Doctor-теги. Селекция дорожек — controller-side операция (Media3 сам применяет TrackSelectionOverride без пересоздания media item).

## 4. Матрица тестов (host)

### Media3TrackProjector
- label только / language только / без метаданных → fallback "Аудиодорожка N";
- длинные RU label'ы ("Авторский одноголосый перевод Гоблина …") сохраняются полностью;
- дубли label/language; commentary/audio-description роли;
- stereo/5.1/7.1, bitrate/sampleRate неизвестны → техническая строка без фейковых "0 kb/s";
- supported/unsupported mix; selected по override; default/forced флаги;
- text tracks: label/language/format badge; textDisabled → selected пусто;
- stale group id → projector не падает.

### Media3CapabilityProjection
- live без duration → hasKnownDuration=false, isLive=true;
- VOD с duration → true/false корректно;
- команды: нет SET_TRACK_SELECTION → canSetTrackSelection=false;
- tracks EMPTY → hasAudioTracks/hasTextTracks=false;
- favoriteSupported прокидывается.

### TrackLabelFormatter
- compact label с потолком; формат времени (1s/61s/3661s/неизвестно).

## 5. Android-тесты (journey)

1. `PlayerSurfaceContentJourneyTest`: surface скрыт → OK reveal → audio/subtitle кнопки отсутствуют при пустых tracks; при `favoriteSupported=false` нет favorite; timeline отсутствует без duration.
2. `TrackSelectionSheetJourneyTest` (лист рендерится напрямую с моделями): фокус стартует на selected; Back → onDismiss + фокус возвращён; Enter на строке → onSelect с ключом; unsupported не focusable; полный длинный label присутствует в семантике (без обрезки).
3. Регресс: существующие `PlayerOverlayJourneyTest`, `PlayerHttpApprovalTest` зелёные без изменений (теги сохранены).

## 6. Проверка

```text
gradlew :player:api:testDebugUnitTest
gradlew :player:media3:testDebugUnitTest
gradlew :feature:player:testDebugUnitTest
gradlew :app:tv:compileDebugKotlin + lint (если есть задачи)
```

Дальше (вне этого среза): EP-07 seek, EP-08 evidence — на уже capability-driven поверхности.

## 7. Риски и отступления от canonical плана

1. `TrackLabelFormatter.kt` размещён в `player:media3` (нужен projector'у для host-тестов), а не в `feature:player`, как в §22.4 — в `feature:player` остаётся UI-форматтер compact-лейблов.
2. Doctor-observations `TRACK_SELECTION_*` не добавляются в этом срезе: recorder не доступен в feature:player без новых DI-контрактов; acceptance EP-05/06 этого не требует; добавляется отдельным срезом вместе с EP-08 evidence.
3. Интерактивный scrub timebar и seek-HUD — строго EP-07; здесь timebar присутствует только как визуальный progress (capability-gated).
4. MKV/MP4-регрессия «repeated switching» физически проверяется на EP-08 corpus (нужен Range-сервер/реальные файлы); host-уровень гарантирует отсутствие пересоздания media item по построению (только TrackSelectionParameters).

## 8. Review & fixes log (2026-08-15)

Выполнено ревью всей реализации; найдено и исправлено:

| # | Находка | Фикс |
|---|---|---|
| 1 | `Player.Commands` не реализует `Set` (`.toSet()` не компилируется) | `Player.Commands.toIntSet()` в `Media3CapabilityProjection` |
| 2 | `TrackGroup(Format...)` присваивает `id = ""`, а не `null` → `TrackKey(groupId="")` падал валидацией | `trackGroupId()`: `id?.takeIf { isNotBlank() } ?: "group-N"` (симметрично в `Media3TrackController.findGroup`) |
| 3 | Host-тесты projector'а падали: `android.text.TextUtils.isEmpty` не мокается | `unitTests.isReturnDefaultValues = true` в `player:media3` (существующие тесты не затронуты — они не вызывали android-методы) |
| 4 | `Format.Builder` в 1.10.1 не имеет `setBitrate` (только `setAverageBitrate`); `bitrate`-поле deprecated | Projector читает `averageBitrate` с fallback на `bitrate`; тесты используют `setAverageBitrate` |
| 5 | `tv-material` `Surface` не принимает `color` (только `SurfaceColors`) | Лист на `Box` + `clip(RoundedCornerShape(TvTokens.Shape.cardCorner))` + `background` (консистентно с остальным кодом) |
| 6 | Язык дублировался в технической строке субтитров ("SRT • русский" + отдельная строка "русский") | `technicalTextLabel` = только формат-бейдж; язык только `languageLabel` |
| 7 | Back в sheet не регистрировал interaction → overlay прятался сразу после закрытия листа (если в листе сидели > 6 c) | `onDismiss` в `PlayerSurfaceContent` вызывает `registerInteraction()` перед закрытием |
| 8 | Модели дорожек пересобирались на ЛЮБОЕ событие плеера → focus в листе дёргался (LaunchedEffect(items) перезапрашивает фокус) | Пересборка только на `EVENT_TRACKS_CHANGED` / `EVENT_TRACK_SELECTION_PARAMETERS_CHANGED` |
| 9 | Лист оставался открытым, если tracks исчезали (capability сменилась) | `LaunchedEffect(showAudioAction, showSubtitleAction)` принудительно закрывает лист |
| 10 | External overlay state ключевался по экземпляру контроллера, а не по сессии | `ExternalUiState.Playing.sessionId` → `contentIdentity` |
| 11 | `SemanticsNodeInteraction.isFocused()` отсутствует в текущей версии compose-ui-test | Хелпер `isFocusedNow()` через `SemanticsProperties.Focused` в journey-тесте |
| 12 | Ожидания тестов: "Русский — Дублированный..." содержит язык → languageLabel=null; "Русские субтитры" не содержит "русский" буквально → languageLabel="русский"; bidi-фикстура | Исправлены ожидания под задокументированное правило `contains(name, ignoreCase)` |

Итог проверки: unit-тесты `player:api` / `player:media3` (95) / `feature:player` / `app:tv` зелёные; lint зелёный; компиляция main + androidTest зелёная. androidTest journey-тесты (листы + capability-gating) написаны, прогон — на эмуляторном harness.

