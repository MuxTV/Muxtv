---
status: accepted
last_reviewed: 2026-07-19
owners: [player, platform, performance]
reference_repositories:
  - androidx/media
  - jellyfin/jellyfin-androidtv
  - kodi-pvr/pvr.iptvsimple
  - mpv-android/mpv-android
---

# Playback runtime architecture

## 1. Scope

Playback — process-level subsystem, отделённый от экранов, provider implementations и конкретного Media3 API. Baseline engine — Media3 stable. Optional libmpv допускается только как отдельная реализация того же контракта и не участвует в раннем MVP.

## 2. Ownership

```text
MuxTvApplication
  └─ PlaybackService / PlaybackController (single owner)
       ├─ PlaybackOrchestrator
       ├─ PlaybackEngine: Media3
       ├─ VariantResolver
       ├─ RecoveryPolicy
       ├─ DeviceCapabilityRegistry
       ├─ MediaSession
       └─ PlaybackTelemetryBuffer
```

Activity/Composable:

- не создают ExoPlayer;
- не хранят `MediaItem` как domain state;
- подписываются на immutable playback state;
- отправляют user intents;
- предоставляют/отзывают video surface.

## 3. Contract

```kotlin
interface PlaybackEngine {
    val capabilities: PlaybackCapabilities
    val state: StateFlow<EngineState>
    val events: Flow<PlaybackEvent>

    suspend fun prepare(request: ResolvedPlaybackRequest)
    suspend fun play()
    suspend fun pause(reason: PauseReason)
    suspend fun seek(command: SeekCommand)
    suspend fun selectTracks(selection: TrackSelection)
    suspend fun setVideoSurface(surface: VideoSurface?)
    suspend fun stop(reason: StopReason)
    suspend fun release()
}
```

Engine errors map to stable MuxTV error catalog before leaving adapter.

## 4. Orchestrator state machine

```text
Idle
 → ResolvingVariant
 → Preparing
 → WaitingForSurface
 → Playing
 ↔ Buffering
 ↔ Paused
 → Seeking
 → Recovering
 → Playing | Failed
 → Stopping
 → Idle
```

Orthogonal state:

```text
foreground/background
surface attached/detached
audio focus held/ducked/lost
network available/validated/lost
live edge / behind live window
```

State transitions serialized through one command actor/mutex. Engine callbacks never mutate UI state directly from arbitrary threads.

## 5. PlaybackRequest

```text
sessionId
canonicalChannelId
variantCandidateSet
contentKind: Live | CatchUp | Recording | VOD
requestedStart: LiveEdge | Position | ProgrammeBoundary
profile preferences snapshot
request policy references
audio/subtitle preferences
recovery budget
```

Resolved request contains short-lived locator/headers and is never persisted verbatim in history or logs.

## 6. Surface and Activity lifecycle

- player lifetime is independent of Activity recreation;
- surface detach does not imply stop;
- resolution/configuration change must not destroy playback session;
- foreground full-screen playback keeps surface attached;
- when app backgrounds, policy chooses pause/continue based on content, user setting and platform rules;
- Home, voice search and system interruptions follow platform lifecycle;
- returning recreates UI and rebinds surface/state;
- process death is not treated as seamless playback continuation unless a persisted safe resume state exists.

## 7. MediaSession and audio focus

- MediaSession reflects current item, programme and channel;
- hardware play/pause/stop/seek keys map to intents;
- audio focus gained before audible playback;
- transient loss pauses or ducks only per content policy;
- permanent loss pauses/stops;
- HDMI/headset route changes produce explicit event;
- noisy output handling is enabled where applicable;
- media-style notification/foreground service is used only when background playback is supported and required;
- live channel switching updates session metadata without recreating service.

## 8. Live semantics

- `LiveEdge` obtained from timeline, not wall-clock assumption;
- `ERROR_CODE_BEHIND_LIVE_WINDOW` triggers bounded seek-to-default/live-edge recovery for supported live content;
- paused live playback may intentionally fall behind edge; policy distinguishes user pause from unintentional stall;
- HLS/DASH sliding windows expose seekable range;
- target live offset is content/device/network profile input;
- low-latency mode is opt-in per stream and disabled if it worsens stability;
- programme boundaries are EPG hints, not media segment guarantees.

