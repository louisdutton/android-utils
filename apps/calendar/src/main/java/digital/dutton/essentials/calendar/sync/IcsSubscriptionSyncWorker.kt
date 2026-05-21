package digital.dutton.essentials.calendar.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Data
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class IcsSubscriptionSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val syncer = IcsSubscriptionSyncer(applicationContext)
        val subscriptionId = inputData.getString(KeySubscriptionId)

        return runCatching {
            if (subscriptionId == null) {
                syncer.syncAll()
            } else {
                syncer.syncSubscription(subscriptionId)
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (runAttemptCount < MaxAttempts) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            },
        )
    }

    companion object {
        fun enqueueOneTime(
            context: Context,
            subscriptionId: String? = null,
        ) {
            val request = OneTimeWorkRequestBuilder<IcsSubscriptionSyncWorker>()
                .setConstraints(NetworkConstraints)
                .setInputData(subscriptionWorkData(subscriptionId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                oneTimeWorkName(subscriptionId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun enqueuePeriodic(
            context: Context,
            subscriptionId: String,
        ) {
            val request = PeriodicWorkRequestBuilder<IcsSubscriptionSyncWorker>(
                RepeatIntervalHours,
                TimeUnit.HOURS,
            )
                .setConstraints(NetworkConstraints)
                .setInputData(workDataOf(KeySubscriptionId to subscriptionId))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                periodicWorkName(subscriptionId),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun enqueuePeriodicForAll(context: Context) {
            CalendarSubscriptionStore(context).list().forEach { subscription ->
                enqueuePeriodic(context, subscription.id)
            }
        }

        fun cancel(
            context: Context,
            subscriptionId: String,
        ) {
            WorkManager.getInstance(context).cancelUniqueWork(oneTimeWorkName(subscriptionId))
            WorkManager.getInstance(context).cancelUniqueWork(periodicWorkName(subscriptionId))
        }

        private fun oneTimeWorkName(subscriptionId: String?): String {
            return "ics-subscription-sync-${subscriptionId ?: "all"}"
        }

        private fun periodicWorkName(subscriptionId: String): String {
            return "ics-subscription-periodic-sync-$subscriptionId"
        }

        private fun subscriptionWorkData(subscriptionId: String?): Data {
            return if (subscriptionId == null) {
                Data.EMPTY
            } else {
                workDataOf(KeySubscriptionId to subscriptionId)
            }
        }

        private val NetworkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private const val KeySubscriptionId = "subscriptionId"
        private const val RepeatIntervalHours = 8L
        private const val MaxAttempts = 3
    }
}
