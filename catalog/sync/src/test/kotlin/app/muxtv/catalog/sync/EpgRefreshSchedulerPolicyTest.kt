package app.muxtv.catalog.sync

import androidx.work.NetworkType
import app.muxtv.database.EpgRefreshPolicy
import app.muxtv.database.EpgRefreshTrigger
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgRefreshSchedulerPolicyTest {
    @Test
    fun `startup refresh inherits durable policy constraints`() {
        val constraints = epgOneShotConstraints(
            trigger = EpgRefreshTrigger.STARTUP,
            policy = EpgRefreshPolicy(
                sourceId = "epg-1",
                enabled = true,
                intervalMinutes = 60,
                unmeteredOnly = true,
                requiresCharging = true,
                updatedAtEpochMillis = 1,
            ),
        )

        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(constraints.requiresCharging()).isTrue()
    }

    @Test
    fun `manual refresh remains connected without periodic policy gating`() {
        val constraints = epgOneShotConstraints(
            trigger = EpgRefreshTrigger.MANUAL,
            policy = null,
        )

        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(constraints.requiresCharging()).isFalse()
    }
}
