package app.muxtv.feature.doctor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DoctorExportPolicyTest {
    @Test
    fun `export is disabled when diagnostic snapshot could not be read`() {
        assertThat(
            DoctorExportPolicy.isEnabled(
                snapshotReadSucceeded = false,
                exportStatus = DoctorExportStatus.IDLE,
            ),
        ).isFalse()
    }

    @Test
    fun `export is enabled only for a readable snapshot without pending destination`() {
        assertThat(
            DoctorExportPolicy.isEnabled(
                snapshotReadSucceeded = true,
                exportStatus = DoctorExportStatus.IDLE,
            ),
        ).isTrue()
        assertThat(
            DoctorExportPolicy.isEnabled(
                snapshotReadSucceeded = true,
                exportStatus = DoctorExportStatus.AWAITING_DESTINATION,
            ),
        ).isFalse()
    }
}
