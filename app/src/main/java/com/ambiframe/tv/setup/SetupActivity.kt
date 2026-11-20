package com.ambiframe.tv.setup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.ambiframe.tv.dream.AmbilightGuard
import kotlinx.coroutines.launch
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Launcher entry point. Hosts the setup flow: finding the TV's JointSpace API, pairing with a
 * PIN and testing Ambilight control.
 */
class SetupActivity : ComponentActivity() {

    private val viewModel: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If an earlier screensaver couldn't restore Ambilight, try again now.
        val guard = AmbilightGuard.get(this)
        lifecycleScope.launch { guard.restorePending() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by viewModel.state.collectAsState()
                SetupScreen(state, viewModel)
            }
        }
    }
}
