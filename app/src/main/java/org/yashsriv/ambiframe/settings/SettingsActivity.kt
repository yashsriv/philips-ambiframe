package org.yashsriv.ambiframe.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import org.yashsriv.ambiframe.ambilight.PairingRepository
import org.yashsriv.ambiframe.art.ArtModule
import org.yashsriv.ambiframe.dream.AmbilightGuard
import org.yashsriv.ambiframe.dream.DreamSettings
import org.yashsriv.ambiframe.setup.SetupActivity
import kotlinx.coroutines.launch

/**
 * The app's home screen, and what the TV opens for "screensaver settings": slideshow options,
 * a preview, whether AmbiFrame is the active screensaver, and the way into Ambilight pairing.
 */
class SettingsActivity : ComponentActivity(), SettingsActions {
    private val dreamSettings by lazy { DreamSettings(this) }
    private val artSettings by lazy { ArtModule.get(this).settings }
    private var state by mutableStateOf(SettingsState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If an earlier screensaver couldn't restore Ambilight, try again now.
        val guard = AmbilightGuard.get(this)
        lifecycleScope.launch { guard.restorePending() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SettingsScreen(state, this)
            }
        }
    }

    /** Pairing and the screensaver choice can change elsewhere, so re-read them on every return. */
    override fun onResume() {
        super.onResume()
        state = state.copy(
            interval = dreamSettings.slideInterval,
            showAttribution = dreamSettings.showAttribution,
            dimPercent = dreamSettings.dimPercent,
            googleArtsCulture = artSettings.googleArtsCultureEnabled,
            screensaver = readScreensaverStatus(this),
        )
        lifecycleScope.launch {
            val tv = PairingRepository.get(this@SettingsActivity).current()
            state = state.copy(pairing = tv?.let { PairingSummary(it.tvName, it.host) }, pairingLoaded = true)
        }
    }

    override fun preview() = startActivity(Intent(this, PreviewActivity::class.java))

    override fun cycleInterval() {
        val next = nextInterval(dreamSettings.slideInterval)
        dreamSettings.slideInterval = next
        state = state.copy(interval = next)
    }

    override fun toggleAttribution() {
        dreamSettings.showAttribution = !dreamSettings.showAttribution
        state = state.copy(showAttribution = dreamSettings.showAttribution)
    }

    override fun cycleDim() {
        dreamSettings.dimPercent = nextDim(dreamSettings.dimPercent)
        state = state.copy(dimPercent = dreamSettings.dimPercent)
    }

    override fun toggleGoogleArtsCulture() {
        artSettings.googleArtsCultureEnabled = !artSettings.googleArtsCultureEnabled
        state = state.copy(googleArtsCulture = artSettings.googleArtsCultureEnabled)
    }

    override fun openAmbilightSetup() = startActivity(Intent(this, SetupActivity::class.java))

    override fun openSystemScreensaverSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            state = state.copy(message = "This TV has no screensaver settings screen. Use the adb command instead.")
        }
    }
}
