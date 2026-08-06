package app.muxtv

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
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
    @Inject @ApplicationIoScope lateinit var applicationScope: CoroutineScope

    private lateinit var userUnlockedStartupGate: UserUnlockedStartupGate

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        val userManager = checkNotNull(getSystemService(UserManager::class.java)) {
            "UserManager is required to guard credential-encrypted startup"
        }
        userUnlockedStartupGate = UserUnlockedStartupGate(
            isUserUnlocked = { userManager.isUserUnlocked },
            registerUnlockListener = ::registerUserUnlockListener,
            onUnlocked = ::launchCredentialEncryptedStartup,
        )
        userUnlockedStartupGate.start()
    }

    private fun registerUserUnlockListener(onUnlocked: () -> Unit): UserUnlockRegistration {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                    onUnlocked()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        return UserUnlockRegistration {
            unregisterReceiver(receiver)
        }
    }

    private fun launchCredentialEncryptedStartup() {
        applicationScope.launch {
            databaseInitializer.get().initialize()
            repairStaleEpgMatchingBestEffort()
            durableRemoteSourceOnboarding.get().cleanupExpired()
            sourceRefreshScheduler.get().reconcile()
            epgRefreshScheduler.get().reconcile()
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
