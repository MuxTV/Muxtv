package app.muxtv.catalog.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.muxtv.database.EpgRefreshPolicy
import app.muxtv.database.EpgRefreshStore
import app.muxtv.database.EpgRefreshTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgRefreshScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val refreshStore: EpgRefreshStore,
) {
    private val applicationContext = context.applicationContext

    suspend fun updatePolicy(policy: EpgRefreshPolicy) {
        refreshStore.upsertPolicy(policy)
        applyPolicy(policy)
    }

    suspend fun removePolicy(sourceId: String) {
        require(sourceId.isNotBlank())
        cancel(sourceId)
        refreshStore.removePolicy(sourceId)
    }

    suspend fun reconcile() {
        refreshStore.getPolicies().forEach { policy ->
            applyPolicy(policy)
            if (policy.enabled) refreshNow(policy.sourceId, EpgRefreshTrigger.STARTUP)
        }
    }

    fun refreshNow(sourceId: String) {
        refreshNow(sourceId, EpgRefreshTrigger.MANUAL)
    }

    fun refreshNow(
        sourceId: String,
        trigger: EpgRefreshTrigger,
    ) {
        require(sourceId.isNotBlank())
        require(trigger != EpgRefreshTrigger.PERIODIC) {
            "Periodic EPG refresh must be scheduled through policy reconciliation."
        }
        val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
            .setInputData(inputData(sourceId, trigger))
            .setConstraints(networkConstraints(unmeteredOnly = false, requiresCharging = false))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(EpgRefreshWorkNames.TAG_ALL)
            .addTag(EpgRefreshWorkNames.sourceTag(sourceId))
            .build()

        workManager().enqueueUniqueWork(
            EpgRefreshWorkNames.immediate(sourceId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(sourceId: String) {
        require(sourceId.isNotBlank())
        workManager().cancelUniqueWork(EpgRefreshWorkNames.immediate(sourceId))
        workManager().cancelUniqueWork(EpgRefreshWorkNames.periodic(sourceId))
    }

    private fun applyPolicy(policy: EpgRefreshPolicy) {
        if (!policy.enabled) {
            workManager().cancelUniqueWork(EpgRefreshWorkNames.periodic(policy.sourceId))
            return
        }

        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(
            policy.intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setInputData(inputData(policy.sourceId, EpgRefreshTrigger.PERIODIC))
            .setConstraints(
                networkConstraints(
                    unmeteredOnly = policy.unmeteredOnly,
                    requiresCharging = policy.requiresCharging,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(EpgRefreshWorkNames.TAG_ALL)
            .addTag(EpgRefreshWorkNames.sourceTag(policy.sourceId))
            .build()

        workManager().enqueueUniquePeriodicWork(
            EpgRefreshWorkNames.periodic(policy.sourceId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun inputData(
        sourceId: String,
        trigger: EpgRefreshTrigger,
    ): Data = Data.Builder()
        .putString(EpgRefreshWorker.KEY_SOURCE_ID, sourceId)
        .putString(EpgRefreshWorker.KEY_TRIGGER, trigger.name)
        .build()

    private fun networkConstraints(
        unmeteredOnly: Boolean,
        requiresCharging: Boolean,
    ): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(requiresCharging)
        .build()

    private fun workManager(): WorkManager = WorkManager.getInstance(applicationContext)

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
