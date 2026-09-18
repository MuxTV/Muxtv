package app.muxtv.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.database.measurement.C03ProductionRoomCorrectnessRunner
import app.muxtv.database.measurement.C03ProductionScenario
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class X05RefreshOverlaySurvivalTest {
    private val runner = C03ProductionRoomCorrectnessRunner(
        ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun providerRefreshPreservesSupportedOverlayAndRecentState() = runTest {
        val scenarios = listOf(
            C03ProductionScenario.DELTA_0,
            C03ProductionScenario.DELTA_10,
            C03ProductionScenario.REORDER,
            C03ProductionScenario.TOKEN_CHURN,
        )

        scenarios.forEach { scenario ->
            val result = runner.runOverlaySurvivalScenario(
                scenario = scenario,
                entryCount = ENTRY_COUNT,
                overlayIndex = OVERLAY_INDEX,
            )

            assertThat(result.before).isEqualTo(result.after)
            assertThat(result.before.isFavorite).isTrue()
            assertThat(result.before.isHidden).isTrue()
            assertThat(result.before.customName).isEqualTo(CUSTOM_NAME)
            assertThat(result.before.channelNumber).isEqualTo(CUSTOM_NUMBER)
            assertThat(result.before.lastSuccessfulPlaybackAtEpochMillis)
                .isEqualTo(RECENT_AT_EPOCH_MILLIS)

            assertThat(result.productionCanonicalChannelId)
                .isEqualTo(result.logicalChannelId)
            assertThat(result.candidateCanonicalChannelId)
                .isEqualTo(result.logicalChannelId)
        }
    }

    private companion object {
        const val ENTRY_COUNT = 120
        const val OVERLAY_INDEX = 42
        const val CUSTOM_NAME = "My News"
        const val CUSTOM_NUMBER = 77
        const val RECENT_AT_EPOCH_MILLIS = 42_000L
    }
}
