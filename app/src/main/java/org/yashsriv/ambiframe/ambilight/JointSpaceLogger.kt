package org.yashsriv.ambiframe.ambilight

import android.util.Log

/**
 * Diagnostic sink for JointSpace traffic. The client never passes it secrets: `auth_key` values are
 * redacted, and the PIN, signatures and Digest headers are not logged at all.
 */
fun interface JointSpaceLogger {
    fun log(message: String)

    companion object {
        val NONE = JointSpaceLogger { }
    }
}

/** Writes to logcat under [TAG]. Read it with `adb logcat -s AmbiFrame`. */
object LogcatLogger : JointSpaceLogger {
    const val TAG = "AmbiFrame"

    override fun log(message: String) {
        // Logcat truncates long lines at about 4 KB, so split them.
        message.chunked(3000).forEach { Log.i(TAG, it) }
    }
}

private val AUTH_KEY_VALUE = Regex(""""auth_key"\s*:\s*"[^"]*"""")

/** Replaces any `"auth_key":"…"` value so the Digest password never reaches the log. */
internal fun redactSecrets(text: String): String =
    AUTH_KEY_VALUE.replace(text, "\"auth_key\":\"<redacted>\"")

/** `Class: message <- Cause: message …`, which shows TLS failures that are otherwise wrapped. */
internal fun Throwable.describeChain(): String =
    generateSequence(this) { it.cause }
        .take(5)
        .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
