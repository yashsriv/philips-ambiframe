package org.yashsriv.ambiframe.dream

import android.util.Log
import org.yashsriv.ambiframe.art.Artwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the slideshow: asks for the next work, decodes it fully, and only then hands it to
 * [show], so the crossfade never starts on a half-loaded image. Runs until cancelled.
 *
 * @param next the next work to show, or null when nothing is available yet.
 * @param decode the decoded slide for a work, or null if the image could not be decoded.
 */
internal class SlideLoop<S : Any>(
    private val next: suspend () -> Artwork?,
    private val decode: suspend (Artwork) -> S?,
    private val show: (S) -> Unit,
    private val interval: () -> Duration,
    private val emptyRetry: Duration = 30.seconds,
) {
    suspend fun run() {
        while (true) {
            val slide = try {
                next()?.let { work -> decode(work).also { if (it == null) Log.w(TAG, "Could not decode ${work.id}") } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Slide failed", e)
                null
            }
            if (slide == null) {
                delay(emptyRetry)
                continue
            }
            show(slide)
            delay(interval())
        }
    }

    private companion object {
        const val TAG = "AmbiFrameDream"
    }
}
