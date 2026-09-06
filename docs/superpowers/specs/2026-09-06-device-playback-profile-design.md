# Device Playback Profile Design

## Purpose

Implement #341 / #184 Slice G as a typed runtime environment projection without changing playback behavior. The existing `PlayerCapabilities` remains session-scoped; `DevicePlaybackProfile` describes device/display evidence that future ranking, Doctor, buffer-policy and release-evidence owners may consume.

## Boundaries

- Stable provider-neutral contracts live in `player:api` and contain no Android or Media3 types.
- Android collection lives in `player:media3`.
- The first slice collects video decoder family availability, hardware-acceleration evidence, display modes/HDR and Android low-RAM/memory-class evidence.
- Audio route/passthrough/offload is deferred from this slice because route capability has its own lifecycle and Media3 `AudioCapabilities` is unstable API; it must not be smuggled into a one-shot immutable probe without an explicit ownership decision.
- No consumer changes playback selection, recovery, buffering or decoder choice in this slice.

## Stable model

`player:api` introduces:

```kotlin
enum class DeviceVideoCodec { AVC, HEVC, VP9, AV1 }

enum class HardwareAccelerationEvidence { PRESENT, ABSENT, UNKNOWN }

data class DeviceVideoDecodeCapability(
    val codec: DeviceVideoCodec,
    val hardwareAcceleration: HardwareAccelerationEvidence,
)

enum class DeviceHdrType { HDR10, HLG, HDR10_PLUS, DOLBY_VISION }

data class DeviceDisplayMode(
    val widthPixels: Int,
    val heightPixels: Int,
    val refreshRateMilliHz: Int,
)

data class DeviceDisplayCapabilities(
    val currentMode: DeviceDisplayMode?,
    val supportedModes: List<DeviceDisplayMode>,
    val hdrTypes: Set<DeviceHdrType>,
)

data class DeviceMemoryCapabilities(
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
)

data class DevicePlaybackProfile(
    val videoDecoders: List<DeviceVideoDecodeCapability>,
    val display: DeviceDisplayCapabilities,
    val memory: DeviceMemoryCapabilities,
)
```

Validation keeps values meaningful and collections bounded. `videoDecoders` has at most one entry per accepted codec family; display modes are positive and bounded to 64 normalized entries; HDR types are limited by the enum; memory class is positive.

## Android probe

`AndroidDevicePlaybackProfileProbe` uses application context only and captures one immutable snapshot.

Video:
- enumerate `MediaCodecList.REGULAR_CODECS`;
- exclude encoders;
- map only `video/avc`, `video/hevc`, `video/x-vnd.on2.vp9`, `video/av01`;
- on API 29+, ignore aliases and use `MediaCodecInfo.isHardwareAccelerated`;
- before API 29, hardware acceleration is `UNKNOWN`, never inferred from codec/vendor/model names;
- aggregate multiple decoders deterministically per codec family.

Display:
- obtain the default display through `DisplayManager`;
- project current and supported `Display.Mode` values;
- normalize refresh rate to integer milli-Hz;
- map Android `Display.HdrCapabilities` constants only;
- unavailable display evidence yields `currentMode = null`, empty modes/HDR, not guessed values.

Memory:
- use `ActivityManager.isLowRamDevice` and `memoryClass` directly.

## Deterministic projection

Android collection is separated from a pure normalization step. Raw evidence is deduplicated and sorted by accepted codec enum order and display `(width, height, refreshRateMilliHz)`. Invalid raw display values are dropped rather than published. Supported display modes are capped at 64 after deterministic sorting. If current mode is valid but missing from the platform-supported array it is inserted before the cap so the profile never contradicts itself.

## Evidence and claims

JVM tests cover model validation plus deterministic dedupe/order/unknown semantics. Android instrumentation on canonical API26/API36 validates the real probe can capture a self-consistent profile without crash. Emulator evidence proves API/runtime correctness only; #31 remains the authority for physical codec/HDR/audio support claims.

## Non-goals

No Room persistence, Doctor UI, variant ranking, buffer tuning, quirk registry, manufacturer/model heuristics, FFmpeg/video fallback, second player/seek owner or support claim is added.