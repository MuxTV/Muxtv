package app.muxtv.catalog.sync

import app.muxtv.catalog.refresh.RemoteSourceNetworkFailureReason
import app.muxtv.catalog.refresh.RemoteSourceRefreshResult
import app.muxtv.database.SourceRefreshRunState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceRefreshOutcomeMapperTest {
    @Test
    fun `successful refresh records activated revision`() {
        val decision = SourceRefreshOutcomeMapper.map(
            RemoteSourceRefreshResult.Refreshed(
                revisionNumber = 3,
                previousRevisionNumber = 2,
                entryCount = 120,
                skippedEntries = 4,
                warningCount = 2,
            ),
        )

        assertThat(decision.state).isEqualTo(SourceRefreshRunState.SUCCEEDED)
        assertThat(decision.revisionNumber).isEqualTo(3)
        assertThat(decision.parsedEntries).isEqualTo(120)
        assertThat(decision.retryable).isFalse()
    }

    @Test
    fun `transient http failure retries`() {
        val decision = SourceRefreshOutcomeMapper.map(RemoteSourceRefreshResult.HttpFailure(503))

        assertThat(decision.state).isEqualTo(SourceRefreshRunState.FAILED)
        assertThat(decision.resultFamily).isEqualTo("HTTP")
        assertThat(decision.retryable).isTrue()
    }

    @Test
    fun `authorization http failure needs authentication without retry`() {
        val decision = SourceRefreshOutcomeMapper.map(RemoteSourceRefreshResult.HttpFailure(401))

        assertThat(decision.state).isEqualTo(SourceRefreshRunState.NEEDS_AUTH)
        assertThat(decision.retryable).isFalse()
    }

    @Test
    fun `tls failure is explicit and not automatically retried`() {
        val decision = SourceRefreshOutcomeMapper.map(
            RemoteSourceRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Tls),
        )

        assertThat(decision.resultFamily).isEqualTo("NETWORK")
        assertThat(decision.resultCode).isEqualTo("TLS")
        assertThat(decision.retryable).isFalse()
    }

    @Test
    fun `network IO failure uses stable code and retries`() {
        val decision = SourceRefreshOutcomeMapper.map(
            RemoteSourceRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Io),
        )

        assertThat(decision.resultCode).isEqualTo("IO")
        assertThat(decision.retryable).isTrue()
    }
}
