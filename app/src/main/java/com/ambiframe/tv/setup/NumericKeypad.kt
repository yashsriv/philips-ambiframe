package com.ambiframe.tv.setup

import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private val KeySize = Modifier.size(width = 72.dp, height = 48.dp)

/**
 * On-screen number pad for D-pad remotes. The remote's own number keys (and Delete / period)
 * also work while any key of the pad has focus.
 */
@Composable
internal fun NumericKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    doneLabel: String,
    firstKeyFocus: FocusRequester,
    modifier: Modifier = Modifier,
    extraKey: Char? = null,
) {
    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (val code = event.nativeKeyEvent.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    onDigit('0' + (code - KeyEvent.KEYCODE_0)); true
                }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    onDigit('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)); true
                }
                KeyEvent.KEYCODE_DEL -> {
                    onBackspace(); true
                }
                KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT ->
                    if (extraKey == '.') {
                        onDigit('.'); true
                    } else {
                        false
                    }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("123", "456", "789").forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { index, c ->
                    val focus = if (rowIndex == 0 && index == 0) Modifier.focusRequester(firstKeyFocus) else Modifier
                    KeyButton(c.toString(), focus) { onDigit(c) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (extraKey != null) KeyButton(extraKey.toString()) { onDigit(extraKey) } else Spacer(KeySize)
            KeyButton("0") { onDigit('0') }
            KeyButton("Del", onClick = onBackspace)
        }
        Button(onClick = onDone, modifier = Modifier.width(232.dp)) {
            Text(doneLabel, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun KeyButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.then(KeySize), contentPadding = PaddingValues(0.dp)) {
        Box(Modifier.size(width = 72.dp, height = 48.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Shows what has been typed on the keypad, or [placeholder] when empty. */
@Composable
internal fun EnteredValue(value: String, placeholder: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(232.dp)
            .border(1.dp, MaterialTheme.colorScheme.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = value.ifEmpty { placeholder },
            style = MaterialTheme.typography.headlineSmall,
            color = if (value.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
