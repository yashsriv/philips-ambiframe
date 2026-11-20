package org.yashsriv.ambiframe.dream

import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.coroutineScope

/**
 * The screensaver: a full-screen fine-art slideshow.
 *
 * It is not interactive, so any remote key dismisses it. [ScreensaverSession] switches Ambilight
 * off when it starts and restores the previous style when it stops.
 */
class ArtDreamService : DreamService() {
    private val owner = DreamLifecycleOwner()
    private lateinit var session: ScreensaverSession

    override fun onCreate() {
        super.onCreate()
        owner.create()
        session = ScreensaverSession(this, owner.lifecycle.coroutineScope)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { session.Content() }
        }
        owner.attachTo(window.decorView)
        owner.attachTo(view)
        setContentView(view)
        owner.resume()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        session.start()
    }

    override fun onDreamingStopped() {
        session.stop()
        owner.stop()
        super.onDreamingStopped()
    }

    /** Some exits skip [onDreamingStopped]; the session ignores the second stop. */
    override fun onDetachedFromWindow() {
        session.stop()
        owner.stop()
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        owner.destroy()
        super.onDestroy()
    }
}
