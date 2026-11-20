package com.ambiframe.tv.dream

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.toBitmap
import com.ambiframe.tv.art.ArtModule
import com.ambiframe.tv.art.Artwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One run of the screensaver: switches Ambilight off, plays the slideshow, and restores Ambilight
 * when stopped. Shared by [ArtDreamService] and the preview, so both behave the same.
 *
 * [start] and [stop] may be called more than once; repeats are ignored.
 */
class ScreensaverSession(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val settings = DreamSettings(this.context)
    private val guard = AmbilightGuard.get(this.context)
    private val showAttribution = settings.showAttribution
    private var slide by mutableStateOf<Slide?>(null)
    private var loop: Job? = null

    @Composable
    fun Content() = ArtFrame(slide, showAttribution)

    fun start() {
        if (loop != null) return
        guard.dreamStarted()
        val slideshow = ArtModule.get(context).slideshow(scope)
        loop = scope.launch {
            SlideLoop(
                next = slideshow::next,
                decode = ::decode,
                show = { slide = it },
                interval = { settings.slideInterval },
            ).run()
        }
    }

    fun stop() {
        val running = loop ?: return
        guard.dreamStopped()
        running.cancel()
        loop = null
    }

    /** Decodes [work] at screen size. The image is normally already in the disk cache. */
    private suspend fun decode(work: Artwork): Slide? {
        val metrics = context.resources.displayMetrics
        val request = ImageRequest.Builder(context)
            .data(work.imageUrl)
            .size(Size(metrics.widthPixels, metrics.heightPixels))
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .build()
        val result = ArtModule.get(context).imageLoader.execute(request) as? SuccessResult ?: return null
        return Slide(work, result.image.toBitmap().asImageBitmap())
    }
}
