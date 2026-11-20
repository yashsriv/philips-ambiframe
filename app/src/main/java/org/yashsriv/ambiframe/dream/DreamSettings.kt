package org.yashsriv.ambiframe.dream

import android.content.Context
import androidx.core.content.edit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Screensaver preferences, edited on the settings screen. */
class DreamSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("dream_settings", Context.MODE_PRIVATE)

    var slideInterval: Duration
        get() = prefs.getLong(KEY_INTERVAL, DEFAULT_INTERVAL.inWholeSeconds).coerceAtLeast(MIN_SECONDS).seconds
        set(value) = prefs.edit { putLong(KEY_INTERVAL, value.inWholeSeconds.coerceAtLeast(MIN_SECONDS)) }

    var showAttribution: Boolean
        get() = prefs.getBoolean(KEY_ATTRIBUTION, true)
        set(value) = prefs.edit { putBoolean(KEY_ATTRIBUTION, value) }

    /** How much darker to draw the art, 0–[MAX_DIM_PERCENT]. Saves power and wear on OLED panels. */
    var dimPercent: Int
        get() = prefs.getInt(KEY_DIM, 0).coerceIn(0, MAX_DIM_PERCENT)
        set(value) = prefs.edit { putInt(KEY_DIM, value.coerceIn(0, MAX_DIM_PERCENT)) }

    private companion object {
        const val KEY_INTERVAL = "slide_interval_seconds"
        const val KEY_ATTRIBUTION = "show_attribution"
        const val KEY_DIM = "dim_percent"
        const val MAX_DIM_PERCENT = 80
        const val MIN_SECONDS = 5L
        val DEFAULT_INTERVAL = 60.seconds
    }
}
