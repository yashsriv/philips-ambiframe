package com.ambiframe.tv.dream

import android.content.Context
import android.util.Log
import com.ambiframe.tv.ambilight.AmbilightState
import com.ambiframe.tv.ambilight.LogcatLogger
import com.ambiframe.tv.ambilight.PairingRepository
import com.ambiframe.tv.ambilight.captureAmbilight
import com.ambiframe.tv.ambilight.restoreAmbilight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Ambilight calls the guard needs. Production: the paired TV's JointSpace API. */
interface AmbilightControl {
    suspend fun capture(): AmbilightState
    suspend fun switchOff()
    suspend fun restore(state: AmbilightState)
}

/** Where the state to restore is kept, so a crash or reboot can't lose it. */
interface PendingRestoreStore {
    fun load(): AmbilightState?
    fun save(state: AmbilightState)
    fun clear()
}

/** A restore that runs later, even if this process dies. Production: WorkManager. */
interface RestoreScheduler {
    fun schedule(delay: Duration)
    fun cancel()
}

/**
 * Turns Ambilight off while the screensaver runs and puts it back afterwards.
 *
 * Operations run one at a time, in the order the dream asks for them. The style captured before
 * switching off is saved in [store] first, and the screensaver stopping schedules a fallback
 * restore with [scheduler] before trying directly. So if the TV doesn't answer, or the process is
 * killed mid-call, the restore still happens later. Every failure is logged and swallowed: the
 * screensaver must never crash because of the TV's API.
 *
 * A saved state that is still there when a new screensaver starts belongs to the user (an
 * earlier restore failed), so it is kept rather than recaptured. Recapturing would save "off".
 */
class AmbilightGuard(
    private val control: suspend () -> AmbilightControl?,
    private val store: PendingRestoreStore,
    private val scheduler: RestoreScheduler,
    private val scope: CoroutineScope,
    private val callTimeout: Duration = 20.seconds,
    private val retryDelays: List<Duration> = listOf(2.seconds, 5.seconds),
    private val fallbackDelay: Duration = 30.seconds,
) {
    private val mutex = Mutex()

    @Volatile
    var dreaming: Boolean = false
        private set

    /** Called from `onDreamingStarted`. Returns at once; the TV calls run in the background. */
    fun dreamStarted() {
        dreaming = true
        scope.launch { mutex.withLock { switchOff() } }
    }

    /** Called from `onDreamingStopped` (and again from `onDetachedFromWindow`; repeats are ignored). */
    fun dreamStopped() {
        if (!dreaming) return
        dreaming = false
        // Before anything async: if the process dies right after the dream ends, this still runs.
        if (store.load() != null) scheduler.schedule(fallbackDelay)
        scope.launch { mutex.withLock { restoreWithRetries() } }
    }

    /**
     * Restores a saved state, if any, unless a screensaver is running. Used by the fallback job and
     * at app start. Returns false when the TV could not be reached, so the caller can retry.
     */
    suspend fun restorePending(): Boolean = mutex.withLock {
        if (dreaming) return@withLock true
        val pending = store.load() ?: return@withLock true
        attempt("restore (fallback)") { restore(pending) }
    }

    private suspend fun switchOff() {
        if (!dreaming) return
        attempt("switch off") {
            val tv = control() ?: return@attempt log("Not paired; leaving Ambilight alone")
            scheduler.cancel() // A pending fallback restore would fight the screensaver.
            if (store.load() == null) {
                val state = tv.capture()
                if (!state.on) return@attempt log("Ambilight already off; nothing to do")
                store.save(state)
                log("Saved Ambilight style ${state.configuration?.styleName}")
            } else {
                log("Keeping the style saved by an earlier screensaver")
            }
            // Dismissed while we were capturing: the restore queued behind us handles it.
            if (!dreaming) return@attempt
            tv.switchOff()
            log("Ambilight off")
        }
    }

    private suspend fun restoreWithRetries() {
        val pending = store.load() ?: return
        scheduler.schedule(fallbackDelay)
        for (wait in listOf(Duration.ZERO) + retryDelays) {
            delay(wait)
            if (dreaming) return // A new screensaver started; it keeps the saved state.
            if (attempt("restore") { restore(pending) }) return
        }
        log("Restore failed; the fallback job will retry")
    }

    private suspend fun restore(pending: AmbilightState) {
        val tv = control() ?: run {
            // Unpaired since: nothing can be restored any more.
            store.clear()
            scheduler.cancel()
            return
        }
        tv.restore(pending)
        store.clear()
        scheduler.cancel()
        log("Ambilight restored to ${pending.configuration?.styleName}")
    }

    /** Runs [block] with a timeout; true on success, false (logged) on any failure. */
    private suspend fun attempt(what: String, block: suspend () -> Unit): Boolean = try {
        withTimeout(callTimeout) { block() }
        true
    } catch (e: CancellationException) {
        if (e is kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Ambilight $what timed out")
            false
        } else {
            throw e
        }
    } catch (e: Exception) {
        Log.w(TAG, "Ambilight $what failed: $e")
        false
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "AmbiFrameDream"

        @Volatile
        private var instance: AmbilightGuard? = null

        fun get(context: Context): AmbilightGuard = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): AmbilightGuard {
            val pairing = PairingRepository.get(context)
            return AmbilightGuard(
                control = { pairing.current()?.let { JointSpaceAmbilightControl(it.client(LogcatLogger)) } },
                store = SharedPrefsPendingRestoreStore(context),
                scheduler = WorkManagerRestoreScheduler(context),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        }
    }
}

private class JointSpaceAmbilightControl(
    private val client: com.ambiframe.tv.ambilight.JointSpaceClient,
) : AmbilightControl {
    override suspend fun capture() = client.captureAmbilight()
    override suspend fun switchOff() = client.setAmbilightPower(false)
    override suspend fun restore(state: AmbilightState) = client.restoreAmbilight(state)
}
