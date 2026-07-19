---
status: accepted
last_reviewed: 2026-07-19
---

# Playback, failover и TV Doctor

## Контракт воспроизведения

```kotlin
interface PlaybackEngine {
    val capabilities: PlaybackCapabilities
    val state: StateFlow<PlaybackState>
    val diagnostics: Flow<PlaybackDiagnosticEvent>

    suspend fun prepare(request: PlaybackRequest)
    suspend fun play()
    suspend fun pause()
    suspend fun stop(reason: StopReason)
    suspend fun selectTrack(selection: TrackSelection)
}
```

Domain и features не используют `ExoPlayer`, `MediaItem` или `PlaybackException` напрямую. Media3 errors преобразуются в стабильную классификацию MuxTV.

## Playback state machine

```text
Idle
 → ResolvingVariant
 → Preparing
 → Playing
 ↔ Buffering
 → Recovering
 → Playing | Failed
 → Stopping
 → Idle
```

`Recovering` имеет ограниченный budget и не допускает бесконечных retry loops.

## Recovery order

1. повторить current request при transient network error;
2. повторно разрешить redirect/tokenized URL через provider adapter;
3. сменить CDN/quality variant внутри того же logical source;
4. перейти на резервный `StreamVariant`;
5. применить совместимый decoder/profile fallback;
6. завершить с понятной причиной и диагностикой.

Каждый шаг записывает `PlaybackAttempt`, но URL credentials и tokens редактируются перед логированием.

## Stream score

Базовый score строится прозрачно:

```text
0.30 stability
0.20 startup latency
0.15 stall rate
0.15 effective quality
0.10 codec/device compatibility
0.05 audio/subtitle completeness
0.05 freshness of probe
```

Профили пользователя меняют веса, но не скрывают результат. Ручной pin имеет приоритет над автоматическим score до явного сброса.

## Health probes

Probe levels:

- `HEAD_OR_METADATA` — дешёвая проверка URL/redirect/TLS;
- `OPEN_STREAM` — открытие manifest/container и чтение metadata;
- `PLAY_SAMPLE` — короткий controlled playback для startup/stall/codec;
- `OBSERVED_SESSION` — статистика реального просмотра.

Фоновые массовые probes ограничиваются по concurrency, сети, температуре и питанию. На телевизоре нельзя одновременно запускать десятки decoder sessions.

## Media3 policy

- production baseline: Media3 stable release;
- HLS/DASH modules подключаются явно;
- player instance принадлежит service/controller scope;
- UI подписывается на state, а не владеет player;
- custom load error policy учитывает live semantics;
- tunneling, offload и decoder fallback включаются через device capability profile;
- optional libmpv реализует тот же contract в отдельном compatibility flavor, а не смешивается с Media3 orchestration.

## Пользовательские ошибки

В UI запрещены сырые исключения. Ошибка содержит:

- краткую причину;
- какие варианты уже проверены;
- безопасное следующее действие;
- ссылку на расширенную диагностику;
- correlation ID для export report.