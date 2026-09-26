package com.example.recipeclipper.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * [AutoBackupScheduler] on WorkManager (#150). Each request only *looks*: [AutoBackup.run]
 * writes a copy when [com.example.recipeclipper.data.backup.AutoBackupPolicy.isDue] says so.
 * - Daily, kept (so at least weekly, and after changes within a day), with battery not low.
 * - A minute after the app is left, kept (so quick returns share one look).
 * - At once when a folder is chosen, replacing any pending one.
 */
class WorkManagerAutoBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : AutoBackupScheduler {

    private val work get() = WorkManager.getInstance(context)

    override fun startPeriodic() {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun afterLeaving() {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>().setInitialDelay(1, TimeUnit.MINUTES).build()
        work.enqueueUniqueWork(AFTER_LEAVING, ExistingWorkPolicy.KEEP, request)
    }

    override fun now() {
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    private companion object {
        const val DAILY = "auto-backup-daily"
        const val AFTER_LEAVING = "auto-backup-after-leaving"
        const val NOW = "auto-backup-now"
    }
}

/**
 * One look for a copy to write. The graph is reached through an entry point rather than a Hilt
 * worker factory, so WorkManager's default initialisation stands. Always succeeds: a failure is
 * recorded for Settings, and the next look tries again.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun autoBackup(): AutoBackup
    }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).autoBackup().run()
        return Result.success()
    }
}
