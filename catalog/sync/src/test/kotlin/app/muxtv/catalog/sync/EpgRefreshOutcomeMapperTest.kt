package app.muxtv.catalog.sync

import app.muxtv.catalog.refresh.EpgHttpValidators
import app.muxtv.catalog.refresh.RemoteEpgRefreshResult
import app.muxtv.catalog.refresh.RemoteSourceNetworkFailureReason
import app.muxtv.database.EpgRefreshCompletion
import app.muxtv.database.EpgRefreshRunState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgRefreshOutcomeMapperTest {
    @Test
    fun `not modified is successful without a revision`() {
        val decision = EpgRefreshOutcomeMapper.map(
            RemoteEpgRefreshResult.NotModified(
                validators = EpgHttpValidators(etag = "etag-1"),
            ),
        )

        assertThat(decision.workSucceeded).isTrue()
        assertThat(decision.retryable).isFalse()
        val completion = decision.toCompletion(200, "access-a")
        assertThat(completion).isInstanceOf(EpgRefreshCompletion.NotModified::class.java)
        completion as EpgRefreshCompletion.NotModified
        assertThat(completion.validators.etag).isEqualTo("etag-1")
    }

    @Test
    fun `superseded is a non retrying cancellation`() {
        val decision = EpgRefreshOutcomeMapper.map(RemoteEpgRefreshResult.Superseded)

        assertThat(decision.workSucceeded).isTrue()
        assertThat(decision.retryable).isFalse()
        val completion = decision.toCompletion(200, "access-a")
        assertThat(completion).isInstanceOf(EpgRefreshCompletion.Terminal::class.java)
        completion as EpgRefreshCompletion.Terminal
        assertThat(completion.state).isEqualTo(EpgRefreshRunState.CANCELLED)
        assertThat(completion.resultFamily).isEqualTo(EpgRefreshCompletion.RESULT_FAMILY)
        assertThat(completion.resultCode).isEqualTo(EpgRefreshCompletion.RESULT_SUPERSEDED)
    }

    @Test
    fun `server failure retries while auth failure does not`() {
        val server = EpgRefreshOutcomeMapper.map(RemoteEpgRefreshResult.HttpFailure(503))
        val auth = EpgRefreshOutcomeMapper.map(RemoteEpgRefreshResult.HttpFailure(401))

        assertThat(server.retryable).isTrue()
        assertThat(server.workSucceeded).isFalse()
        assertThat(auth.retryable).isFalse()
        val authCompletion = auth.toCompletion(200, "access-a") as EpgRefreshCompletion.Terminal
        assertThat(authCompletion.state).isEqualTo(EpgRefreshRunState.NEEDS_AUTH)
        assertThat(authCompletion.httpStatus).isEqualTo(401)
    }

    @Test
    fun `transient network failure retries`() {
        val decision = EpgRefreshOutcomeMapper.map(
            RemoteEpgRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Timeout),
        )

        assertThat(decision.retryable).isTrue()
        val completion = decision.toCompletion(200, "access-a") as EpgRefreshCompletion.Terminal
        assertThat(completion.state).isEqualTo(EpgRefreshRunState.FAILED)
    }
}
