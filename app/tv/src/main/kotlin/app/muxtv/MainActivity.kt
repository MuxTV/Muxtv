package app.muxtv

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.database.SourceRefreshStore
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.sources.SourceEntryOnboarding
import app.muxtv.feature.sources.SourcePlaybackApprovalActions
import app.muxtv.navigation.AppNavigation
import app.muxtv.player.PlaybackObservationReader
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var doctorExportState: DoctorExportState
    private val doctorDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
        ::finishDoctorExport,
    )

    @Inject
    lateinit var playbackCatalog: PlaybackCatalog

    @Inject
    lateinit var channelBrowseRepository: ChannelBrowseRepository

    @Inject
    lateinit var channelPreferencesRepository: ChannelPreferencesRepository

    @Inject
    lateinit var channelSearchRepository: ChannelSearchRepository

    @Inject
    lateinit var guideWindowRepository: GuideWindowRepository

    @Inject
    lateinit var controllerConnector: MuxTvMediaControllerConnector

    @Inject
    lateinit var sourceRefreshStore: SourceRefreshStore

    @Inject
    lateinit var sourceRefreshScheduler: SourceRefreshScheduler

    @Inject
    lateinit var sourceEntryOnboarding: SourceEntryOnboarding

    @Inject
    lateinit var sourcePlaybackApprovalActions: SourcePlaybackApprovalActions

    @Inject
    lateinit var playbackObservationReader: PlaybackObservationReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        doctorExportState = DoctorExportState(
            restoredPendingReport = savedInstanceState?.getString(PENDING_DOCTOR_REPORT_KEY),
            restoredDestinationUri = savedInstanceState?.getString(PENDING_DOCTOR_DESTINATION_KEY),
        )
        setContent {
            MuxTvTheme {
                AppNavigation(
                    playbackCatalog = playbackCatalog,
                    channelBrowseRepository = channelBrowseRepository,
                    channelPreferencesRepository = channelPreferencesRepository,
                    channelSearchRepository = channelSearchRepository,
                    guideWindowRepository = guideWindowRepository,
                    controllerConnector = controllerConnector,
                    sourceRefreshStore = sourceRefreshStore,
                    sourceRefreshScheduler = sourceRefreshScheduler,
                    sourceEntryOnboarding = sourceEntryOnboarding,
                    sourcePlaybackApprovalActions = sourcePlaybackApprovalActions,
                    playbackObservationReader = playbackObservationReader,
                    doctorExportStatus = doctorExportState.status,
                    onExportDoctorReport = ::beginDoctorExport,
                )
            }
        }
        doctorExportState.pendingExportForResume()?.let(::writeDoctorExport)
    }

    private fun beginDoctorExport(report: String) {
        if (!doctorExportState.begin(report)) return
        try {
            doctorDocumentLauncher.launch(DOCTOR_REPORT_FILE_NAME)
        } catch (_: RuntimeException) {
            doctorExportState.launchFailed()
        }
    }

    private fun finishDoctorExport(uri: Uri?) {
        if (uri == null) {
            doctorExportState.cancelDestination()
            return
        }
        val pendingExport = doctorExportState.selectDestination(uri.toString()) ?: return
        writeDoctorExport(pendingExport)
    }

    private fun writeDoctorExport(pendingExport: PendingDoctorExport) {
        lifecycleScope.launch {
            val exported = withContext(Dispatchers.IO) {
                runCatching {
                    val destination = Uri.parse(pendingExport.destinationUri)
                    val stream = requireNotNull(contentResolver.openOutputStream(destination, "wt"))
                    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(pendingExport.report)
                    }
                }.isSuccess
            }
            doctorExportState.writeFinished(exported)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_DOCTOR_REPORT_KEY, doctorExportState.pendingReportForSave())
        outState.putString(
            PENDING_DOCTOR_DESTINATION_KEY,
            doctorExportState.pendingDestinationUriForSave(),
        )
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val DOCTOR_REPORT_FILE_NAME = "muxtv-doctor-report.txt"
        const val PENDING_DOCTOR_REPORT_KEY = "doctor.pending_report"
        const val PENDING_DOCTOR_DESTINATION_KEY = "doctor.pending_destination"
    }
}
