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
import app.muxtv.database.SourceRefreshPolicy
import app.muxtv.database.SourceRefreshStore
import app.muxtv.database.SourceRefreshTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRefreshScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val refreshStore: SourceRefreshStore,
) {
    private val applicationContext = context.applicationContext

    suspend fun updatePolicy(policy: SourceRefreshPolicy) {
        refreshStore.upsertPolicy(policy)
        applyPolicy(policy)
    }

    suspend fun reconcile() {
        refreshStore.getPolicies().forEach(::applyPolicy)
    }

    fun refreshNow(
        sourceId: String,
        trigger: SourceRefreshTrigger = SourceRefreshTrigger.MANUAL,
    ) {
        require(sourceId.isNotBlank())
        val request = OneTimeWorkRequestBuilder<SourceRefreshWorker>()
            .setInputData(inputData(sourceId, trigger))
            .setConstraints(networkConstraints(unmeteredOnly = false, requiresCharging = false))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(SourceRefreshWorkNames.TAG_ALL)
            .addTag(SourceRefreshWorkNames.sourceTag(sourceId))
            .build()

        workManager().enqueueUniqueWork(
            SourceRefreshWorkNames.immediate(sourceId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(sourceId: String) {
        require(sourceId.isNotBlank())
        workManager().cancelUniqueWork(SourceRefreshWorkNames.immediate(sourceId))
        workManager().cancelUniqueWork(SourceRefreshWorkNames.periodic(sourceId))
    }

    private fun applyPolicy(policy: SourceRefreshPolicy) {
        if (!policy.enabled) {
            workManager().cancelUniqueWork(SourceRefreshWorkNames.periodic(policy.sourceId))
            return
        }

        val request = PeriodicWorkRequestBuilder<SourceRefreshWorker>(
            policy.intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setInputData(inputData(policy.sourceId, SourceRefreshTrigger.PERIODIC))
            .setConstraints(
                networkConstraints(
                    unmeteredOnly = policy.unmeteredOnly,
                    requiresCharging = policy.requiresCharging,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(SourceRefreshWorkNames.TAG_ALL)
            .addTag(SourceRefreshWorkNames.sourceTag(policy.sourceId))
            .build()

        workManager().enqueueUniquePeriodicWork(
            SourceRefreshWorkNames.periodic(policy.sourceId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun inputData(
        sourceId: String,
        trigger: SourceRefreshTrigger,
    ): Data = Data.Builder()
        .putString(SourceRefreshWorker.KEY_SOURCE_ID, sourceId)
        .putString(SourceRefreshWorker.KEY_TRIGGER, trigger.name)
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
