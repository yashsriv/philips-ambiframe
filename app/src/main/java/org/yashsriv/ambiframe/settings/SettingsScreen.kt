package org.yashsriv.ambiframe.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class PairingSummary(val tvName: String?, val host: String)

data class SettingsState(
    val interval: Duration = 1.minutes,
    val showAttribution: Boolean = true,
    val dimPercent: Int = 0,
    val googleArtsCulture: Boolean = false,
    val screensaver: ScreensaverStatus = ScreensaverStatus.Unknown,
    val pairing: PairingSummary? = null,
    val pairingLoaded: Boolean = false,
    val message: String? = null,
)

internal interface SettingsActions {
    fun preview()
    fun cycleInterval()
    fun toggleAttribution()
    fun cycleDim()
    fun toggleGoogleArtsCulture()
    fun openAmbilightSetup()
    fun openSystemScreensaverSettings()
}

@Composable
internal fun SettingsScreen(state: SettingsState, actions: SettingsActions) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 58.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            StatusColumn(state, Modifier.weight(1f))
            OptionsColumn(state, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusColumn(state: SettingsState, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("AmbiFrame", style = MaterialTheme.typography.displaySmall)
        Text(
            "Fine-art screensaver that turns Ambilight off while it runs and restores it afterwards.",
            style = MaterialTheme.typography.bodyLarge,
            color = muted,
        )

        Text("Ambilight", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                !state.pairingLoaded -> "Checking…"
                state.pairing != null -> "Paired with ${state.pairing.tvName ?: "the TV"} (${state.pairing.host})."
                else -> "Not paired yet. Without pairing the screensaver still shows art, but can't switch " +
                    "Ambilight off. Open Ambilight setup to pair."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )

        Text("Screensaver", style = MaterialTheme.typography.titleMedium)
        Text(
            when (state.screensaver) {
                ScreensaverStatus.Active -> "AmbiFrame is your screensaver."
                ScreensaverStatus.OtherScreensaver -> "Another screensaver is selected."
                ScreensaverStatus.Disabled -> "Screensavers are turned off on this TV."
                ScreensaverStatus.Unknown -> "Couldn't check which screensaver is selected."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        if (state.screensaver != ScreensaverStatus.Active) {
            Text(
                "Choose AmbiFrame under Settings > Device Preferences > Screen saver. If it isn't " +
                    "listed (Google TV hides other screensavers), run this from a computer with adb:",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Text(ENABLE_COMMAND, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun OptionsColumn(state: SettingsState, actions: SettingsActions, modifier: Modifier) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        item {
            Option(
                "Preview screensaver",
                "Runs it now, Ambilight included. Press any key to stop.",
                onClick = actions::preview,
                modifier = Modifier.focusRequester(firstFocus),
            )
        }
        item {
            Option(
                "Time per picture",
                intervalLabel(state.interval),
                onClick = actions::cycleInterval,
            )
        }
        item {
            Option(
                "Show credits",
                "Title, artist and museum, small in the bottom-right corner",
                onClick = actions::toggleAttribution,
                trailing = { Switch(checked = state.showAttribution, onCheckedChange = null) },
            )
        }
        item {
            Option(
                "Dim pictures",
                if (state.dimPercent > 0) "${dimLabel(state.dimPercent)}. Saves power and panel wear on OLED TVs."
                else "Off. Darkening the art saves power and panel wear on OLED TVs.",
                onClick = actions::cycleDim,
            )
        }
        item {
            Option(
                "Google Arts & Culture",
                "More works from museums worldwide. Unofficial feed; may stop working.",
                onClick = actions::toggleGoogleArtsCulture,
                trailing = { Switch(checked = state.googleArtsCulture, onCheckedChange = null) },
            )
        }
        item {
            Option(
                "Ambilight setup",
                if (state.pairing != null) "Paired. Test, pair again or forget the TV." else "Pair with the TV",
                onClick = actions::openAmbilightSetup,
            )
        }
        item {
            Option(
                "TV screensaver settings",
                "Pick the screensaver and when it starts",
                onClick = actions::openSystemScreensaverSettings,
            )
        }
    }
}

@Composable
private fun Option(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = trailing,
        modifier = modifier,
    )
}
