---
status: accepted
last_reviewed: 2026-07-19
owners: [performance, quality, player, data, ui]
---

# Benchmark and reliability methodology

## 1. Цель

Performance budgets становятся merge gates только при воспроизводимой методике. Отдельно измеряются UI/runtime, parsing/database, playback/network и endurance. Один быстрый flagship/emulator не считается доказательством качества TV-приложения.

## 2. Device tiers

### Tier A — minimum supported

- Android TV/AOSP API 26–28;
- 2 GB RAM or less usable;
- weak quad-core ARM;
- slow eMMC;
- 1080p output;
- older MediaCodec implementations.

### Tier B — mass market

- Google TV/Android TV API 30+;
- 2–3 GB RAM;
- 4K output;
- common Wi-Fi 5/Ethernet;
- mid-range SoC.

### Tier C — high end

- modern 4K box/TV, API 33+;
- stronger CPU/GPU;
- HDR/audio passthrough reference chain.

### Tier F — Fire TV

- at least one low/mid Fire TV Stick and one higher class device;
- current supported Fire OS generations;
- no Google Play Services assumptions.

### Emulator

Used for deterministic UI/database/instrumentation, not final codec, thermal, startup or network claims.

Concrete model/build fingerprints are recorded in `.work/quality/reference-devices.md` after devices are acquired. A device result without firmware/build is incomplete.

## 3. Environment control

Each report records:

```text
device model/fingerprint/API
MuxTV commit/build type/baseline profile state
battery/power and thermal state
screen resolution/refresh/HDR mode
network transport and signal
router/test server setup
source fixture version
cold/warm/cache state
run count and discarded warmups
```

Tests run plugged in when appropriate, after thermal stabilization and without unrelated background load.

## 4. Network profiles

Deterministic local proxy/server shapes:

```text
LAN_FAST:       low latency, high bandwidth, no loss
HOME_WIFI:      20–40 ms, moderate jitter
SLOW_STABLE:    constrained bandwidth
LOSSY:          packet loss/jitter/reset injection
HIGH_LATENCY:   150–300 ms
INTERRUPTED:    disconnect/reconnect/network switch
RATE_LIMITED:   429/Retry-After
STALE_LIVE:     manifest stops advancing/missing segments
```

Real external streams supplement but never replace controlled fixtures because availability changes and cannot be used as sole regression evidence.

## 5. Statistical protocol

- startup/navigation micro journeys: minimum 10 measured iterations after documented warmup, preferably Macrobenchmark defaults/calibration;
- playback startup: minimum 20 attempts per profile/stream/device for p50/p95, randomized order;
- parser/database: minimum 5 runs per corpus size with clean temp DB/cache state defined;
- endurance: at least one long run per target device plus repeated shorter CI smoke runs;
- report median, p90/p95, min/max and failure count;
- no silently discarded outliers; invalid runs have reason;
- baseline comparison uses same device/firmware/environment;
- regression threshold considers variance/confidence, not one sample.

## 6. Startup journeys

Measure:

1. process not running, cold app start;
2. time to first drawn frame;
3. time to first interactive/focusable shell;
4. time until cached favorite/channel list usable;
5. warm return from background;
6. first start after install/update/migration;
7. startup with 1k/10k/100k catalog and large EPG.

Use Macrobenchmark/Perfetto and system startup metrics. Do not insert custom timestamps only and call them equivalent.

## 7. UI journeys

- Home rapid horizontal rail navigation;
- vertical movement between rails;
- channel list 10k entries;
- category switch;
- EPG horizontal/vertical scroll with lazy window extension;
- opening/closing player overlay;
- profile switch;
- search result updates;
- TV Doctor result list;
- settings with large text/high contrast.

Metrics:

```text
frame time/jank
input-to-visual-response latency
recompositions/allocations where useful
memory/bitmap cache
focus correctness failures
scroll/focus restoration correctness
```

A visually smooth animation that ignores keys or restores wrong focus fails UX gate.

## 8. Parser/database corpus

Generated deterministic sets:

```text
M3U: 1k, 10k, 100k, 500k entries
XMLTV: 10k, 1M, 10M programmes and large compressed/decompressed fixtures
```

Variants include malformed data, duplicates, tokenized URL churn, encodings, gzip/zip, timezone conflicts, cancellation points and process-death checkpoints.

Measure:

