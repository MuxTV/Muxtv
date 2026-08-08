package app.muxtv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.muxtv.feature.doctor.DoctorExportStatus

internal class DoctorExportState(
    restoredPendingReport: String? = null,
    restoredDestinationUri: String? = null,
) {
    private var pendingReport: String? = restoredPendingReport
    private var pendingDestinationUri: String? = restoredDestinationUri

    var status by mutableStateOf(
        if (restoredPendingReport == null) {
            DoctorExportStatus.IDLE
        } else {
            DoctorExportStatus.AWAITING_DESTINATION
        },
    )
        private set

    fun begin(report: String): Boolean {
        if (status == DoctorExportStatus.AWAITING_DESTINATION) return false
        pendingReport = report
        pendingDestinationUri = null
        status = DoctorExportStatus.AWAITING_DESTINATION
        return true
    }

    fun pendingReportForSave(): String? = pendingReport

    fun pendingDestinationUriForSave(): String? = pendingDestinationUri

    fun selectDestination(destinationUri: String): PendingDoctorExport? {
        val report = pendingReport ?: return null
        pendingDestinationUri = destinationUri
        return PendingDoctorExport(report, destinationUri)
    }

    fun pendingExportForResume(): PendingDoctorExport? {
        val report = pendingReport ?: return null
        val destinationUri = pendingDestinationUri ?: return null
        return PendingDoctorExport(report, destinationUri)
    }

    fun cancelDestination() {
        pendingReport = null
        pendingDestinationUri = null
        status = DoctorExportStatus.IDLE
    }

    fun launchFailed() {
        pendingReport = null
        pendingDestinationUri = null
        status = DoctorExportStatus.FAILED
    }

    fun writeFinished(succeeded: Boolean) {
        pendingReport = null
        pendingDestinationUri = null
        status = if (succeeded) DoctorExportStatus.EXPORTED else DoctorExportStatus.FAILED
    }
}

internal data class PendingDoctorExport(
    val report: String,
    val destinationUri: String,
)
