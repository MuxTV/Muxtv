package app.muxtv.external

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalNetworkPermissionGateTest {
    @Test
    fun `below android 17 no permission is ever required`() {
        for (apiLevel in listOf(26, 33, 35, LocalNetworkPermissionGate.ANDROID_17_API - 1)) {
            val gate = LocalNetworkPermissionGate(apiLevel)
            assertThat(gate.permissionRequired(LocalNetworkClassification.LOCAL)).isFalse()
            assertThat(gate.permissionRequired(LocalNetworkClassification.REMOTE)).isFalse()
            assertThat(gate.permissionRequired(LocalNetworkClassification.LOOPBACK)).isFalse()
            assertThat(gate.permissionRequired(LocalNetworkClassification.AMBIGUOUS)).isFalse()
        }
    }

    @Test
    fun `android 16 opt-in experiment never uses the runtime permission`() {
        // Android 16 / API36 has no mandatory ACCESS_LOCAL_NETWORK contract (LNP opt-in
        // experiment): requesting the runtime permission there is explicitly not recommended.
        val gate = LocalNetworkPermissionGate(36)
        assertThat(gate.permissionRequired(LocalNetworkClassification.LOCAL)).isFalse()
        assertThat(gate.permissionRequired(LocalNetworkClassification.REMOTE)).isFalse()
        assertThat(gate.permissionRequired(LocalNetworkClassification.LOOPBACK)).isFalse()
    }

    @Test
    fun `android 17 and newer require permission only for local targets`() {
        for (apiLevel in listOf(
            LocalNetworkPermissionGate.ANDROID_17_API,
            LocalNetworkPermissionGate.ANDROID_17_API + 1,
        )) {
            val gate = LocalNetworkPermissionGate(apiLevel)

            assertThat(gate.permissionRequired(LocalNetworkClassification.LOCAL)).isTrue()
            assertThat(gate.permissionRequired(LocalNetworkClassification.REMOTE)).isFalse()
            assertThat(gate.permissionRequired(LocalNetworkClassification.LOOPBACK)).isFalse()
            assertThat(gate.permissionRequired(LocalNetworkClassification.AMBIGUOUS)).isFalse()
        }
    }

    @Test
    fun `grant maps to granted`() {
        val gate = LocalNetworkPermissionGate(LocalNetworkPermissionGate.ANDROID_17_API)

        assertThat(gate.resolveRequestResult(granted = true, rationaleAvailable = false))
            .isEqualTo(LocalNetworkPermissionState.GRANTED)
    }

    @Test
    fun `denial maps to typed states`() {
        val gate = LocalNetworkPermissionGate(LocalNetworkPermissionGate.ANDROID_17_API)

        assertThat(gate.resolveRequestResult(granted = false, rationaleAvailable = true))
            .isEqualTo(LocalNetworkPermissionState.DENIED)
        assertThat(gate.resolveRequestResult(granted = false, rationaleAvailable = false))
            .isEqualTo(LocalNetworkPermissionState.PERMANENTLY_DENIED)
    }
}
