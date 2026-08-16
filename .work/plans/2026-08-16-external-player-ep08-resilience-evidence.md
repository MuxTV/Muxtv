# External Player EP-08 — TorrServer/progressive resilience evidence: план и реализация

**Дата:** 2026-08-16
**База:** PR #166 `feat/external-player-ep04-06` @ `d9b97509` (EP-04..EP-07, capability-driven shared surface, трек-селекторы, coalesced seek)
**Канонический план:** «MuxTV Ext Player + TorrServer Implementation Plan» §23 EP-08, §24 test strategy, §25 performance measurements
**Media3:** 1.10.1 stable (без upgrade)

## 1. Цель среза

EP-08 не добавляет пользовательских фич. Он строит evidence-инфраструктуру и отвечает на три отложенных решения canonical плана (§23 EP-08):

1. выгоден ли `SeekParameters.CLOSEST_SYNC` вместо `DEFAULT` (baseline остаётся `DEFAULT`, §14.4) — **результат EP-08: inconclusive на PCM-корпусе, `DEFAULT` остаётся** (см. §4.3);
2. оправдан ли один автоматический external reprepare (baseline: typed failure + ручной «Повторить», §17.3) — evidence: обрыв активного запроса даёт typed failure, ручной `prepare()` восстанавливает READY (§4.8); auto-reprepare остаётся за physical evidence;
3. нужно ли менять buffer policy (baseline: Media3 defaults, §16.2) — evidence: rebuffer-цикл и recovery на stock defaults работают; прод-policy не меняется.

Если defaults показывают себя хорошо — PR остаётся tests/evidence-only (§23: «This PR may be tests/evidence-only»).

## 2. Ключевые проектные решения

### 2.1 Decode-free deterministic PCM MP4 вместо бинарного media corpus

Репозиторий не содержит бинарных media-файлов. Для device-тестов нужен playable progressive файл, который:
- воспроизводится на **любом** эмуляторе API 26/36 без зависимости от vendor MediaCodec/HDR (свитчер репозитория использует только `android-tv`/`google-tv` images);
- имеет детерминированный seek map (stss) для A/B-измерения `DEFAULT` vs `CLOSEST_SYNC`;
- может нести 1 или 2 аудио-дорожки для evidence «track switch не перезапускает HTTP».

Решение: `PcmMp4` — чистый Kotlin-генератор MP4 с audio sample entry `sowt` (PCM 16-bit signed LE, 8 kHz mono). PCM проходит Media3 AudioSink напрямую (без MediaCodec), тишина генерируется нулями, stts/stsc/stsz/stco/stss пишутся вручную. Два трека = два `trak` с interleaved chunks.

Ограничение: PCM — audio-only. `onRenderedFirstFrame` для audio-only пути ненадёжен на части прошивок, поэтому для полного external journey (first frame — обязательное условие `ExternalPlaybackStartResult.Started`) в `app:tv` генерируется **на устройстве** короткий H.264 MP4 через MediaCodec+MediaMuxer (solid-color кадры, ~4 с). При отсутствии кодеков тест честно пропускается (`assumeTrue` с явным сообщением); на штатных google_apis TV-образах encoder/decoder присутствуют.

### 2.2 RangeMediaServer — единый fixture в `core:testing`

`MockWebServer` + Dispatcher с семантикой Range (206/Content-Range/Accept-Ranges/ETag), счётчиками запросов (HEAD/GET, range/non-range/416, failures), инъекцией отказов (первый N-й запрос → заданный статус) и задержками (`bodyDelay`, `throttleBody`). Используется и JVM-тестами, и androidTest (`player:media3`, `app:tv`). Продакшн-код не меняется.

### 2.3 Уровни evidence и что они доказывают

