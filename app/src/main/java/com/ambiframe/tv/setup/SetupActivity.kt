package com.ambiframe.tv.setup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/**
 * Launcher entry point. Hosts the setup flow: pairing with the TV's
 * JointSpace API and screensaver settings. Pairing UI lands in a later step.
 */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SetupScreen()
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 58.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AmbiFrame", style = MaterialTheme.typography.displaySmall)
            Text(
                "Fine-art screensaver that turns Ambilight off while it runs.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Ambilight pairing setup is coming in the next step.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
