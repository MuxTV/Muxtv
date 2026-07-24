package app.muxtv.catalog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RefreshRuntimePolicyTest {
    @Test
    fun `refresh timeout remains below stale lease threshold`() {
        assertThat(REFRESH_TIMEOUT_MILLIS).isLessThan(LEASE_STALE_AFTER_MILLIS)
    }
}
