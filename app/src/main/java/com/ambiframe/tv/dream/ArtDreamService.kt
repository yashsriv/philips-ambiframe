package com.ambiframe.tv.dream

import android.service.dreams.DreamService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.coroutineScope
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.toBitmap
import com.ambiframe.tv.art.ArtModule
import com.ambiframe.tv.art.Artwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The screensaver: a full-screen fine-art slideshow.
 *
 * It is not interactive, so any remote key dismisses it. Ambilight is switched off and restored
 * around [onDreamingStarted]/[onDreamingStopped] in step 7.
 */
class ArtDreamService : DreamService() {
    private val owner = DreamLifecycleOwner()
    private var slide by mutableStateOf<Slide?>(null)
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        owner.create()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        val settings = DreamSettings(this)
        val showAttribution = settings.showAttribution
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { ArtFrame(slide, showAttribution) }
        }
        owner.attachTo(window.decorView)
        owner.attachTo(view)
        setContentView(view)
        owner.resume()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val module = ArtModule.get(this)
        val settings = DreamSettings(this)
        val scope = owner.lifecycle.coroutineScope
        val slideshow = module.slideshow(scope)
        loop = scope.launch {
            SlideLoop(
                next = slideshow::next,
                decode = ::decode,
                show = { slide = it },
                interval = { settings.slideInterval },
            ).run()
        }
    }

    override fun onDreamingStopped() {
        loop?.cancel()
        loop = null
        owner.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        loop?.cancel()
        loop = null
        owner.stop()
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        owner.destroy()
        super.onDestroy()
    }

    /** Decodes [work] at screen size. The image is normally already in the disk cache. */
    private suspend fun decode(work: Artwork): Slide? {
        val metrics = resources.displayMetrics
        val request = ImageRequest.Builder(this)
            .data(work.imageUrl)
            .size(Size(metrics.widthPixels, metrics.heightPixels))
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .build()
        val result = ArtModule.get(this).imageLoader.execute(request) as? SuccessResult ?: return null
        return Slide(work, result.image.toBitmap().asImageBitmap())
    }
}
