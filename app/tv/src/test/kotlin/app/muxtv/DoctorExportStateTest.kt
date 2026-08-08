package app.muxtv

import app.muxtv.feature.doctor.DoctorExportStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DoctorExportStateTest {
    @Test
    fun `restored pending report remains available for activity result`() {
        val restored = DoctorExportState(restoredPendingReport = "redacted-report")

        assertThat(restored.status).isEqualTo(DoctorExportStatus.AWAITING_DESTINATION)
        assertThat(restored.pendingReportForSave()).isEqualTo("redacted-report")
        assertThat(restored.pendingExportForResume()).isNull()
    }

    @Test
    fun `cancelled destination clears pending report without reporting failure`() {
        val state = DoctorExportState()
        assertThat(state.begin("redacted-report")).isTrue()

        state.cancelDestination()

        assertThat(state.status).isEqualTo(DoctorExportStatus.IDLE)
        assertThat(state.pendingReportForSave()).isNull()
    }

    @Test
    fun `second launch is rejected while destination is pending`() {
        val state = DoctorExportState()

        assertThat(state.begin("first")).isTrue()
        assertThat(state.begin("second")).isFalse()
        assertThat(state.pendingReportForSave()).isEqualTo("first")
    }

    @Test
    fun `selected destination and report survive recreation until write finishes`() {
        val state = DoctorExportState()
        state.begin("redacted-report")

        val selected = state.selectDestination("content://doctor-report")
        val restored = DoctorExportState(
            restoredPendingReport = state.pendingReportForSave(),
            restoredDestinationUri = state.pendingDestinationUriForSave(),
        )

        assertThat(selected).isEqualTo(
            PendingDoctorExport("redacted-report", "content://doctor-report"),
        )
        assertThat(restored.pendingExportForResume()).isEqualTo(selected)
        assertThat(restored.status).isEqualTo(DoctorExportStatus.AWAITING_DESTINATION)

        restored.writeFinished(succeeded = true)

        assertThat(restored.pendingReportForSave()).isNull()
        assertThat(restored.pendingDestinationUriForSave()).isNull()
        assertThat(restored.status).isEqualTo(DoctorExportStatus.EXPORTED)
    }
}
