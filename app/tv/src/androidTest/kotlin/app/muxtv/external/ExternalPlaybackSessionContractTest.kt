package app.muxtv.external

import android.os.Bundle
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.player.ExternalPlaybackLeaseId
import app.muxtv.player.ExternalPlaybackStartFailure
import app.muxtv.player.ExternalPlaybackStartResult
import app.muxtv.player.media3.ExternalPlaybackSessionContract
import app.muxtv.player.media3.ExternalPlaybackSetupCommand
import app.muxtv.player.media3.PlaybackSetupId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalPlaybackSessionContractTest {
    @Test
    fun setupArgsRoundTripPreservesOnlyLeaseIdentity() {
        val setupId = PlaybackSetupId.create()
        val leaseId = ExternalPlaybackLeaseId.create()

        val parsed = ExternalPlaybackSessionContract.parseSetupArgs(
            ExternalPlaybackSessionContract.setupArgs(setupId, leaseId),
        )

        assertThat(parsed).isEqualTo(
            ExternalPlaybackSetupCommand(id = setupId, leaseId = leaseId),
        )
    }

    @Test
    fun setupArgsRejectUnexpectedKeys() {
        val args = ExternalPlaybackSessionContract.setupArgs(
            PlaybackSetupId.create(),
            ExternalPlaybackLeaseId.create(),
        ).apply { putString("uri", "http://should-not-cross/secret") }

        assertThat(ExternalPlaybackSessionContract.parseSetupArgs(args)).isNull()
    }

    @Test
    fun startedResultRoundTrips() {
        val parsed = ExternalPlaybackSessionContract.parseResult(
            ExternalPlaybackSessionContract.result(ExternalPlaybackStartResult.Started),
        )

        assertThat(parsed).isEqualTo(ExternalPlaybackStartResult.Started)
    }

    @Test
    fun rejectedResultRoundTripsWithFailureAndEvidence() {
        val original = ExternalPlaybackStartResult.Rejected(
            reason = ExternalPlaybackStartFailure.PlaybackFailed,
            observationAvailable = true,
        )

        val parsed = ExternalPlaybackSessionContract.parseResult(
            ExternalPlaybackSessionContract.result(original),
        )

        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun nonSuccessResultCodesDoNotProducePlaybackResults() {
        assertThat(ExternalPlaybackSessionContract.parseResult(SessionResult(1))).isNull()
    }

    @Test
    fun rejectedResultsWithUnexpectedKeysAreRejected() {
        val extras = ExternalPlaybackSessionContract.result(
            ExternalPlaybackStartResult.Rejected(
                ExternalPlaybackStartFailure.LeaseUnavailable,
            ),
        ).extras.apply { putString("sneaky", "value") }

        assertThat(ExternalPlaybackSessionContract.parseResult(SessionResult(0, extras)))
            .isNull()
    }

    @Test
    fun setupCommandToStringNeverRevealsIdentifiers() {
        val command = ExternalPlaybackSetupCommand(
            id = PlaybackSetupId.create(),
            leaseId = ExternalPlaybackLeaseId.create(),
        )

        assertThat(command.toString()).doesNotContain(command.id.encoded())
        assertThat(command.toString()).doesNotContain(command.leaseId.encoded())
    }

    @Test
    fun bundleNeverContainsLocatorOrMime() {
        val args = ExternalPlaybackSessionContract.setupArgs(
            PlaybackSetupId.create(),
            ExternalPlaybackLeaseId.create(),
        )

        assertThat(args.keySet()).containsExactly("setup_id", "lease_id")
        assertThat(flatten(args)).doesNotContain("http")
    }

    private fun flatten(bundle: Bundle): String = bundle.keySet()
        .joinToString { "$it=${bundle.get(it)}" }
}
