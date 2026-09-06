# Device Playback Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a provider-neutral runtime `DevicePlaybackProfile` and an Android probe that captures bounded codec/display/HDR/memory evidence without changing playback behavior.

**Architecture:** Stable immutable types live in `player:api`; Android collection remains in `player:media3`. A pure projection layer separates platform collection from deterministic normalization so JVM tests can prove ordering, dedupe and unknown semantics independently of instrumentation.

**Tech Stack:** Kotlin 2.3.x, Android API26+, MediaCodec/Display/ActivityManager platform APIs, Media3 1.11.0, JUnit4, Truth, AndroidX instrumentation.

**Spec:** `docs/superpowers/specs/2026-09-06-device-playback-profile-design.md`

## Global Constraints

- Do not modify `PlayerCapabilities` environment semantics.
- Do not leak `android.*` or `androidx.media3.*` types into `player:api`.
- Do not add ranking, buffering, decoder-selection, Doctor UI, quirks or persistence.
- Pre-API29 hardware acceleration evidence is `UNKNOWN`; never infer from names/models.
- Profile collections are deterministic, deduplicated and bounded; supported display modes max 64.
- Emulator evidence is correctness evidence only; #31 owns physical support claims.

---

### Task 1: RED stable environment-capability contract

**Files:**
- Create: `player/api/src/test/kotlin/app/muxtv/player/DevicePlaybackProfileContractTest.kt`

**Interfaces:**
- Consumes: existing `PlayerCapabilities`.
- Produces: failing contract requiring a separate `app.muxtv.player.DevicePlaybackProfile` type while proving session `PlayerCapabilities` has no environment fields.

- [ ] **Step 1: Write the failing reflection-based contract test**

```kotlin
@Test
fun environmentCapabilityHasSeparateStableProfile() {
    val profileClass = runCatching {
        Class.forName("app.muxtv.player.DevicePlaybackProfile")
    }.getOrNull()
    assertThat(profileClass).isNotNull()

    val sessionFields = PlayerCapabilities::class.java.declaredFields.map { it.name }
    assertThat(sessionFields).doesNotContain("videoDecoders")
    assertThat(sessionFields).doesNotContain("display")
    assertThat(sessionFields).doesNotContain("memory")
}
```

- [ ] **Step 2: Run RED**

Run: `./gradlew :player:api:test --tests app.muxtv.player.DevicePlaybackProfileContractTest`
Expected: FAIL because `DevicePlaybackProfile` does not exist.

- [ ] **Step 3: Commit RED only**

Commit: `test(player): define device playback profile boundary`

---

### Task 2: Stable player-api model

**Files:**
- Create: `player/api/src/main/kotlin/app/muxtv/player/DevicePlaybackProfile.kt`
- Modify: `player/api/src/test/kotlin/app/muxtv/player/DevicePlaybackProfileContractTest.kt`

**Interfaces:**
- Produces: `DeviceVideoCodec`, `HardwareAccelerationEvidence`, `DeviceVideoDecodeCapability`, `DeviceHdrType`, `DeviceDisplayMode`, `DeviceDisplayCapabilities`, `DeviceMemoryCapabilities`, `DevicePlaybackProfile`.

- [ ] **Step 1: Extend tests with model invariants**

Test that duplicate codec families are rejected, display dimensions/rate are positive, supported modes are bounded to 64, memory class is positive, and no model class exposes Android/Media3 types.

- [ ] **Step 2: Run tests and observe RED**

Run: `./gradlew :player:api:test`
Expected: compilation/test failures for missing model types/invariants.

- [ ] **Step 3: Implement minimal immutable model**

Use the exact type names/signatures from the design. In `DevicePlaybackProfile.init`, require at most four decoder entries and unique codec families. In `DeviceDisplayCapabilities.init`, require `supportedModes.size <= 64`, unique modes and `currentMode == null || currentMode in supportedModes`. Validate positive display fields and positive `memoryClassMb` in their own data classes.

- [ ] **Step 4: Run GREEN**

