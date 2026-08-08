package app.muxtv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.doctor.DOCTOR_REFRESH_TEST_TAG
import app.muxtv.feature.doctor.DoctorExportStatus
import app.muxtv.feature.doctor.DoctorRoute
import app.muxtv.feature.doctor.doctorObservationTestTag
import app.muxtv.player.PlaybackObservation
import app.muxtv.player.PlaybackObservationKind
import app.muxtv.player.PlaybackObservationReader
import org.junit.Rule
import org.junit.Test

class DoctorFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dpadDownMovesFromActionsIntoDiagnosticEvents() {
        val reader = PlaybackObservationReader {
            List(12) { index ->
                PlaybackObservation(
                    kind = PlaybackObservationKind.ATTEMPT_STARTED,
                    attemptNumber = 1,
                    attemptLimit = 3,
                    timestampEpochMillis = index.toLong(),
                )
            }
        }
        composeRule.setContent {
            MuxTvTheme {
                DoctorRoute(
                    observationReader = reader,
                    exportStatus = DoctorExportStatus.IDLE,
                    onExport = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCTOR_REFRESH_TEST_TAG)
            .assertIsFocused()
            .performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(doctorObservationTestTag(0)).assertIsFocused()
    }
}
