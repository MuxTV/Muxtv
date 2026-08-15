# External Player EP-04..EP-07 — план дальнейших шагов, ревью и коммитов

**Дата:** 2026-08-15
**База:** `feat/external-player-ep04-06` @ 308a5a7d (EP-01..EP-03 + review fixes поверх `upd/player-overlay-tv`)
**Media3:** 1.10.1 stable (проверено по jar: `Player.Listener` **не имеет** `onSeekProcessed`; `MediaSession.Callback` **не имеет** `onSeekForward/onSeekBack` — перехват через `onPlayerCommandRequest`)

## 1. Baseline: фактическое состояние на входе сессии

Рабочее дерево содержит реализацию EP-04..EP-07 (незакоммиченную). Проверка компиляции выявила, что предыдущий review log не был подкреплён реальным прогоном сборки. Найдено и исправлено:

| # | Находка | Фикс |
|---|---|---|
| 1 | `PlaybackSeekController` использует параметры конструктора без `val` | `private val stepMillis/quietWindowMillis/hudLingerMillis` |
| 2 | `rememberCoroutineScope()` внутри `remember {}` (не-composable блок) | `surfaceScope` вынесен в composable-контекст |
| 3 | `Player.Listener.onSeekProcessed` не существует в 1.10.1 | подтверждение только через `onPositionDiscontinuity(reason == DISCONTINUITY_REASON_SEEK)` |
| 4 | `assertDoesNotExist` не является import-ом (это member) | import удалён из `SeekHudJourneyTest` |
| 5 | Несвязанная discontinuity в `Pending` отменяла applyJob → HUD зависал навсегда | `onSeekConfirmed` no-op, если состояние не `Applying` |
| 6 | Неприменённый seek (no-op target / без discontinuity) оставлял HUD в `Applying` навсегда | bounded fallback: `APPLY_TIMEOUT_MILLIS = 2_000` → `Idle` без подтверждения; подтверждение лишь сокращает HUD |

## 2. Дальнейшие шаги этого среза

### Шаг A. EP-07 completion: service-side coalescing для MediaSession seek-команд

Canonical план §14.1 требует service-owned seek controller. D-pad путь уже покрыт клиентским контроллером (события DPAD_LEFT/RIGHT не доходят до service). Но media-кнопки пульта (MEDIA_FORWARD/BACKWARD с повторами) транслируются в `COMMAND_SEEK_FORWARD`/`COMMAND_SEEK_BACK` и исполняются сессией напрямую — без coalescing это Range storm на TorrServer.

Решение (без custom-команд и state-push):

- `MuxTvPlaybackService` создаёт второй экземпляр `PlaybackSeekController` (serviceScope), generation = `player.currentMediaItem.mediaId`, `onApplySeek` → `player.seekTo(target)` с проверкой generation.
- Перехват в `SessionCallback.onPlayerCommandRequest`: `COMMAND_SEEK_FORWARD/BACK` → coalesced запрос; если принят — вернуть `COMMAND_INVALID` (проглотить дефолтное исполнение); если отклонён (live/unknown duration) — вернуть команду как есть.
- `clearInstalled()` сбрасывает контроллер (новая генерация и так отбрасывает старую, reset для гигиены).
- UI-контроллер и service-контроллер не конфликтуют: разные входные поверхности (`seekTo` vs SEEK_FORWARD command), разные ключи генерации.

Почему не полноценный service-owner для D-pad: немедленный pending target для HUD требует клиентского виртуального состояния; пушить его из service обратно — отдельный канал (session extras) без доказанного выигрыша. Клиентский контроллер даёт тот же наблюдаемый результат (N нажатий → 1 `seekTo`), что и подтверждено host-тестами.

### Шаг B. Верификация

```
gradlew :player:api:test :player:media3:testDebugUnitTest :feature:player:testDebugUnitTest
gradlew :app:tv:compileDebugKotlin :app:tv:compileDebugAndroidTestKotlin
gradlew :app:tv:lintDebug :feature:player:lintDebug :player:media3:lintDebug
```

