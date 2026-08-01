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

    @Test
    fun `manual and startup use trigger-distinct unique work identities`() {
        val manual = EpgRefreshWorkNames.immediate("epg-1", EpgRefreshTrigger.MANUAL)
        val startup = EpgRefreshWorkNames.immediate("epg-1", EpgRefreshTrigger.STARTUP)

        assertThat(manual).isNotEqualTo(startup)
        assertThat(manual).contains("epg-1")
        assertThat(startup).contains("epg-1")
    }

    @Test
    fun `disabling policy cancels startup and periodic but not manual work`() {
        val policyOwned = epgPolicyOwnedWorkNames("epg-1")

        assertThat(policyOwned).containsExactly(
            EpgRefreshWorkNames.immediate("epg-1", EpgRefreshTrigger.STARTUP),
            EpgRefreshWorkNames.periodic("epg-1"),
        )
        assertThat(policyOwned).doesNotContain(
            EpgRefreshWorkNames.immediate("epg-1", EpgRefreshTrigger.MANUAL),
        )
    }
}
