---
status: accepted
last_reviewed: 2026-07-19
owners: [diagnostics, player, catalog, security, ui]
---

# TV Doctor specification

## 1. Назначение

TV Doctor проверяет источники, EPG, варианты потоков и совместимость устройства, объясняет проблемы человеческим языком и предлагает обратимые исправления. Он не является скрытым массовым scanner, не гарантирует юридическую доступность потока и не должен ухудшать просмотр фоновой нагрузкой.

## 2. Режимы запуска

### Quick check

Для одного канала перед сохранением/по команде:

- locator resolution;
- DNS/TCP/TLS/redirect;
- manifest/container metadata;
- first-byte/startup estimate;
- basic codec/track compatibility;
- EPG binding state.

### Full channel check

Дополнительно:

- short controlled playback;
- first frame/audio;
- effective resolution/fps/bitrate;
- initial stalls;
- alternate variants;
- failover dry-run without disrupting current session where possible.

### Source audit

Batch checks with sampling and budgets:

- dead/unreachable entries;
- exact URL duplicates;
- duplicate channel proposals;
- EPG coverage/matches;
- invalid logos/metadata;
- suspicious count/churn;
- source freshness.

### Observed health

Passive data from real sessions. It has highest realism and lowest synthetic load but is biased toward watched channels. Synthetic and observed evidence remain distinguished.

## 3. Probe levels

```text
L0 STATIC
  Parse metadata only; no network.

L1 CONNECT
  DNS/connect/TLS/redirect/status/headers.

L2 OPEN
  Read bounded manifest/container metadata, no decoder.

L3 SAMPLE
  Prepare and render a short sample with one decoder session.

L4 OBSERVED
  Aggregate actual user playback sessions.
```

UI displays which level produced each conclusion. `L1 success` does not mean playable video.

## 4. Probe result

```kotlin
data class StreamProbeResult(
    val variantId: StreamVariantId,
    val level: ProbeLevel,
    val startedAt: Instant,
    val duration: Duration,
    val outcome: ProbeOutcome,
    val network: NetworkMeasurements?,
    val media: MediaMeasurements?,
    val compatibility: CompatibilityResult?,
    val warnings: List<DiagnosticFinding>,
    val provenance: ProbeProvenance,
)
```

Raw locator and credentials are excluded.

## 5. Measurements

### Network

- DNS time and address-family outcome;
- connect/TLS time;
- redirect count and origin transitions;
- HTTP status/cache headers/content type;
- time to first byte;
- bounded throughput estimate;
- disconnect/reset/timeouts;
- network transport: Ethernet/Wi-Fi/other, without collecting SSID by default.

### Media

- protocol/container;
- video/audio/subtitle tracks;
- codecs/profile/level;
- resolution/fps/bit depth/HDR;
- declared and observed bitrate;
- manifest freshness and live-window movement;
- first frame/audio time;
- sample stalls and dropped frames where available;
- audio-only/video-only/frozen states.

### Catalog/EPG

- exact and possible duplicate count;
- identity confidence;
- EPG binding/method/confidence;
- guide coverage and staleness;
- timezone ambiguity;
- missing/invalid logo;
- source revision warnings.

## 6. Resource budgets

TV Doctor coordinates through global `ProbeScheduler`.

Initial rules:

- maximum one L3 decoder probe on low/normal device;
- L1/L2 network concurrency default 4, adaptable to device/network;
- no L3 while foreground playback is active unless testing current session or user explicitly confirms;
- stop/defer on thermal severe, low battery device class, storage pressure or unvalidated network;
- cellular/metered network requires explicit permission;
- batch sampling prioritizes favorites/recent channels, then representative strata;
- provider rate limit and Retry-After respected;
- per-host circuit breaker prevents storms.

Full source audit does not automatically play every channel. It uses staged sampling and escalates only uncertain/important cases.

## 7. Health model

Dimensions normalized 0..1 with confidence and sample count:

```text
availability
startup
stability
quality
compatibility
completeness
freshness
```

Health is not a single timeless number. Stored aggregate includes:

- time window;
- observed/synthetic source;
- device/network class;
- sample count;
- uncertainty;
- last failure signatures;
- algorithm version.

A stream that fails only on one TV codec profile is not globally marked dead.

## 8. Initial stream score

```text
0.30 stability
0.20 startup latency
0.15 stall rate
0.15 effective quality
0.10 device compatibility
0.05 audio/subtitle completeness
0.05 freshness
```

Refinement:

