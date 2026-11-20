package org.yashsriv.ambiframe.dream

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.yashsriv.ambiframe.art.Artwork

/** One decoded slide, ready to draw. */
@Immutable
class Slide(val work: Artwork, val bitmap: ImageBitmap)

/**
 * The screensaver picture: [slide] full-bleed (cropped to fill the screen), crossfading when it
 * changes, with the attribution line in the bottom-right corner.
 *
 * Everything drifts by a few pixels over several minutes and the image is scaled up slightly so the
 * drift never shows an edge. That keeps static content, the attribution text especially, from
 * sitting on the same pixels for hours on OLED panels.
 *
 * [dim] (0–1) darkens the art, not the credit, by drawing black over it at that opacity. On OLED
 * panels power use and wear follow pixel brightness, so this saves both.
 */
@Composable
fun ArtFrame(slide: Slide?, showAttribution: Boolean, modifier: Modifier = Modifier, dim: Float = 0f) {
    val drift = rememberInfiniteTransition(label = "burn-in drift")
    val dx by drift.animateFloat(
        initialValue = -1f, targetValue = 1f, label = "dx",
        animationSpec = infiniteRepeatable(tween(DRIFT_X_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
    )
    val dy by drift.animateFloat(
        initialValue = -1f, targetValue = 1f, label = "dy",
        animationSpec = infiniteRepeatable(tween(DRIFT_Y_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
    )
    val driftPx = with(LocalDensity.current) { DRIFT.toPx() }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val maxAttributionWidth = maxWidth * AttributionStyle.maxWidthFraction
        Crossfade(
            targetState = slide,
            animationSpec = tween(CROSSFADE_MILLIS),
            label = "slide",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            if (shown == null) return@Crossfade
            Box(Modifier.fillMaxSize()) {
                Image(
                    bitmap = shown.bitmap,
                    contentDescription = shown.work.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = DRIFT_SCALE
                            scaleY = DRIFT_SCALE
                            translationX = dx * driftPx
                            translationY = dy * driftPx
                        }
                        .drawWithContent {
                            drawContent()
                            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim.coerceAtMost(1f)))
                        },
                )
                if (showAttribution) {
                    Text(
                        text = attributionLine(shown.work),
                        color = AttributionStyle.color,
                        fontSize = AttributionStyle.fontSize,
                        style = TextStyle(shadow = AttributionStyle.shadow),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = AttributionStyle.horizontalMargin,
                                bottom = AttributionStyle.verticalMargin,
                            )
                            .widthIn(max = maxAttributionWidth)
                            .graphicsLayer {
                                translationX = dx * driftPx
                                translationY = dy * driftPx
                            },
                    )
                }
            }
        }
    }
}

private const val CROSSFADE_MILLIS = 2_000

/** Maximum drift in each direction, and how slowly it moves (seconds for one sweep). */
private val DRIFT = 6.dp
private const val DRIFT_X_MILLIS = 7 * 60_000
private const val DRIFT_Y_MILLIS = 11 * 60_000

/** Enough overscan to hide a [DRIFT] shift on a 1080p-wide layout. */
private const val DRIFT_SCALE = 1.015f
