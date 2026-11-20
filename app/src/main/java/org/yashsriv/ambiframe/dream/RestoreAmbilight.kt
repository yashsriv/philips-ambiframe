package org.yashsriv.ambiframe.dream

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.yashsriv.ambiframe.ambilight.AmbilightState
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Keeps the state to restore in SharedPreferences, written synchronously so it survives a kill. */
internal class SharedPrefsPendingRestoreStore(context: Context) : PendingRestoreStore {
    private val prefs = context.getSharedPreferences("ambilight_guard", Context.MODE_PRIVATE)

    override fun load(): AmbilightState? {
        val text = prefs.getString(KEY, null) ?: return null
        return try {
            json.decodeFromString(AmbilightState.serializer(), text)
        } catch (e: IllegalArgumentException) {
            Log.w("AmbiFrameDream", "Dropping unreadable saved Ambilight state", e)
            clear()
            null
        }
    }

    override fun save(state: AmbilightState) =
        prefs.edit(commit = true) { putString(KEY, json.encodeToString(AmbilightState.serializer(), state)) }

    override fun clear() = prefs.edit(commit = true) { remove(KEY) }

    private companion object {
        const val KEY = "pending_restore"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Schedules [RestoreAmbilightWorker] once the network is up, retrying with backoff. */
internal class WorkManagerRestoreScheduler(private val context: Context) : RestoreScheduler {
    override fun schedule(delay: Duration) {
        val request = OneTimeWorkRequestBuilder<RestoreAmbilightWorker>()
            .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "restore-ambilight"
        const val BACKOFF_SECONDS = 30L
    }
}

/**
 * Fallback restore after the screensaver ends, for when the direct restore failed or the process
 * died first. Retries with backoff while the TV is unreachable, and gives up after
 * [MAX_ATTEMPTS]. The saved state is kept then, so the next screensaver's end restores it.
 */
class RestoreAmbilightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val restored = AmbilightGuard.get(applicationContext).restorePending()
        return when {
            restored -> Result.success()
            runAttemptCount + 1 >= MAX_ATTEMPTS -> Result.failure()
            else -> Result.retry()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}
