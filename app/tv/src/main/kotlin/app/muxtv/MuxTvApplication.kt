package app.muxtv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.database.DatabaseInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class MuxTvApplication : Application(), Configuration.Provider {
    @Inject lateinit var databaseInitializer: DatabaseInitializer
    @Inject lateinit var sourceRefreshScheduler: SourceRefreshScheduler
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
            durableRemoteSourceOnboarding.cleanupExpired()
            sourceRefreshScheduler.reconcile()
        }
    }
}
