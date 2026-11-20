package com.ambiframe.tv.settings

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.ambiframe.tv.dream.ArtDreamService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Whether AmbiFrame is the system screensaver. */
enum class ScreensaverStatus { Active, OtherScreensaver, Disabled, Unknown }

/** The adb command that makes AmbiFrame the screensaver (needed where Settings hides it). */
const val ENABLE_COMMAND =
    "adb shell settings put secure screensaver_components com.ambiframe.tv/.dream.ArtDreamService"

/**
 * Reads the screensaver from secure settings. `screensaver_components` is a hidden key: Android 11
 * lets apps read it, newer versions may refuse, which reads as [ScreensaverStatus.Unknown].
 */
fun readScreensaverStatus(context: Context): ScreensaverStatus = try {
    val resolver = context.contentResolver
    screensaverStatus(
        components = Settings.Secure.getString(resolver, "screensaver_components"),
        enabled = Settings.Secure.getString(resolver, "screensaver_enabled"),
        ours = ComponentName(context, ArtDreamService::class.java).flattenToString(),
    )
} catch (_: SecurityException) {
    ScreensaverStatus.Unknown
}

/**
 * [components] is a comma-separated list of flattened component names (the first is used);
 * [enabled] is "0" when screensavers are off. [ours] is our full `package/class` name; the short
 * `package/.Class` form in settings matches it too.
 */
internal fun screensaverStatus(components: String?, enabled: String?, ours: String): ScreensaverStatus {
    if (components == null) return ScreensaverStatus.Unknown
    if (enabled == "0") return ScreensaverStatus.Disabled
    val first = components.split(',').firstOrNull()?.trim().orEmpty()
    return if (expandComponent(first) == expandComponent(ours)) ScreensaverStatus.Active else ScreensaverStatus.OtherScreensaver
}

/** `pkg/.Cls` → `pkg/pkg.Cls`; anything else unchanged. */
private fun expandComponent(name: String): String {
    if ('/' !in name) return name
    val pkg = name.substringBefore('/')
    val cls = name.substringAfter('/')
    return if (cls.startsWith('.')) "$pkg/$pkg$cls" else name
}

/** The slide intervals offered in settings, shortest first. */
val SLIDE_INTERVALS: List<Duration> = listOf(15.seconds, 30.seconds, 1.minutes, 2.minutes, 5.minutes, 10.minutes, 30.minutes)

/** The next option after [current], wrapping round; an unlisted value moves to the default (1 min). */
internal fun nextInterval(current: Duration): Duration {
    val index = SLIDE_INTERVALS.indexOf(current)
    return if (index < 0) 1.minutes else SLIDE_INTERVALS[(index + 1) % SLIDE_INTERVALS.size]
}

internal fun intervalLabel(interval: Duration): String = when {
    interval < 1.minutes -> "${interval.inWholeSeconds} seconds"
    interval == 1.minutes -> "1 minute"
    else -> "${interval.inWholeMinutes} minutes"
}
