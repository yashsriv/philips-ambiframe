package com.ambiframe.tv.dream

import com.ambiframe.tv.art.ArtSource
import com.ambiframe.tv.art.Artwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SlideLoopTest {
    private fun art(id: String) =
        Artwork(id, "https://example.test/$id.jpg", 1920, 1080, id, "", "", "", ArtSource.ART_INSTITUTE_OF_CHICAGO)

    @Test
    fun `shows each decoded work for one interval`() = runTest {
        val queue = ArrayDeque(listOf(art("a"), art("b"), art("c")))
        val shown = mutableListOf<String>()
        val job = launch {
            SlideLoop(
                next = { queue.removeFirstOrNull() },
                decode = { it.id },
                show = { shown += it },
                interval = { 60.seconds },
            ).run()
        }
        runCurrent()
        assertEquals(listOf("a"), shown)
        advanceTimeBy(59_000); runCurrent()
        assertEquals(listOf("a"), shown)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("a", "b"), shown)
        job.cancel()
    }

    @Test
    fun `undecodable work is skipped without showing it`() = runTest {
        val queue = ArrayDeque(listOf(art("bad"), art("good")))
        val shown = mutableListOf<String>()
        val job = launch {
            SlideLoop(
                next = { queue.removeFirstOrNull() },
                decode = { if (it.id == "bad") null else it.id },
                show = { shown += it },
                interval = { 60.seconds },
                emptyRetry = 5.seconds,
            ).run()
        }
        runCurrent()
        assertEquals(emptyList<String>(), shown)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("good"), shown)
        job.cancel()
    }

    @Test
    fun `errors and empty results retry later instead of ending the loop`() = runTest {
        var calls = 0
        val shown = mutableListOf<String>()
        val job = launch {
            SlideLoop(
                next = {
                    calls++
                    when (calls) {
                        1 -> throw IllegalStateException("network down")
                        2 -> null
                        else -> art("late")
                    }
                },
                decode = { it.id },
                show = { shown += it },
                interval = { 60.seconds },
                emptyRetry = 30.seconds,
            ).run()
        }
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(emptyList<String>(), shown)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("late"), shown)
        job.cancel()
    }
}