- each component must expose raw metric, normalization function and confidence;
- missing component contributes neutral prior with low confidence, not zero;
- recent terminal failures apply decaying penalty;
- successful long observed sessions outweigh short synthetic samples;
- device-specific compatibility remains scoped to capability profile;
- hysteresis prevents switching for small score differences;
- manual pin remains authoritative subject to explicit fallback policy.

Initial selection hysteresis:

```text
switch only if candidate score improves by >= 0.10
or current variant has terminal/transient failure requiring failover
cooldown after failover: 10 minutes per failed variant/session
```

Exact numbers require calibration and are stored in `.work/meta/scoring-model.yaml`.

## 9. Findings

Finding structure:

```text
code
severity: Info | Improvement | Warning | Blocking
scope: Source | Channel | Variant | EPG | Device | Profile
confidence
summary
explanation
evidence references
safe actions[]
requiresConfirmation
undo capability
```

Examples:

- `STREAM_REDIRECTS_TO_INSECURE_ORIGIN`;
- `STREAM_DECODER_UNSUPPORTED_ON_DEVICE`;
- `STREAM_STARTUP_SLOW`;
- `VIDEO_FREEZES_AUDIO_CONTINUES`;
- `EPG_GZIP_DETECTED`;
- `EPG_TIMEZONE_UNRESOLVED`;
- `POSSIBLE_CHANNEL_DUPLICATE`;
- `SOURCE_CHANNEL_COUNT_DROPPED`;
- `LOGO_OVERSIZED_OR_INVALID`;
- `CREDENTIAL_REDIRECT_BLOCKED`.

## 10. Automatic fixes

TV Doctor separates detection from mutation.

Allowed automatic proposal types:

- hide clearly retired provider entry from default view while retaining tombstone;
- choose higher-ranked variant;
- mark temporary cooldown;
- bind exact EPG ID;
- normalize display-only metadata;
- group exact duplicates;
- update source charset/timezone setting after preview;
- remove invalid cached logo;
- schedule source refresh.

Never silently:

- delete source or canonical channel;
- merge uncertain channels;
- change manual EPG binding;
- discard profile overlays;
- disable TLS verification;
- send credentials to redirected origin;
- enable unrestricted cleartext;
- reset database/player settings.

## 11. Preview and undo

Batch fix screen shows:

```text
finding count by category
exact entities affected
before/after examples
confidence and reason
potential side effects
estimated duration/network load
```

Fix transaction records `DoctorMutationSet` and inverse operations. User can:

- apply all safe;
- choose categories/items;
- apply once;
- ignore finding;
- create persistent rule;
- undo until journal compaction.

## 12. UX

Simple summary:

```text
Проверено 412 каналов
Работают: 287
Нестабильны: 46
Нуждаются в программе: 28
Возможные дубли: 63
Недоступны сейчас: 41
```

Numbers always state coverage level/sample caveat. «Недоступны сейчас» is not «навсегда мёртвые».

Expert details expose timings, codecs, redirects, evidence and attempt chronology. Raw secrets remain redacted.

## 13. Scheduling

- manual audit has visible progress/cancel;
- periodic lightweight checks only if user enables them;
- passive observed health always local and low-overhead;
- periodic work uses constraints and quotas;
- large audits checkpoint progress;
- process death resumes from durable queue;
- notification only for meaningful actionable changes, not every transient failure.

## 14. Security/privacy

- probes only user-added/approved destinations;
- source network policy governs private/LAN access;
- DNS rebinding and redirect transitions revalidated;
- no open relay/proxy behavior;
- diagnostic export is explicit;
- no stream content or fingerprint upload by default;
- sample data not retained beyond needed metrics;
- health history retention bounded;
- hostnames may be hidden/hash-redacted in normal logs.

## 15. Calibration

Scoring is evaluated against labeled outcomes:

- successful 30-minute sessions;
- startup p50/p95;
- stall ratio;
- frame drops;
- user manual variant preference;
- repeated failures by device;
- failover success;
- false dead-stream classification.

Do not optimize solely on synthetic local test servers. Include hostile/slow CDNs and representative home networking.

## 16. Acceptance criteria

- foreground playback is never degraded by background audit;
- batch audit can cancel/resume;
- L1 success is not reported as playable proof;
- health is scoped by evidence/device/network and carries confidence;
- all fixes have preview; destructive/uncertain ones require confirmation;
- undo restores exact previous catalog/profile state;
- secrets never enter results/export;
- provider rate limits are respected;
- score is transparent and deterministic for a fixed model version.