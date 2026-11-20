package com.ambiframe.tv.dream

import com.ambiframe.tv.ambilight.AmbilightConfiguration
import com.ambiframe.tv.ambilight.AmbilightState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AmbilightGuardTest {
    private val lounge = AmbilightState(on = true, configuration = AmbilightConfiguration(styleName = "FOLLOW_VIDEO"))

    private class FakeTv(var state: AmbilightState) : AmbilightControl {
        val calls = mutableListOf<String>()
        var failRestores = 0
        var captureGate: CompletableDeferred<Unit>? = null

        override suspend fun capture(): AmbilightState {
            captureGate?.await()
            calls += "capture"
            return state
        }

        override suspend fun switchOff() {
            calls += "off"
            state = state.copy(on = false)
        }

        override suspend fun restore(state: AmbilightState) {
            calls += "restore:${state.configuration?.styleName}"
            if (failRestores > 0) {
                failRestores--
                throw IOException("TV unreachable")
            }
            this.state = state
        }
    }

    private class MemoryStore : PendingRestoreStore {
        var state: AmbilightState? = null
        override fun load() = state
        override fun save(state: AmbilightState) { this.state = state }
        override fun clear() { state = null }
    }

    private class FakeScheduler : RestoreScheduler {
        var scheduled: Duration? = null
        override fun schedule(delay: Duration) { scheduled = delay }
        override fun cancel() { scheduled = null }
    }

    private val store = MemoryStore()
    private val scheduler = FakeScheduler()

    /** On the test scheduler but not `backgroundScope`, whose work `advanceUntilIdle()` doesn't wait for. */
    private fun TestScope.guardScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private fun TestScope.guard(tv: FakeTv?) = AmbilightGuard(
        control = { tv },
        store = store,
        scheduler = scheduler,
        scope = guardScope(),
        retryDelays = listOf(2.seconds, 5.seconds),
        fallbackDelay = 30.seconds,
    )

    @Test
    fun `switches off and restores the saved style`() = runTest {
        val tv = FakeTv(lounge)
        val guard = guard(tv)

        guard.dreamStarted(); advanceUntilIdle()
        assertEquals(listOf("capture", "off"), tv.calls)
        assertFalse(tv.state.on)
        assertEquals(lounge, store.state)

        guard.dreamStopped(); advanceUntilIdle()
        assertEquals(listOf("capture", "off", "restore:FOLLOW_VIDEO"), tv.calls)
        assertEquals(lounge, tv.state)
        assertNull(store.state)
        assertNull("fallback cancelled after a direct restore", scheduler.scheduled)
    }

    @Test
    fun `leaves Ambilight alone when it was already off`() = runTest {
        val tv = FakeTv(AmbilightState(on = false, configuration = null))
        val guard = guard(tv)

        guard.dreamStarted(); advanceUntilIdle()
        guard.dreamStopped(); advanceUntilIdle()

        assertEquals(listOf("capture"), tv.calls)
        assertNull(store.state)
        assertNull(scheduler.scheduled)
    }

    @Test
    fun `does nothing when not paired`() = runTest {
        val guard = guard(null)
        guard.dreamStarted(); advanceUntilIdle()
        guard.dreamStopped(); advanceUntilIdle()
        assertNull(store.state)
        assertNull(scheduler.scheduled)
    }

    @Test
    fun `retries a failed restore, then leaves it to the fallback job`() = runTest {
        val tv = FakeTv(lounge)
        val guard = guard(tv)
        guard.dreamStarted(); advanceUntilIdle()

        tv.failRestores = 3
        guard.dreamStopped(); advanceUntilIdle()

        assertEquals(3, tv.calls.count { it.startsWith("restore") })
        assertEquals(lounge, store.state)
        assertEquals(30.seconds, scheduler.scheduled)

        // The fallback job runs later and the TV answers this time.
        assertTrue(guard.restorePending())
        assertNull(store.state)
        assertNull(scheduler.scheduled)
        assertEquals(lounge, tv.state)
    }

    @Test
    fun `a failed fallback reports false so the job retries`() = runTest {
        val tv = FakeTv(lounge)
        store.state = lounge
        tv.failRestores = 1
        assertFalse(guard(tv).restorePending())
        assertEquals(lounge, store.state)
    }

    @Test
    fun `a state left by a failed restore is kept, not recaptured as off`() = runTest {
        // An earlier screensaver switched the lights off and couldn't restore them.
        val tv = FakeTv(AmbilightState(on = false, configuration = null))
        store.state = lounge
        scheduler.scheduled = 30.seconds
        val guard = guard(tv)

        guard.dreamStarted(); advanceUntilIdle()
        assertEquals("no capture: the saved style is the user's", listOf("off"), tv.calls)
        assertNull("fallback cancelled while dreaming", scheduler.scheduled)

        guard.dreamStopped(); advanceUntilIdle()
        assertEquals(lounge, tv.state)
        assertNull(store.state)
    }

    @Test
    fun `dismissed while capturing does not switch off afterwards`() = runTest {
        val tv = FakeTv(lounge).apply { captureGate = CompletableDeferred() }
        val guard = guard(tv)

        guard.dreamStarted(); advanceUntilIdle()
        guard.dreamStopped()
        tv.captureGate!!.complete(Unit)
        advanceUntilIdle()

        assertFalse("never switched off", "off" in tv.calls)
        assertEquals(lounge, tv.state)
        assertNull(store.state)
    }

    @Test
    fun `stopping twice restores once`() = runTest {
        val tv = FakeTv(lounge)
        val guard = guard(tv)
        guard.dreamStarted(); advanceUntilIdle()
        guard.dreamStopped()
        guard.dreamStopped()
        advanceUntilIdle()
        assertEquals(1, tv.calls.count { it.startsWith("restore") })
    }

    @Test
    fun `fallback does not restore while a screensaver is running`() = runTest {
        val tv = FakeTv(lounge)
        val guard = guard(tv)
        guard.dreamStarted(); advanceUntilIdle()

        assertTrue(guard.restorePending())
        assertFalse(tv.state.on)
        assertEquals(lounge, store.state)
    }

    @Test
    fun `a capture failure leaves the lights on and saves nothing`() = runTest {
        val tv = object : AmbilightControl {
            var offCalled = false
            override suspend fun capture(): AmbilightState = throw IOException("TV unreachable")
            override suspend fun switchOff() { offCalled = true }
            override suspend fun restore(state: AmbilightState) = Unit
        }
        val guard = AmbilightGuard({ tv }, store, scheduler, guardScope())
        guard.dreamStarted(); advanceUntilIdle()
        assertFalse(tv.offCalled)
        assertNull(store.state)
    }
}
