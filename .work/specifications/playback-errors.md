---
status: accepted
last_reviewed: 2026-07-19
owners: [player, ui, diagnostics]
---

# Playback error taxonomy

## 1. Цель

Пользователь и feature-код не должны видеть `PlaybackException`, `IOException`, codec names или HTTP stack traces. Media3, provider и network errors переводятся в стабильный каталог MuxTV с машинным кодом, понятным объяснением, recovery policy и безопасными диагностическими данными.

## 2. Структура ошибки

```kotlin
data class PlaybackFailure(
    val code: PlaybackErrorCode,
    val category: PlaybackErrorCategory,
    val retryability: Retryability,
    val userMessageKey: String,
    val technicalSummary: String,
    val attemptedActions: List<RecoveryAction>,
    val nextActions: List<UserAction>,
    val correlationId: CorrelationId,
    val diagnostics: RedactedDiagnostics,
)
```

Ошибка immutable и не содержит исходный URL, credentials, cookies или provider token.

## 3. Категории

### SOURCE

| Code | Meaning | Default action |
|---|---|---|
| `SOURCE_NOT_FOUND` | 404/410 или provider удалил объект | refresh locator, next variant |
| `SOURCE_UNAUTHORIZED` | 401/403 | refresh credentials/token; не повторять бесконечно |
| `SOURCE_RATE_LIMITED` | 429 | respect Retry-After, cooldown |
| `SOURCE_REDIRECT_REJECTED` | redirect нарушил policy | stop, explain security restriction |
| `SOURCE_RESPONSE_INVALID` | payload/manifest/container malformed | next variant |
| `SOURCE_EMPTY` | connection succeeded, usable media absent | next variant |
| `SOURCE_EXPIRED_LOCATOR` | signed URL/token expired | provider resolver refresh |
| `SOURCE_UNSUPPORTED_SCHEME` | locator cannot be handled | configuration action |

### NETWORK

| Code | Meaning | Default action |
|---|---|---|
| `NETWORK_UNAVAILABLE` | no validated network | wait for network |
| `NETWORK_TIMEOUT_CONNECT` | connect timeout | bounded retry/next variant |
| `NETWORK_TIMEOUT_READ` | stream stalled before media data | retry/next variant |
| `NETWORK_DNS_FAILED` | resolution failure | retry after network change |
| `NETWORK_TLS_FAILED` | certificate/handshake failure | no insecure bypass by default |
| `NETWORK_CONNECTION_RESET` | transient transport failure | retry current once |
| `NETWORK_IPV4_IPV6_PATH_FAILED` | address-family/path issue | normal resolver fallback; diagnose |
| `NETWORK_CAPTIVE_OR_UNVALIDATED` | network exists but internet not validated | user action |

### LIVE

| Code | Meaning | Default action |
|---|---|---|
| `LIVE_BEHIND_WINDOW` | position left sliding live window | seek to default/live edge |
| `LIVE_MANIFEST_STALE` | playlist/MPD stopped advancing | reload manifest/locator |
| `LIVE_SEGMENT_MISSING` | media segment unavailable | short retry, skip only if safe |
| `LIVE_TIMELINE_DISCONTINUITY` | unsupported/inconsistent discontinuity | recreate item/variant |
| `LIVE_EDGE_UNKNOWN` | timeline cannot establish live position | conservative playback or fail |

### DECODER

| Code | Meaning | Default action |
|---|---|---|
| `DECODER_VIDEO_UNSUPPORTED` | no suitable video decoder | compatible variant/external engine |
| `DECODER_AUDIO_UNSUPPORTED` | audio unsupported | audio fallback/other track |
| `DECODER_INIT_FAILED` | advertised decoder fails at runtime | record device evidence, fallback |
| `DECODER_RUNTIME_FAILED` | decoder crashed/stalled after start | recreate/fallback once |
| `DECODER_SECURE_REQUIRED` | secure decoder/DRM mismatch | fail unless authorized flow exists |
| `AUDIO_PASSTHROUGH_FAILED` | output chain cannot use requested mode | decode to PCM/fallback |
| `HDR_MODE_UNSUPPORTED` | incompatible HDR/DV path | compatible variant if present |

### TRACK

| Code | Meaning | Default action |
|---|---|---|
| `TRACK_SELECTION_STALE` | selected track disappeared after manifest refresh | semantic reselect |
| `SUBTITLE_UNSUPPORTED` | subtitle renderer unsupported | continue video, disable subtitle |
| `SUBTITLE_PARSE_FAILED` | malformed external/embedded subtitle | continue video, report |
| `AUDIO_TRACK_UNAVAILABLE` | preferred audio no longer exists | language/default fallback |

### RENDERING