### Шаг C. Коммиты (каждый компилируется и зелёный)

1. `feat(player): capability-driven shared surface with full-label audio/subtitle selectors (EP-04..EP-06)`
   - player/api: `PlaybackCapabilities`, `PlaybackTrackModels` (+тесты)
   - player/media3: `Media3CapabilityProjection` (+тест), `Media3TrackProjector` (+тест), `Media3TrackController`, `build.gradle.kts` (host-тесты Media3-моделей)
   - feature/player: `PlayerSurfaceContent` (промежуточная версия без timeline/seek), `PlayerCapabilitiesProjection`, `TrackSelectionSheet`, `AudioTrackSheet`, `SubtitleTrackSheet`, `TrackLabelFormatter` (+тест)
   - app/tv: `PlayerRoute`, `ExternalPlaybackActivity` → shared surface
   - androidTest: `PlayerSurfaceContentJourneyTest` (2 теста), `TrackSelectionSheetJourneyTest`
2. `feat(player): coalesced seek with transient HUD for seekable sessions (EP-07)`
   - player/media3: `PlaybackSeekController` (+тест, включая edge-cases №5-6), `PlaybackSeekPolicy`
   - player/media3: `MuxTvPlaybackService` — service-side media-key coalescing
   - feature/player: `SeekHud`, `PlayerSurfaceContent` (timeline с фокусом/превью + hidden-surface D-pad seek + HUD)
   - androidTest: `SeekHudJourneyTest`, третий тест в `PlayerSurfaceContentJourneyTest`

## 3. Вне этого среза (остаются следующими шагами)

| Шаг | Содержание | Условие |
|---|---|---|
| EP-08 | Range-aware MockWebServer fixture; preflight/storm/bounded-retry доказательства; seek-to-frame метрики | эмулятор/устройство; решает CLOSEST_SYNC, auto-reprepare, buffer policy |
| Doctor seek/track observations | `SEEK_PENDING/APPLIED/...`, `TRACK_SELECTION_*` | нужен доступ recorder'а в feature:player или события из service |
| Back-buffer (#109) | только после EP-08 evidence | conditional |
| FFmpeg audio (#117) | только если corpus воспроизводит unsupported-audio blocker | conditional |
| #118 startup isolation | только после измерения contention external cold-start | conditional |
| TorrServer REST adapter/resume | post-core | отдельный scope |
| Физический TV corpus (§24.5) | TorrServe→MuxTV→Back journey, MKV multi-audio, длинные RU label'ы | release evidence |

## 4. Отклонения от canonical плана (задокументированные)

1. `PlaybackSeekModels` не вынесены в `player:api`: `SeekControllerState` живёт рядом с контроллером в `player:media3`, UI потребляет `StateFlow` напрямую (feature:player уже зависит от media3).
2. Seek-контроллеры два (UI для D-pad, service для media-команд) вместо одного service-owned: объяснение в шаге A; объединение — только вместе с state-push каналом, если EP-08 покажет необходимость.
3. Doctor-observations seek/track — отложены (см. §3).
4. Timebar интерактивен только при фокусе; hidden-surface D-pad seek запускает HUD без показа overlay — соответствует §11.3/§14.

## 5. Критерии приёмки этого среза

- [x] unit-тесты зелёные (player:api, player:media3, feature:player)
- [x] lint зелёный
- [x] компиляция app:tv main + androidTest зелёная
- [x] N быстрых D-pad нажатий → 1 фактический seek (host-тест контроллера)
- [x] несвязанная discontinuity не ломает pending burst (host-тест)
- [x] неприменённый seek не оставляет HUD навсегда (host-тест)
- [x] stale generation инертен (host-тест)
- [x] media-команды SEEK_FORWARD/BACK coalesced на service-стороне (генерация-проверка в onPlayerCommandRequest)
- [ ] journey-тесты на эмуляторном harness (прогон отдельно)
