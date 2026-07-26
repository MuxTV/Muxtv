package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaControllerOperationFailureTest {
    @Test
    fun `connection failures map to stable categories`() = runTest {
        val timeout = runCatching {
            withTimeout(1) { delay(2) }
        }.exceptionOrNull()!!

        assertThat(connectionFailureFor(ControllerConnectionRegistryClosedException()))
            .isEqualTo(MediaControllerOperationFailure.ConnectorClosed)
        assertThat(connectionFailureFor(timeout))
            .isEqualTo(MediaControllerOperationFailure.ConnectionTimedOut)
        assertThat(connectionFailureFor(ListenableFutureCancelledException()))
            .isEqualTo(MediaControllerOperationFailure.ConnectionCancelled)
        assertThat(connectionFailureFor(IllegalStateException("synthetic connection failure")))
            .isEqualTo(MediaControllerOperationFailure.ConnectionFailed)
    }

    @Test
    fun `command failures map to stable categories`() = runTest {
        val timeout = runCatching {
            withTimeout(1) { delay(2) }
        }.exceptionOrNull()!!

        assertThat(commandFailureFor(timeout))
            .isEqualTo(MediaControllerOperationFailure.CommandTimedOut)
        assertThat(commandFailureFor(ListenableFutureCancelledException()))
            .isEqualTo(MediaControllerOperationFailure.CommandCancelled)
        assertThat(commandFailureFor(IllegalStateException("synthetic command failure")))
            .isEqualTo(MediaControllerOperationFailure.CommandFailed)
    }

    @Test
    fun `operation exception never contains raw cause text`() {
        val secretFixture = "https://provider.example/live.m3u8?token=controller-secret"
        val failure = connectionFailureFor(IllegalStateException(secretFixture))
        val error = MediaControllerOperationException(failure)

        assertThat(error.message).isEqualTo(
            "Media controller operation failed: ConnectionFailed",
        )
        assertThat(error.toString()).doesNotContain(secretFixture)
        assertThat(error.cause).isNull()
    }
}