| Code | Meaning | Default action |
|---|---|---|
| `VIDEO_SURFACE_LOST` | surface detached/destroyed | await/rebind surface |
| `VIDEO_FIRST_FRAME_TIMEOUT` | no rendered frame before budget | detect audio-only/decoder/network, recover |
| `VIDEO_FROZEN_AUDIO_CONTINUES` | frames stopped while audio progresses | decoder/stream recovery |
| `AUDIO_SILENT_VIDEO_CONTINUES` | video progresses without audio | track/output recovery |
| `DISPLAY_MODE_CHANGE_FAILED` | resolution/refresh switch disrupted output | restore safe mode, continue/reprepare |

### POLICY / USER

| Code | Meaning | Default action |
|---|---|---|
| `PROFILE_CONTENT_RESTRICTED` | current profile policy forbids content | stop and return to allowed screen |
| `USER_STOPPED` | explicit stop/back action | terminal, no error UI |
| `RECOVERY_BUDGET_EXHAUSTED` | all bounded attempts spent | final explanation |
| `VARIANT_PIN_UNAVAILABLE` | manually pinned variant failed | ask before unpin or use allowed fallback policy |

### INTERNAL

| Code | Meaning | Default action |
|---|---|---|
| `PLAYER_STATE_INVARIANT` | illegal state transition | stop safely, diagnostic export |
| `PLAYER_ENGINE_CRASHED` | adapter/native failure | recreate once if safe |
| `UNKNOWN_PLAYBACK_ERROR` | unmapped failure | fail safely, record mapper gap |

## 4. Retryability

```text
Never
ImmediateOnce
Backoff
WaitForCondition
RefreshResolver
TryNextVariant
UserActionRequired
```

Retry policy is data, not `if/else` scattered through UI. Same `(code, context)` always yields deterministic default recovery.

## 5. User messages

Message structure:

```text
Что произошло
Что приложение уже попробовало
Что можно сделать сейчас
```

Example:

```text
Канал временно недоступен.
Проверены основной и два резервных потока.
Повторить проверку или открыть диагностику.
```

Forbidden:

- raw exception text;
- «Unknown error» without correlation ID/action;
- exposing HTTP credentials/query;
- blaming user/network without evidence;
- modal retry loop that freezes UI if ignored.

## 6. Recovery accounting

`RecoveryBudget` limits:

- total elapsed recovery time;
- attempts per action type;
- attempts per variant;
- provider re-resolution count;
- engine recreation count;
- decoder fallback count.

Identical failure signatures increment circuit-breaker counters. A rapidly failing variant enters cooldown and is not selected again in the same session.

## 7. Error mapping

Adapter mapping is versioned and tested against:

- Media3 stable error codes;
- HTTP status and transport exceptions;
- MediaCodec initialization/runtime exceptions;
- provider resolver failures;
- internal state machine violations.

Any unmapped Media3 code is recorded as `UNKNOWN_PLAYBACK_ERROR` with sanitized numeric/original class metadata and creates a test-gap signal; it is not shown verbatim to the user.

## 8. Diagnostics and privacy

Allowed:

- error code/category;
- status code;
- host hash or user-approved hostname disclosure;
- protocol/container/codec metadata;
- timing and attempt sequence;
- device model/firmware when exporting explicitly;
- Media3/MuxTV version.

Redacted:

- URL userinfo/query;
- Authorization/Cookie/Referer values;
- provider username/password/token;
- full playlist entry;
- PIN/profile private data;
- local file names unless explicitly selected.

## 9. UI behavior

- transient recovery uses non-blocking status overlay;
- user can cancel recovery;
- final failure leaves player UI responsive;
- Back always exits error overlay predictably;
- focus defaults to safest common action, usually «Повторить» or «Назад», never destructive reset;
- diagnostics opens a separate screen without losing the current channel context;
- successful failover may show brief source-switch notification, configurable per profile.

## 10. Tests

- table-driven mapping for every Media3/network/provider error;
- legal/illegal state transitions;
- recovery budgets and circuit breaker;
- stop cancels pending retry;
- behind-live-window green path;
- expired locator → resolver → success;
- decoder init failure → alternate profile/variant;
- stale subtitle index cannot crash playback;
- redaction property tests over URLs/headers;
- UI screenshot/focus tests for recovering/final failure states;
- unknown error still produces safe message and correlation ID.

## 11. Acceptance criteria

- no raw engine exception reaches UI;
- every terminal failure has stable code and next action;
- retries are bounded and cancellable;
- identical failing variant is not selected repeatedly;
- subtitle/audio track errors do not crash video unnecessarily;
- exported report contains enough chronology to diagnose but no credentials;
- changing Media3 version requires mapper contract tests to pass.