Run: `./gradlew :player:api:test`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit: `feat(player): add stable device playback profile contract`

---

### Task 3: Pure deterministic projection

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/DevicePlaybackProfileProjection.kt`
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/DevicePlaybackProfileProjectionTest.kt`

**Interfaces:**
- Consumes: stable player-api model.
- Produces internal raw evidence types plus `projectDevicePlaybackProfile(evidence: DevicePlaybackProbeEvidence): DevicePlaybackProfile`.

Raw evidence:

```kotlin
internal data class VideoDecoderEvidence(
    val codec: DeviceVideoCodec,
    val hardwareAccelerated: Boolean?,
)

internal data class DisplayModeEvidence(
    val widthPixels: Int,
    val heightPixels: Int,
    val refreshRateMilliHz: Int,
)

internal data class DevicePlaybackProbeEvidence(
    val videoDecoders: List<VideoDecoderEvidence>,
    val currentDisplayMode: DisplayModeEvidence?,
    val supportedDisplayModes: List<DisplayModeEvidence>,
    val hdrTypes: Set<DeviceHdrType>,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
)
```

- [ ] **Step 1: Write RED normalization tests**

Cover: duplicate decoder aggregation; `PRESENT` wins if any decoder is hardware accelerated; all known false -> `ABSENT`; any unknown with no true -> `UNKNOWN`; codec enum ordering; invalid display modes dropped; duplicate modes removed; current valid mode inserted; mode cap 64; deterministic output for reversed raw input.

- [ ] **Step 2: Run RED**

Run: `./gradlew :player:media3:testDebugUnitTest --tests app.muxtv.player.media3.DevicePlaybackProfileProjectionTest`
Expected: FAIL/compile failure because projection does not exist.

- [ ] **Step 3: Implement projection**

Aggregate video evidence with enum ordering. Normalize display mode candidates by positive values, distinct, sorted `(widthPixels, heightPixels, refreshRateMilliHz)`, make sure valid current mode is retained, then cap to 64. Construct the stable profile without reading any Android state.

- [ ] **Step 4: Run GREEN**

Run: same focused test, then `./gradlew :player:media3:testDebugUnitTest`.
Expected: PASS.

- [ ] **Step 5: Commit**

Commit: `feat(player): normalize runtime device capability evidence`

---

### Task 4: Android runtime probe and canonical device evidence

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/AndroidDevicePlaybackProfileProbe.kt`
- Create: `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/AndroidDevicePlaybackProfileProbeTest.kt`

**Interfaces:**
- Produces: `internal class AndroidDevicePlaybackProfileProbe(context: Context) { fun capture(): DevicePlaybackProfile }`.

- [ ] **Step 1: Write instrumentation RED**

Instantiate the probe from `ApplicationProvider.getApplicationContext()`, capture a profile, assert positive memory class, decoder list <=4 and unique, modes <=64/positive/unique, current mode belongs to supported modes when non-null, and HDR set contains only stable enum values.

- [ ] **Step 2: Add Android collector**

Use `MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos`; exclude encoders and API29+ aliases; map accepted video MIME types; API29+ uses `isHardwareAccelerated`, API26-28 emits null. Obtain `DisplayManager` default display, `mode`, `supportedModes` and `hdrCapabilities.supportedHdrTypes`; normalize refresh rate with `(refreshRate * 1000f).roundToInt()`. Obtain `ActivityManager.isLowRamDevice` and `memoryClass`. Feed only raw typed evidence to the pure projector.

- [ ] **Step 3: Run host + lint**

Run: `./gradlew :player:api:test :player:media3:testDebugUnitTest :player:media3:lintDebug`
Expected: PASS.

- [ ] **Step 4: Run canonical connected evidence**

Use repository-owned API26 then API36 device workflows/entrypoints. Expected: instrumentation PASS on both; this is correctness evidence, not hardware support certification.

- [ ] **Step 5: Exact-head qualification and merge**

Open/update draft PR with RED evidence. Require Hosted validation, player/media3 lint and risk-selected canonical device gates terminal GREEN on one exact head before ready/merge.