## 9. Startup sequence

1. resolve candidate and current locator;
2. apply request/redirect security policy;
3. build MediaItem behind adapter;
4. select codec/device profile;
5. prepare;
6. wait for first frame/audio readiness;
7. mark successful playback only after defined stability window;
8. publish session observation to health model.

Black screen timeout is separate from network connect timeout. Audio-only/video-frozen states are detected when possible and classified distinctly.

## 10. Recovery policy

Recovery budget limits attempts, elapsed time and repeated candidate use.

Order:

1. live-window correction;
2. retry transient request using server `Retry-After` where valid;
3. refresh tokenized locator via provider resolver;
4. recreate engine item without service recreation;
5. switch quality/CDN sibling;
6. switch reserve `StreamVariant` using hysteresis/cooldown;
7. safe decoder/audio fallback;
8. fail with human-readable action.

No infinite retry. User `Stop` cancels recovery immediately.

## 11. Decoder/device capability profile

Capability registry records observations, not only static `MediaCodecList` claims:

```text
codec/profile/level
resolution/fps/bit depth/HDR
audio codec/channels/passthrough
known runtime failures
successful observed combinations
firmware/device fingerprint
```

Static support claim can be overridden by session evidence after repeated runtime initialization failure. User can reset learned compatibility.

Do not maintain a giant hardcoded model blacklist without issue evidence and expiry/review metadata.

## 12. Tracks

Selection order:

- explicit per-session user choice;
- profile language preference;
- forced/default flags;
- compatibility;
- source order fallback.

Track identity uses stable semantic fingerprint, not array index. This prevents crashes when manifests refresh and indexes change.

Subtitle support records format, language, forced/default, embedded/external and renderer support. Unsupported subtitle does not crash video playback.

## 13. Network changes

- network callbacks update validated connectivity;
- Wi-Fi↔Ethernet transition gives current player a short grace period;
- lost network pauses recovery timer appropriately;
- restored network re-resolves volatile locator;
- IPv4/IPv6 are both supported by normal resolver/client behavior;
- app must not assume local providers have IPv4 A records;
- captive/unvalidated network is distinct from no network.

## 14. Caching and timeshift

Baseline Media3 cache is bounded and purpose-specific:

- metadata/manifests may be cached under HTTP rules;
- live media cache is not presented as durable offline content;
- local timeshift requires explicit ring-buffer/storage design and is deferred;
- provider catch-up and local timeshift are separate capabilities;
- cache keys redact volatile tokens where safe but must not cause cross-user credential collision.

## 15. Diagnostics

Session records:

```text
session/correlation ID
channel and variant opaque IDs
engine/version
protocol/container
startup milestones
selected codecs/tracks
buffering/stall intervals
network transitions
recovery attempts and reasons
first-frame and stable-play timestamps
terminal reason
```

Never include credential values or unredacted signed URLs.

## 16. Reference repository findings

### AndroidX Media

Canonical source for engine behavior and APIs. Its demo is a behavior reference, not MuxTV domain architecture.

### Jellyfin Android TV

Long-lived issue history demonstrates:

- runtime decoder claims can be wrong;
- subtitles must not be addressed by unstable indexes;
- Activity/configuration recreation can terminate playback if ownership is wrong;
- Fire TV and specific firmware require real-device testing;
- audio passthrough and HDR behavior vary by device chain;
- playback rewrite becomes expensive when the player boundary is not isolated early.

### Kodi IPTV Simple

Useful distinction between live, catch-up and timeshift capabilities depending on inputstream. MuxTV should not label every HLS/TS source as seekable.

### mpv-android

Useful compatibility benchmark and possible fallback, but native build, packaging, UI integration and lifecycle cost make it unsuitable as baseline engine.

## 17. Acceptance criteria

- Activity recreation does not stop active playback;
- Back/Home/system interruptions have deterministic behavior;
- behind-live-window recovers within budget;
- unstable track indexes cannot crash selection;
- stop cancels retry/failover;
- unsupported codec produces classified error and optional fallback;
- audio-only/video-freeze conditions are diagnosable;
- network transition can recover without returning to catalog;
- all engine-specific types remain inside player adapter;
- 100 channel switches do not leak player instances, surfaces or listeners.