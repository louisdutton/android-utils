package digital.dutton.essentials.calendar.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import digital.dutton.essentials.calendar.widget.AgendaWidgetProvider
import java.util.concurrent.TimeUnit

class CalDavSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val syncer = CalDavSyncer(applicationContext)
        val accountId = inputData.getString(KeyAccountId)

        return runCatching {
            if (accountId == null) {
                syncer.syncAll()
            } else {
                syncer.syncAccount(accountId)
            }
        }.fold(
            onSuccess = {
                AgendaWidgetProvider.updateAll(applicationContext)
                Result.success()
            },
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
            accountId: String? = null,
        ) {
            val request = OneTimeWorkRequestBuilder<CalDavSyncWorker>()
                .setConstraints(NetworkConstraints)
                .setInputData(accountWorkData(accountId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                oneTimeWorkName(accountId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun enqueuePeriodic(
            context: Context,
            accountId: String,
        ) {
            val request = PeriodicWorkRequestBuilder<CalDavSyncWorker>(
                RepeatIntervalHours,
                TimeUnit.HOURS,
            )
                .setConstraints(NetworkConstraints)
                .setInputData(workDataOf(KeyAccountId to accountId))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                periodicWorkName(accountId),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun enqueuePeriodicForAll(context: Context) {
            CalDavAccountStore(context).listAccounts().forEach { account ->
                enqueuePeriodic(context, account.id)
            }
        }

        fun cancel(
            context: Context,
            accountId: String,
        ) {
            WorkManager.getInstance(context).cancelUniqueWork(oneTimeWorkName(accountId))
            WorkManager.getInstance(context).cancelUniqueWork(periodicWorkName(accountId))
        }

        private fun oneTimeWorkName(accountId: String?): String {
            return "caldav-sync-${accountId ?: "all"}"
        }

        private fun periodicWorkName(accountId: String): String {
            return "caldav-periodic-sync-$accountId"
        }

        private fun accountWorkData(accountId: String?): Data {
            return if (accountId == null) {
                Data.EMPTY
            } else {
                workDataOf(KeyAccountId to accountId)
            }
        }

        private val NetworkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private const val KeyAccountId = "accountId"
        private const val RepeatIntervalHours = 4L
        private const val MaxAttempts = 3
    }
}
