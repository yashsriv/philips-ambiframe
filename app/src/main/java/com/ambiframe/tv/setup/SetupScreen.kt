package com.ambiframe.tv.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Callbacks from the setup screen; implemented by [SetupViewModel]. */
internal interface SetupActions {
    fun detect()
    fun enterManually()
    fun typeManual(c: Char)
    fun backspaceManual()
    fun submitManualIp()
    fun pair()
    fun typePin(c: Char)
    fun backspacePin()
    fun submitPin()
    fun cancelPairing()
    fun testAmbilight(on: Boolean)
    fun refreshStatus()
    fun rePair()
    fun forget()
}

@Composable
internal fun SetupScreen(state: SetupUiState, actions: SetupActions) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 58.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("AmbiFrame", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Fine-art screensaver that turns Ambilight off while it runs and restores it afterwards.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.titleMedium,
                )
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
                StepHint(state)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepContent(state, actions)
            }
        }
    }
}

@Composable
private fun StepHint(state: SetupUiState) {
    val hint = when (state.step) {
        SetupStep.ManualEntry ->
            "Find the IP address on the TV under Settings > Network (or Wireless and networks) > " +
                "View network settings. Use the TV's own address even when this app runs on the TV. " +
                "On some older models the remote-control API (JointSpace) has to be switched on first."
        SetupStep.EnterPin ->
            "The PIN is shown on the TV for about a minute. Type it with the remote's number keys " +
                "or the on-screen pad."
        SetupStep.Paired ->
            "Use the test buttons to check that this app can switch Ambilight. The screensaver " +
                "does the same automatically."
        else -> null
    }
    hint?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepContent(state: SetupUiState, actions: SetupActions) {
    val firstFocus = remember(state.step) { FocusRequester() }
    LaunchedEffect(state.step) {
        // Put D-pad focus on the step's main control; ignore if it isn't laid out yet.
        runCatching { firstFocus.requestFocus() }
    }
    val wide = Modifier.width(320.dp)

    when (state.step) {
        SetupStep.Detecting -> {
            Text("Searching on this TV and the local network.", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = actions::enterManually, modifier = wide.focusRequester(firstFocus)) {
                Text("Enter IP address instead")
            }
        }

        SetupStep.ManualEntry -> {
            EnteredValue(state.manualIp, placeholder = "192.168.1.20")
            NumericKeypad(
                onDigit = actions::typeManual,
                onBackspace = actions::backspaceManual,
                onDone = actions::submitManualIp,
                doneLabel = "Connect",
                firstKeyFocus = firstFocus,
                extraKey = '.',
            )
            OutlinedButton(onClick = actions::detect, modifier = Modifier.width(232.dp)) {
                Text("Search again")
            }
        }

        SetupStep.Found -> {
            Button(onClick = actions::pair, modifier = wide.focusRequester(firstFocus)) {
                Text(if (state.needsPairing) "Pair" else "Use this TV")
            }
            OutlinedButton(onClick = actions::enterManually, modifier = wide) {
                Text("A different TV")
            }
        }

        SetupStep.EnterPin -> {
            EnteredValue(state.pin, placeholder = "PIN")
            NumericKeypad(
                onDigit = actions::typePin,
                onBackspace = actions::backspacePin,
                onDone = actions::submitPin,
                doneLabel = "Confirm PIN",
                firstKeyFocus = firstFocus,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::pair) { Text("New PIN") }
                OutlinedButton(onClick = actions::cancelPairing) { Text("Cancel") }
            }
        }

        SetupStep.Paired -> {
            Button(onClick = { actions.testAmbilight(false) }, modifier = wide.focusRequester(firstFocus)) {
                Text("Test: Ambilight off")
            }
            Button(onClick = { actions.testAmbilight(true) }, modifier = wide) {
                Text("Test: Ambilight on")
            }
            OutlinedButton(onClick = actions::refreshStatus, modifier = wide) {
                Text("Check status")
            }
            OutlinedButton(onClick = actions::rePair, modifier = wide) {
                Text("Pair again")
            }
            OutlinedButton(onClick = actions::forget, modifier = wide) {
                Text("Forget this TV")
            }
        }
    }
}
