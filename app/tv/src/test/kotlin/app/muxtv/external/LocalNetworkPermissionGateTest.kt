package app.muxtv.external

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalNetworkPermissionGateTest {
    @Test
    fun `below android 16 no permission is ever required`() {
        for (apiLevel in listOf(26, 33, 35)) {
            val gate = LocalNetworkPermissionGate(apiLevel)
            assertThat(gate.permissionRequired(LocalNetworkClassification.LOCAL)).isFalse()
            assertThat(gate.permissionRequired(LocalNetworkClassification.REMOTE)).isFalse()
            assertThat(gate.permissionRequired(LocalNetworkClassification.AMBIGUOUS)).isFalse()
        }
    }

    @Test
    fun `android 16 and newer require permission only for local targets`() {
        for (apiLevel in listOf(
            LocalNetworkPermissionGate.ANDROID_16_API,
            LocalNetworkPermissionGate.ANDROID_16_API + 1,
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
        val gate = LocalNetworkPermissionGate(LocalNetworkPermissionGate.ANDROID_16_API)

        assertThat(gate.resolveRequestResult(granted = true, rationaleAvailable = false))
            .isEqualTo(LocalNetworkPermissionState.GRANTED)
    }

    @Test
    fun `denial maps to typed states`() {
        val gate = LocalNetworkPermissionGate(LocalNetworkPermissionGate.ANDROID_16_API)

        assertThat(gate.resolveRequestResult(granted = false, rationaleAvailable = true))
            .isEqualTo(LocalNetworkPermissionState.DENIED)
        assertThat(gate.resolveRequestResult(granted = false, rationaleAvailable = false))
            .isEqualTo(LocalNetworkPermissionState.PERMANENTLY_DENIED)
    }
}