| Уровень | Файл | Доказывает |
|---|---|---|
| JVM | `core:testing` fixture tests | корректность HTTP/Range fixture и структуры MP4 |
| Device player-level | `player:media3` androidTest: собственный ExoPlayer + **реальный** `PlaybackMediaSourceFactory` + `MuxTvHttpClients` | нет preflight/HEAD, burst seek → bounded Range, DEFAULT vs CLOSEST_SYNC, default Media3 retry на 503, fallback без Range-support, track switch без перезапроса, rebuffer/восстановление, ручной retry после обрыва |
| Device journey-level | `app:tv` androidTest: реальный `ExternalPlaybackActivity` + `MuxTvPlaybackService` + RangeMediaServer | TorrServe-style ACTION_VIEW → HTTP-approval → first frame (`EXTERNAL_FIRST_FRAME`) → скрытый surface, D-pad seek принят с HUD → Back → завершение, playback остановлен; сетевой evidence здесь не заявляется |
| Physical | manual lane (не CI) | TorrServer на LAN, бюджетные ТВ, vendor codecs — §24.5 canonical плана |

### 2.4 Что НЕ входит в срез

- **Doctor-наблюдения `SEEK_*`/`REBUFFER_*`/`TRACK_SELECTION_*`** — отдельный production-слайс (нужен recorder в service/feature:player). Здесь метрики собирает test-side `PlaybackResilienceProbe` (AnalyticsListener) — та же семантика, что потребуется прод-реализации, но без изменения прод-кода.
- **Реальный TorrServer на CI** — manual/physical lane (см. §7).
- **Изменения `LoadControl`/`SeekParameters`/buffers в проде** — решения по §1 принимаются по evidence, но изменение прод-конфигурации — отдельный PR с отдельным гейтом. Rebuffer-тест использует test-scoped малый `LoadControl` только как инструмент измерения, а не как предложение для прода.
- **EP-09 (back-buffer), EP-10 (FFmpeg), EP-11 (startup isolation), EP-12 (TorrServer REST)** — conditional, см. §6.

## 3. Карта файлов

### Создать

```text
core/testing/src/main/kotlin/app/muxtv/testing/http/RangeMediaServer.kt
core/testing/src/main/kotlin/app/muxtv/testing/media/PcmMp4.kt
core/testing/src/test/kotlin/app/muxtv/testing/http/RangeMediaServerTest.kt
core/testing/src/test/kotlin/app/muxtv/testing/media/PcmMp4Test.kt
player/media3/src/androidTest/kotlin/app/muxtv/player/media3/PlaybackResilienceProbe.kt
player/media3/src/androidTest/kotlin/app/muxtv/player/media3/ProgressiveResilienceEvidenceTest.kt
app/tv/src/androidTest/kotlin/app/muxtv/external/ExternalPlaybackRangeJourneyTest.kt
```

### Изменить

```text
core/testing/build.gradle.kts            # + mockwebserver3
player/media3/build.gradle.kts           # + androidTestImplementation(project(":core:testing"))
app/tv/build.gradle.kts                  # + androidTestImplementation(project(":core:testing"))
```

## 4. Сценарии device-тестов

### `ProgressiveResilienceEvidenceTest` (player:media3, без Compose)

