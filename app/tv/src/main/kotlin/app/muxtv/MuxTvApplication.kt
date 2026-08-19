package app.muxtv

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.sync.EpgRefreshScheduler
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.database.DatabaseInitializer
import app.muxtv.database.EpgMatchingStore
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class MuxTvApplication : Application(), Configuration.Provider {
    @Inject lateinit var databaseInitializer: Lazy<DatabaseInitializer>
    @Inject lateinit var epgMatchingStore: Lazy<EpgMatchingStore>
    @Inject lateinit var sourceRefreshScheduler: Lazy<SourceRefreshScheduler>
    @Inject lateinit var epgRefreshScheduler: Lazy<EpgRefreshScheduler>
    @Inject lateinit var durableRemoteSourceOnboarding: Lazy<DurableRemoteSourceOnboarding>
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject @ApplicationIoScope lateinit var applicationScope: Lazy<CoroutineScope>

    private val startupGate by lazy(LazyThreadSafetyMode.NONE) {
        UserUnlockedStartupGate(
            isUserUnlocked = {
                getSystemService(UserManager::class.java).isUserUnlocked
            },
            registerUnlockListener = ::registerUserUnlockListener,
            onUnlocked = ::startCredentialEncryptedStartup,
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        startupGate.start()
    }

    private fun startCredentialEncryptedStartup() {
        applicationScope.get().launch {
            databaseInitializer.get().initialize()
            repairStaleEpgMatchingBestEffort()
            durableRemoteSourceOnboarding.get().cleanupExpired()
            sourceRefreshScheduler.get().reconcile()
            epgRefreshScheduler.get().reconcile()
        }
    }

    private fun registerUserUnlockListener(onUnlocked: () -> Unit): UserUnlockRegistration {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                    onUnlocked()
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return UserUnlockRegistration {
            unregisterReceiver(receiver)
        }
    }

    private suspend fun repairStaleEpgMatchingBestEffort() {
        try {
            epgMatchingStore.get().reconcileAllIfStale()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Startup must continue: matching is derived local state and refresh publication can repair it later.
        }
    }
}
