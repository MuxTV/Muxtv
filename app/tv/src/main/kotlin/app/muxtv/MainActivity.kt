package app.muxtv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.catalog.SourceManagement
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.external.LocalNetworkPermissionGate
import app.muxtv.external.LocalNetworkPermissionState
import app.muxtv.feature.sources.LocalNetworkPermissionOutcome
import app.muxtv.navigation.AppNavigation
import app.muxtv.player.PlaybackObservationReader
import app.muxtv.player.PlaybackSessionGateway
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var doctorExportState: DoctorExportState
    private var pendingLocalNetworkPermission: CompletableDeferred<LocalNetworkPermissionOutcome>? = null
    private var pendingLocalNetworkSettings: CompletableDeferred<Boolean>? = null

    private val doctorDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
        ::finishDoctorExport,
    )
    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
        ::finishLocalNetworkPermissionRequest,
    )
    private val localNetworkSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        pendingLocalNetworkSettings?.complete(hasLocalNetworkPermission())
    }

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
    lateinit var recentChannelsRepository: RecentChannelsRepository

    @Inject
    lateinit var epgGuideRepository: EpgGuideRepository

    @Inject
    lateinit var controllerConnector: MuxTvMediaControllerConnector

    @Inject
    lateinit var playbackSessionGateway: PlaybackSessionGateway

    @Inject
    lateinit var sourceManagement: SourceManagement

    @Inject
    lateinit var sourceOnboarding: SourceOnboarding

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
                    recentChannelsRepository = recentChannelsRepository,
                    epgGuideRepository = epgGuideRepository,
                    controllerConnector = controllerConnector,
                    playbackSessionGateway = playbackSessionGateway,
                    sourceManagement = sourceManagement,
                    sourceOnboarding = sourceOnboarding,
                    playbackObservationReader = playbackObservationReader,
                    doctorExportStatus = doctorExportState.status,
                    onExportDoctorReport = ::beginDoctorExport,
                    requestLocalNetworkPermission = ::requestLocalNetworkPermission,
                    openLocalNetworkPermissionSettings = ::openLocalNetworkPermissionSettings,
                )
            }
        }
        doctorExportState.pendingExportForResume()?.let(::writeDoctorExport)
    }

    private suspend fun requestLocalNetworkPermission(): LocalNetworkPermissionOutcome {
        if (hasLocalNetworkPermission()) return LocalNetworkPermissionOutcome.GRANTED

        pendingLocalNetworkPermission?.let { return it.await() }
        val pending = CompletableDeferred<LocalNetworkPermissionOutcome>()
        pendingLocalNetworkPermission = pending
        try {
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } catch (_: RuntimeException) {
            if (pendingLocalNetworkPermission === pending) pendingLocalNetworkPermission = null
            return LocalNetworkPermissionOutcome.DENIED
        }

        return try {
            pending.await()
        } finally {
            if (pendingLocalNetworkPermission === pending) pendingLocalNetworkPermission = null
        }
    }

    private fun finishLocalNetworkPermissionRequest(granted: Boolean) {
        val rationaleAvailable = !granted && shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
        val state = LocalNetworkPermissionGate(Build.VERSION.SDK_INT).resolveRequestResult(
            granted = granted,
            rationaleAvailable = rationaleAvailable,
        )
        pendingLocalNetworkPermission?.complete(state.toFeatureOutcome())
    }

    private suspend fun openLocalNetworkPermissionSettings(): Boolean {
        if (hasLocalNetworkPermission()) return true

        pendingLocalNetworkSettings?.let { return it.await() }
        val pending = CompletableDeferred<Boolean>()
        pendingLocalNetworkSettings = pending
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            localNetworkSettingsLauncher.launch(intent)
        } catch (_: RuntimeException) {
            if (pendingLocalNetworkSettings === pending) pendingLocalNetworkSettings = null
            return false
        }

        return try {
            pending.await()
        } finally {
            if (pendingLocalNetworkSettings === pending) pendingLocalNetworkSettings = null
        }
    }

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < LocalNetworkPermissionGate.ANDROID_17_API ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

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

    override fun onDestroy() {
        pendingLocalNetworkPermission?.cancel()
        pendingLocalNetworkSettings?.cancel()
        pendingLocalNetworkPermission = null
        pendingLocalNetworkSettings = null
        super.onDestroy()
    }

    private fun LocalNetworkPermissionState.toFeatureOutcome(): LocalNetworkPermissionOutcome = when (this) {
        LocalNetworkPermissionState.NOT_REQUIRED,
        LocalNetworkPermissionState.GRANTED,
        -> LocalNetworkPermissionOutcome.GRANTED

        LocalNetworkPermissionState.DENIED -> LocalNetworkPermissionOutcome.DENIED
        LocalNetworkPermissionState.PERMANENTLY_DENIED ->
            LocalNetworkPermissionOutcome.PERMANENTLY_DENIED
    }

    private companion object {
        const val DOCTOR_REPORT_FILE_NAME = "muxtv-doctor-report.txt"
        const val PENDING_DOCTOR_REPORT_KEY = "doctor.pending_report"
        const val PENDING_DOCTOR_DESTINATION_KEY = "doctor.pending_destination"
    }
}