1. `coldStartHasNoPreflightAndIssuesGetRequests` — подготовка PcmMp4 через реальный фабрик: ни одного HEAD, первый запрос — GET (наблюдение: stock Media3 на этом corpus начинает обычным открытым GET без Range-хедера; `Range: bytes=0-` здесь не экспонируется — фиксируется в logcat как evidence, а не утверждается); состояние READY, позиция движется.
2. `rapidSeekBurstProducesBoundedRangeRequests` — 5 быстрых D-pad-запросов через `PlaybackSeekController` → ровно 1 `onApplySeek` → т≤2 новых HTTP-запросов; позиция = base + 5×10 s (clamped). Доказывает coalescing-алгоритм; dual-owner path (#166) остаётся отдельным open deviation (§9.7).
3. `seekModeComparisonIsLoggedAsEvidenceOnPcmCorpus` — A/B `DEFAULT` vs `CLOSEST_SYNC` на PCM-корпусе: оба режима ложатся точно на таргет (аудио-сэмплы самодостаточны, различимых sync points нет), delta = 0; дельта пишется в logcat как evidence и не утверждается. **Вывод EP-08: `SeekParameters.DEFAULT` остаётся**, реальный video-keyframe A/B deferred до physical/realistic corpus (§9.5).
4. `transientServerFailureIsRetriedByDefaultMedia3Policy` — первый запрос 503 → default `DefaultLoadErrorHandlingPolicy` ретраит → READY; счётчики: 1 failure, общее число запросов bounded.
5. `originWithoutRangeSupportPlaysFullBody` — сервер игнорирует Range (200 full) → READY, позиция движется, ровно 1 GET.
6. `audioTrackSwitchDoesNotRestartHttpMediaRequest` — двухтрековый PcmMp4: `TrackSelectionOverride` на вторую дорожку → число GET-запросов не растёт (нет перезапуска источника).
7. `seekIntoStalledChunkRebuffersAndRecovers` — seek-ответ с `bodyDelay` → после первого READY наблюдается BUFFERING → восстановление в READY (rebuffer cycle зафиксирован `PlaybackResilienceProbe`).
8. `connectionLossFailsThenManualRetryRecovers` — детерминированный обрыв **активного** запроса: seek в незагруженный регион (80 s из 90 s) → в полёте Range-запрос с отложенным телом → `server.close()` рвёт передачу → default retry упирается в refused connection → typed player failure → перезапуск сервера + повторный `prepare()`/`play()` на том же locator → READY (evidence для решения про auto-reprepare).

### `ExternalPlaybackRangeJourneyTest` (app:tv)

9. TorrServe-style `ACTION_VIEW` (http://127.0.0.1:port/media.mp4, video/mp4, EXTRA_TITLE) → `ExternalPlaybackActivity` → HTTP-approval (`external-http-approve`) → `ExternalPlaybackStartResult.Started` (первый кадр H.264 с устройства, encode через MediaCodec+EGL с явными PTS) → скрытый surface: D-pad Right ×4 → seek принят (`external-seek-hud`) → Back из overlay → Back → активность завершена, playback остановлен (нет HTTP после destroy). Network Range/rebuffer claims на app-уровне не делаются: 4-секундный fixture полностью буферизуется до D-pad input, поэтому HTTP delta вокруг seek причинно не наблюдаем — этот evidence живёт в `ProgressiveResilienceEvidenceTest`.

Проверка завершения: после `finish()` состояние `MediaController`/service больше не держит external media (через коннектор), и повторный запуск активности с новым intent создаёт новую сессию без утечки старой.

## 5. Верификация и CI

Локально (host):
```text
gradlew :core:testing:test
gradlew :player:media3:testDebugUnitTest
gradlew :core:testing:compileKotlin :player:media3:compileDebugAndroidTestKotlin
gradlew :app:tv:compileDebugKotlin :app:tv:compileDebugAndroidTestKotlin
gradlew :core:testing:lintDebug? / lint по затронутым модулям
```

CI (self-hosted runner, существующий `self-hosted-validation.yml`):
- `DeviceCurrent` (API36) через `workflow_dispatch` — прогоняет Full (host) + connected suite всех модулей (`:player:media3:connectedDebugAndroidTest`, `:app:tv:connectedDebugAndroidTest` подхватывают новые классы автоматически; `Assert-AndroidTestCount` гарантирует ненулевой прогон).
- При наличии бюджета — `DeviceMatrix` (API26+36).

Сбор evidence: логи/счётчики печатаются в logcat (попадает в `tv-device-manifest`-артефакты), assertions фиксируются в TEST-*.xml. Никакие timing thresholds в CI не используются.

## 6. Дальнейшие шаги после этого PR (roadmap)

| Шаг | Содержание | Условие запуска |
|---|---|---|
| Doctor seek/track/rebuffer observations | прод-`AnalyticsListener` в `MuxTvPlaybackService` + `PlaybackObservationKind`: `SEEK_STARTED/SEEK_COMPLETED/SEEK_FAILED`, `REBUFFER_STARTED/REBUFFER_ENDED`, `TRACK_SELECTION_*`; redacted, без URI/labels | следующий слайс после EP-08 (прод-код, не evidence) |
| EP-09 back-buffer (#109) | один глобальный conservative policy + физические PSS/zap-замеры | только если EP-08/physical покажет проблему короткой перемотки |
| EP-10 FFmpeg audio (#117) | официальное media3 FFmpeg extension, fallback-only, ABI/R8 | только если corpus воспроизведёт unsupported-audio blocker |
| EP-11 startup isolation (#118) | `NormalRuntimeCoordinator` для external cold start | только после замера contention на физике |
| EP-12 TorrServer REST adapter | origin config/auth, privacy-safe identity, resume/playlist | post-core, отдельный scope |
| Physical TV corpus (§24.5) | TorrServe→MuxTV→Back journey, MKV multi-audio, длинные RU label'ы, бюджетный ТВ | release evidence, manual lane |
| Manual TorrServer lane | локальный TorrServer (Go binary) + прогон того же connected suite против него | manual, не CI |

## 7. Manual lane: реальный TorrServer (не CI)

```text
1. Скачать TorrServer под Windows/Linux (Go, без зависимостей).
2. Запустить с каталогом торрентов, добавить тестовый торрент с MP4/MKV.
3. Получить stream URL: http://host:8090/stream/<file>?link=<hash>&index=0&play
4. Прогнать ExternalPlaybackRangeJourneyTest-эквивалент вручную (adb shell am start -a android.intent.action.VIEW -d "<url>" -t video/mp4) на физическом ТВ.
5. Зафиксировать: first frame, seek burst без Range storm, rebuffer после Wi-Fi обрыва, Back → TorrServe.
```

## 8. Критерии приёмки среза

- [ ] все host-тесты затронутых модулей зелёные;
- [ ] lint затронутых модулей зелёный;
- [ ] компиляция `app:tv` main+androidTest и `player:media3` androidTest зелёная;
- [ ] DeviceCurrent self-hosted run зелёный, новые классы исполнены (не пустые прогоны);
- [ ] в отчёте: нет HEAD/preflight; burst → 1 applied seek и bounded Range; seek A/B delta зафиксирован (на PCM corpus = 0, `DEFAULT` остаётся); 503 → default retry; track switch без перезапроса; rebuffer + recovery; обрыв активного запроса → typed failure → ручной retry работает;
- [ ] external journey: ACTION_VIEW → approval → first frame → seek HUD → Back без утечек (playback остановлен, HTTP после destroy отсутствует);
- [ ] ни один тест не хранит и не логирует URI path/query/torrent-идентификаторы (только 127.0.0.1 фикстуры);
- [ ] прод-код не изменён (кроме build.gradle test-зависимостей) — defaults Media3 подтверждены или задокументировано обратное.

## 9. Отклонения от canonical плана (задокументированные)

1. PCM MP4 + on-device MediaCodec H.264 вместо бинарного corpus — репозиторий не хранит media-файлы.
2. Rebuffer-инструмент использует test-scoped задержки/обрывы — прод-buffer решение остаётся за physical evidence.
3. Реальный TorrServer не в CI (Windows runner + сеть) — manual lane §7.
4. Doctor-наблюдения seek/track/rebuffer — отдельный слайс (см. §6), здесь test-side probe.
5. Metrics пишутся в logcat/assertions, а не в отдельный JSON-артефакт (паттерн PlayerProxyMeasurement не тиражируется без необходимости).
6. **Seek A/B на PCM-корпусе inconclusive**: audio-only файл не экспонирует видео-keyframe sync points, поэтому delta `DEFAULT`/`CLOSEST_SYNC` = 0 наблюдается и логируется, но не утверждается; решение `SeekParameters.DEFAULT` остаётся, video A/B — на physical corpus (не закрывает §14.4).
7. **Dual seek-controller deviation из #166 остаётся открытым**: `rapidSeekBurst…` доказывает coalescing-алгоритм отдельного controller-а, но не безопасность смешанного D-pad + MediaSession input; EP-08 не делает claim о закрытии deviation (см. #132).
8. Cold start на этом corpus начинается открытым GET без Range-хедера (наблюдение stock Media3); `Range: bytes=0-` паттерн не утверждается как invariant.
9. App-level journey не заявляет network-seek evidence: 4-секундный H.264 fixture буферизуется целиком до D-pad input, HTTP delta вокруг seek не причинно наблюдаем; Range/rebuffer claims живут только в player-level `ProgressiveResilienceEvidenceTest`.
10. `OnDeviceVideoFixture` использует официальный MediaCodec state contract (configure → createInputSurface → start) + EGL/`eglPresentationTimeANDROID` с явными PTS; рендер через `lockCanvas` на codec input surface недопустим.
