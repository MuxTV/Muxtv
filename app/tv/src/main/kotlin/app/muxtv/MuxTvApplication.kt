package app.muxtv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.sync.EpgRefreshScheduler
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.database.DatabaseInitializer
import app.muxtv.database.EpgMatchingStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class MuxTvApplication : Application(), Configuration.Provider {
    @Inject lateinit var databaseInitializer: DatabaseInitializer
    @Inject lateinit var epgMatchingStore: EpgMatchingStore
    @Inject lateinit var sourceRefreshScheduler: SourceRefreshScheduler
    @Inject lateinit var epgRefreshScheduler: EpgRefreshScheduler
    @Inject lateinit var durableRemoteSourceOnboarding: DurableRemoteSourceOnboarding
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject @ApplicationIoScope lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            databaseInitializer.initialize()
            repairStaleEpgMatchingBestEffort()
            durableRemoteSourceOnboarding.cleanupExpired()
            sourceRefreshScheduler.reconcile()
            epgRefreshScheduler.reconcile()
        }
    }

    private suspend fun repairStaleEpgMatchingBestEffort() {
        try {
            epgMatchingStore.reconcileAllIfStale()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Startup must continue: matching is derived local state and refresh publication can repair it later.
        }
    }
}
