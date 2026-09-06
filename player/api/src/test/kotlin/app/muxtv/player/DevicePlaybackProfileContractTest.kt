package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevicePlaybackProfileContractTest {
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

    @Test
    fun stableProfileDoesNotExposeAndroidOrMedia3ImplementationTypes() {
        val stableTypes = listOf(
            DeviceVideoDecodeCapability::class.java,
            DeviceDisplayMode::class.java,
            DeviceDisplayCapabilities::class.java,
            DeviceMemoryCapabilities::class.java,
            DevicePlaybackProfile::class.java,
        )

        val exposedTypeNames = stableTypes
            .flatMap { type -> type.declaredFields.map { field -> field.type.name } }

        assertThat(exposedTypeNames.any { name ->
            name.startsWith("android.") || name.startsWith("androidx.media3.")
        }).isFalse()
    }

    @Test
    fun displayModeRequiresPositiveDimensionsAndRefreshRate() {
        assertInvalid { DeviceDisplayMode(0, 1080, 60_000) }
        assertInvalid { DeviceDisplayMode(1920, 0, 60_000) }
        assertInvalid { DeviceDisplayMode(1920, 1080, 0) }
    }

    @Test
    fun displayCapabilitiesRequireUniqueBoundedModesAndCurrentMembership() {
        val current = DeviceDisplayMode(1920, 1080, 60_000)
        val other = DeviceDisplayMode(3840, 2160, 60_000)

        assertInvalid {
            DeviceDisplayCapabilities(
                currentMode = current,
                supportedModes = listOf(current, current),
                hdrTypes = emptySet(),
            )
        }
        assertInvalid {
            DeviceDisplayCapabilities(
                currentMode = current,
                supportedModes = listOf(other),
                hdrTypes = emptySet(),
            )
        }
        assertInvalid {
            DeviceDisplayCapabilities(
                currentMode = null,
                supportedModes = List(65) { index ->
                    DeviceDisplayMode(1280 + index, 720, 60_000)
                },
                hdrTypes = emptySet(),
            )
        }
    }

    @Test
    fun aggregateCollectionsAreImmutableSnapshots() {
        val current = DeviceDisplayMode(1920, 1080, 60_000)
        val mutableModes = mutableListOf(current)
        val mutableHdrTypes = mutableSetOf(DeviceHdrType.HDR10)
        val mutableDecoders = mutableListOf(
            DeviceVideoDecodeCapability(
                codec = DeviceVideoCodec.AVC,
                hardwareAcceleration = HardwareAccelerationEvidence.PRESENT,
            ),
        )
        val profile = DevicePlaybackProfile(
            videoDecoders = mutableDecoders,
            display = DeviceDisplayCapabilities(
                currentMode = current,
                supportedModes = mutableModes,
                hdrTypes = mutableHdrTypes,
            ),
            memory = DeviceMemoryCapabilities(lowRamDevice = false, memoryClassMb = 256),
        )

        mutableModes.clear()
        mutableHdrTypes.clear()
        mutableDecoders.clear()

        assertThat(profile.videoDecoders).hasSize(1)
        assertThat(profile.display.supportedModes).containsExactly(current)
        assertThat(profile.display.hdrTypes).containsExactly(DeviceHdrType.HDR10)
    }

    @Test
    fun profileRejectsDuplicateCodecFamilies() {
        val capability = DeviceVideoDecodeCapability(
            codec = DeviceVideoCodec.AVC,
            hardwareAcceleration = HardwareAccelerationEvidence.UNKNOWN,
        )

        assertInvalid {
            DevicePlaybackProfile(
                videoDecoders = listOf(capability, capability),
                display = emptyDisplay(),
                memory = DeviceMemoryCapabilities(lowRamDevice = false, memoryClassMb = 256),
            )
        }
    }

    @Test
    fun memoryCapabilityRequiresPositiveMemoryClass() {
        assertInvalid { DeviceMemoryCapabilities(lowRamDevice = false, memoryClassMb = 0) }
    }

    private fun emptyDisplay() = DeviceDisplayCapabilities(
        currentMode = null,
        supportedModes = emptyList(),
        hdrTypes = emptySet(),
    )

    private fun assertInvalid(block: () -> Unit) {
        assertThat(runCatching(block).exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
