package app.muxtv

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleartextTransportContractTest {
    @Test
    fun platformPermitsCleartextCapabilityForDynamicIptvOrigins() {
        val policy = NetworkSecurityPolicy.getInstance()

        assertThat(policy.isCleartextTrafficPermitted).isTrue()
        assertThat(policy.isCleartextTrafficPermitted("127.0.0.1")).isTrue()
        assertThat(policy.isCleartextTrafficPermitted("provider.example")).isTrue()
    }
}
