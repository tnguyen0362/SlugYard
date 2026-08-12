package com.sluggyard.tv.core.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class SupabaseSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val result = syncCoordinatorFromApplication(applicationContext).synchronize()
        return when (result) {
            is SyncResult.Success -> {
                Log.i(
                    TAG,
                    "sync ok pulled=${result.value.pulledRecords} pushed=${result.value.pushedMutations} " +
                        "retained=${result.value.retainedMutations}",
                )
                Result.success()
            }
            SyncResult.SessionExpired -> {
                Log.w(TAG, "sync session expired")
                Result.failure()
            }
            is SyncResult.Failure -> {
                Log.w(TAG, "sync failed kind=${result.kind} transient=${result.kind.isTransient()}")
                if (result.kind.isTransient()) Result.retry() else Result.failure()
            }
        }
    }

    private companion object {
        const val TAG = "SupabaseSync"
    }
}

private fun SyncFailureKind.isTransient(): Boolean = when (this) {
    SyncFailureKind.Network,
    SyncFailureKind.RateLimited,
    SyncFailureKind.Server,
    -> true
    else -> false
}

object SupabaseSyncScheduler {
    private const val PERIODIC_WORK_NAME = "slugyard-supabase-periodic-sync"
    private const val IMMEDIATE_WORK_NAME = "slugyard-supabase-immediate-sync"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SupabaseSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun requestImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SupabaseSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SupabaseSyncWorkerEntryPoint {
    fun syncCoordinator(): SyncCoordinator
}

internal fun syncCoordinatorFromApplication(context: Context): SyncCoordinator =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        SupabaseSyncWorkerEntryPoint::class.java,
    ).syncCoordinator()
