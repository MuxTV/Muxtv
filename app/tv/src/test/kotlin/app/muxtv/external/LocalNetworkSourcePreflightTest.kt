package app.muxtv.external

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalNetworkSourcePreflightTest {
    @Test
    fun `api36 never requires runtime local-network permission`() {
        var permissionChecks = 0
        val preflight = LocalNetworkSourcePreflight(
            apiLevel = 36,
            permissionGranted = {
                permissionChecks += 1
                false
            },
        )

        assertThat(preflight.accessRequired("https://192.168.1.20/playlist.m3u")).isFalse()
        assertThat(permissionChecks).isEqualTo(0)
    }

    @Test
    fun `api37 local target requires missing permission`() {
        val preflight = LocalNetworkSourcePreflight(
            apiLevel = 37,
            permissionGranted = { false },
        )

        assertThat(preflight.accessRequired("https://192.168.1.20/playlist.m3u")).isTrue()
        assertThat(preflight.accessRequired("192.168.1.21:8080")).isTrue()
        assertThat(preflight.accessRequired("https://box.local/get.php")).isTrue()
        assertThat(preflight.accessRequired("https://[fd00::20]:8080/get.php")).isTrue()
    }

    @Test
    fun `api37 granted permission allows local target`() {
        val preflight = LocalNetworkSourcePreflight(
            apiLevel = 37,
            permissionGranted = { true },
        )

        assertThat(preflight.accessRequired("http://100.64.1.2:8080/get.php")).isFalse()
    }

    @Test
    fun `api37 remote loopback and ambiguous targets do not request broad permission`() {
        var permissionChecks = 0
        val preflight = LocalNetworkSourcePreflight(
            apiLevel = 37,
            permissionGranted = {
                permissionChecks += 1
                false
            },
        )

        assertThat(preflight.accessRequired("https://127.0.0.1/list.m3u")).isFalse()
        assertThat(preflight.accessRequired("https://localhost/list.m3u")).isFalse()
        assertThat(preflight.accessRequired("https://8.8.8.8/list.m3u")).isFalse()
        assertThat(preflight.accessRequired("https://provider.example/list.m3u")).isFalse()
        assertThat(preflight.accessRequired("not a valid endpoint")).isFalse()
        assertThat(permissionChecks).isEqualTo(0)
    }
}