- wall time by pipeline stage;
- peak Java/native/graphics heap;
- temp/disk usage;
- DB transaction duration;
- rows/second;
- cancellation latency;
- recovery integrity;
- active catalog read latency during refresh;
- source refresh diff churn.

## 9. Playback startup

Milestones:

```text
user intent
variant selected
locator resolved
request started
manifest/container parsed
decoder initialized
audio ready
first video frame
stable playback window reached
```

Measure failure category as well as time. A fast failed/black-screen attempt is not success.

Profiles:

- HLS/DASH/MPEG-TS progressive where supported;
- SD/HD/FHD/UHD;
- AVC/HEVC/AV1 by device capability;
- common audio/subtitle combinations;
- live, catch-up, recording;
- token refresh and failover.

## 10. Zapping

Channel-zap metric starts at confirmed user channel command and ends at first stable frame/audio on target channel.

Separate:

- same source/protocol;
- cross source;
- cached/warm player path;
- resolver/token refresh;
- failed primary + successful reserve;
- incompatible codec fallback.

Record wrong-channel-frame incidents, audio bleed and stale metadata. Time alone is insufficient.

## 11. Endurance

Scenarios:

- 8–24 hours continuous live playback;
- 100/500 channel switches;
- repeated Activity recreation/background/foreground;
- network disconnect/reconnect and Wi-Fi↔Ethernet;
- source/EPG refresh during playback;
- profile switches;
- repeated EPG open/close;
- storage/cache pressure;
- thermal stress on weak device.

Observe:

- heap/native/graphics growth and post-GC steady band;
- file descriptors/threads;
- player/surface/listener leaks;
- stalls/frame drops;
- DB WAL/temp/cache growth;
- ANR/crash;
- remote responsiveness;
- battery/thermal where meaningful.

## 12. Fault injection

Deterministic failures:

- HTTP 401/403/404/429/500/503;
- slow headers/body, resets, truncated response;
- redirect loop/cross-origin/downgrade;
- DNS failure/IPv6-only/dual-stack path;
- stale HLS, missing segments, behind-live-window;
- decoder init/runtime failure fake adapter;
- surface loss/config change;
- Room constraint/disk full/process death;
- corrupted backup/APK/checksum;
- extension timeout/crash;
- XML/zip bomb rejection.

Expected recovery and terminal code are asserted.

## 13. Performance budgets workflow

- initial budgets are hypotheses until measured on Tier A/B/F;
- first implementation establishes baseline report;
- PR compares affected journeys;
- >10% statistically credible regression requires explanation;
- >20% or budget breach blocks merge unless accepted ADR;
- improvement that compromises correctness, quality or security is rejected;
- major dependency upgrade reruns relevant suite.

## 14. Baseline/Startup Profiles

Generated journeys:

- cold start to Home;
- open Channels;
- open EPG;
- start cached channel;
- search;
- profile switch only after feature exists.

Verification:

- profile artifact packaged in release-like APK;
- benchmark compares compiled-with/without where useful;
- profile rules updated with navigation changes;
- sideloaded APK behavior tested, not only store assumptions.

## 15. CI versus lab

### Every PR

- JVM/parser/domain tests;
- architecture and migration tests;
- screenshot/focus tests;
- small generated corpus;
- emulator smoke;
- build/lint/security gates.

### Scheduled/nightly

- large parser corpus;
- emulator Macrobenchmark trend;
- fuzzing bounded runs;
- long refresh/fault suites.

### Release lab

- physical Tier A/B/F/C devices;
- playback/device codec matrix;
- endurance;
- update/install/migration;
- actual remote/accessibility;
- network shaping.

## 16. Report format

```text
report id/date/commit
method version
hardware/software/environment
fixture version
raw summary and failures
p50/p90/p95
comparison baseline
plots/traces artifact refs
known limitations
pass/fail per budget
```

Raw Perfetto/benchmark artifacts retained for a bounded period and linked from `.work/reviews` without committing huge binaries to source.

## 17. Acceptance criteria

- every claimed performance number names device/environment/method;
- p95 is based on sufficient valid samples;
- external stream availability is not sole benchmark;
- Tier A/B/Fire results gate release;
- parser memory remains bounded and measured;
- playback success includes stable frame/audio, not prepare callback;
- endurance detects leaks and disk growth;
- benchmark regression process is automated where practical and reviewable.