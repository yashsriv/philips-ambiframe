package com.ambiframe.tv.dream

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambiframe.tv.art.ArtSource
import com.ambiframe.tv.art.Artwork

/**
 * How the attribution line looks. Everything about it lives here so it is easy to tweak.
 * It sits in the bottom-right corner, small and semi-transparent.
 */
object AttributionStyle {
    val fontSize: TextUnit = 11.sp
    val color: Color = Color.White.copy(alpha = 0.7f)
    val shadow: Shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 6f)

    /** Distance from the right and bottom screen edges. */
    val horizontalMargin: Dp = 20.dp
    val verticalMargin: Dp = 12.dp

    /** The line never takes more than this share of the screen width; longer text is ellipsized. */
    const val maxWidthFraction: Float = 0.6f
}

/**
 * The attribution text: `Title — Artist, Date · Museum`. Blank parts and their separators are
 * dropped.
 *
 * The museum comes from [museumOf]: AIC's `credit` is "Art Institute of Chicago, <donor credit
 * line>", which is too long for one small line, so only the museum is shown. Google Arts & Culture's
 * `credit` is already just the holding museum.
 */
fun attributionLine(work: Artwork): String {
    val who = listOf(work.artist, work.date).map(String::trim).filter(String::isNotEmpty).joinToString(", ")
    val head = listOf(work.title.trim(), who).filter(String::isNotEmpty).joinToString(" — ")
    return listOf(head, museumOf(work)).filter(String::isNotEmpty).joinToString(" · ")
}

private fun museumOf(work: Artwork): String = when (work.source) {
    ArtSource.ART_INSTITUTE_OF_CHICAGO -> work.source.label
    ArtSource.GOOGLE_ARTS_AND_CULTURE -> work.credit.trim().ifEmpty { work.source.label }
}
