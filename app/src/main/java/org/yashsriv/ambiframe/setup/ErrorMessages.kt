package org.yashsriv.ambiframe.setup

import org.yashsriv.ambiframe.ambilight.JointSpaceException
import kotlinx.coroutines.TimeoutCancellationException

/** Turns a failure into one sentence a person can act on. */
internal fun plainLanguage(error: Throwable, host: String? = null): String {
    val where = host?.let { " at $it" }.orEmpty()
    return when (error) {
        is TimeoutCancellationException ->
            "The TV$where didn't answer in time. Check that it's switched on and on the same network."
        is JointSpaceException.Unreachable ->
            if (error.timedOut) {
                "The TV$where didn't answer in time. Check that it's switched on and on the same network."
            } else {
                "Can't reach a Philips TV$where. Check the IP address and that the TV is on the same " +
                    "network. On some models the remote-control API (JointSpace) has to be switched on first."
            }
        is JointSpaceException.CertificateChanged ->
            "The TV's security certificate has changed since pairing. If the TV was reset or replaced, " +
                "forget it and pair again."
        is JointSpaceException.Unauthorized ->
            "The TV no longer accepts this app's pairing. Choose \"Pair again\"."
        is JointSpaceException.PairingRejected -> pairingRejected(error.errorId)
        is JointSpaceException.HttpError ->
            if (error.code == 404) {
                "This TV doesn't offer Ambilight control through its API."
            } else {
                "The TV answered with an error (HTTP ${error.code}). Try again in a moment."
            }
        is JointSpaceException.BadResponse ->
            "The TV sent a reply this app doesn't understand. It may use an older API version."
        is JointSpaceException.NoPairingInProgress ->
            "Pairing hasn't started yet. Choose \"Pair\" to show a PIN on the TV."
        else -> "Something went wrong: ${error.message ?: error.javaClass.simpleName}"
    }
}

private fun pairingRejected(errorId: String?): String {
    val id = errorId.orEmpty().uppercase()
    return when {
        "PIN" in id ->
            "That PIN wasn't accepted. Check the code on the TV screen and try again. " +
                "If the code has gone, choose \"New PIN\"."
        "TIMEOUT" in id || "EXPIRED" in id ->
            "The PIN expired. Choose \"New PIN\" to get another one."
        "CONCURRENT" in id ->
            "The TV is already pairing with another device. Close that pairing and try again."
        else -> "The TV refused to pair${errorId?.let { " ($it)" }.orEmpty()}. Try again."
    }
}
