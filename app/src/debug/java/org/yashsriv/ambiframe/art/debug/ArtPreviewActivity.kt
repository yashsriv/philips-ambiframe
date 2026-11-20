package org.yashsriv.ambiframe.art.debug

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.yashsriv.ambiframe.art.ArtModule
import org.yashsriv.ambiframe.art.ArtSlideshow
import org.yashsriv.ambiframe.art.Artwork
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration.Companion.seconds

/**
 * Debug-only full-screen slideshow, for checking the art pipeline on a real TV before the
 * screensaver exists. Not in release builds.
 *
 * ```
 * adb shell am start -n org.yashsriv.ambiframe/.art.debug.ArtPreviewActivity [--ez google true] [--ei interval 15]
 * ```
 * `--ez google true|false` turns the Google Arts & Culture source on or off (persisted).
 * D-pad right or center: next work. Logcat tag `AmbiFrameArt`.
 */
class ArtPreviewActivity : ComponentActivity() {
    private val skip = Channel<Unit>(Channel.CONFLATED)
    private var current by mutableStateOf<Artwork?>(null)
    private var status by mutableStateOf("Loading…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val module = ArtModule.get(this)
        if (intent.hasExtra(EXTRA_GOOGLE)) {
            module.settings.googleArtsCultureEnabled = intent.getBooleanExtra(EXTRA_GOOGLE, false)
        }
        val interval = intent.getIntExtra(EXTRA_INTERVAL, 15).coerceAtLeast(3).seconds
        val slideshow = module.slideshow(lifecycleScope)

        setContent { Preview(module, current, status) }

        lifecycleScope.launch { run(slideshow, interval, module) }
    }

    private suspend fun run(slideshow: ArtSlideshow, interval: kotlin.time.Duration, module: ArtModule) {
        val count = slideshow.refresh()
        val bySource = slideshow.works().groupingBy { it.source.label }.eachCount()
        val offline = if (module.repository.isOffline) " (offline catalog)" else ""
        status = "$count works$offline · $bySource · sources ${module.settings.enabledSources()}"
        Log.i(TAG, "Preview: $status")
        while (true) {
            val work = slideshow.next()
            if (work == null) {
                status = "Nothing to show$offline"
                delay(interval)
                continue
            }
            current = work
            Log.i(TAG, "Showing ${work.id} ${work.width}x${work.height} ${work.imageUrl}")
            select {
                skip.onReceive { }
                onTimeout(interval) { }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            skip.trySend(Unit)
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val TAG = "AmbiFrameArt"
        const val EXTRA_GOOGLE = "google"
        const val EXTRA_INTERVAL = "interval"
    }
}

@Composable
private fun Preview(module: ArtModule, work: Artwork?, status: String) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (work != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(work.imageUrl).crossfade(1000).build(),
                contentDescription = work.title,
                imageLoader = module.imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val shadow = TextStyle(shadow = Shadow(Color.Black, blurRadius = 6f))
        Text(
            text = status,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            style = shadow,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )
        if (work != null) {
            Column(Modifier.align(Alignment.BottomEnd).padding(horizontal = 16.dp, vertical = 10.dp)) {
                val ratio = if (work.width != null && work.height != null) {
                    " · %.2f".format(work.width.toDouble() / work.height)
                } else {
                    ""
                }
                Text(
                    text = listOf(work.title, listOf(work.artist, work.date).filter { it.isNotBlank() }.joinToString(", "))
                        .filter { it.isNotBlank() }.joinToString(" — ") + " · " + work.credit + ratio,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = shadow,
                )
            }
        }
    }
}
