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
}
