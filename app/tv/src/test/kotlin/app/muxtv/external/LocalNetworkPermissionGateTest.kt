package app.muxtv.external

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalNetworkPermissionGateTest {
    @Test
    fun `below android 17 no permission is ever required`() {
        for (apiLevel in listOf(26, 33, 36)) {
            val gate = LocalNetworkPermissionGate(apiLevel)
            assertThat(gate.permissionRequired(LocalNetworkClassification.LOCAL)).isFalse()
            assertThat(gate.permissionRequired(LocalNetworkClassification.REMOTE)).isFalse()
            assertThat(gate.permissionRequired(LocalNetworkClassification.AMBIGUOUS)).isFalse()
        }
    }

    @Test
    fun `android 17 requires permission only for local targets`() {
        val gate = LocalNetworkPermissionGate(LocalNetworkPermissionGate.ANDROID_17_API)

        assertThat(gate.permissionRequired(LocalNetworkClassification.LOCAL)).isTrue()
        assertThat(gate.permissionRequired(LocalNetworkClassification.REMOTE)).isFalse()
        assertThat(gate.permissionRequired(LocalNetworkClassification.LOOPBACK)).isFalse()
        assertThat(gate.permissionRequired(LocalNetworkClassification.AMBIGUOUS)).isFalse()
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
