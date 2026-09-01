package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackLocalNetworkPermissionContractTest {
    @Test
    fun permissionRequiredResultCarriesOnlyStableVariantIdentity() {
        // The stable player boundary may identify the retry candidate, but must not expose
        // the secret-bearing resolved provider locator to presentation code.
        val result = PlaybackStartResult.LocalNetworkPermissionRequired(
            variantId = "variant-local",
        )

        assertThat(result.variantId).isEqualTo("variant-local")
        assertThat(result.toString()).doesNotContain("http://")
        assertThat(result.toString()).doesNotContain("https://")
    }
}
