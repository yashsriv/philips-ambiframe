package com.ambiframe.tv.ambilight

import java.io.IOException

/** Everything [JointSpaceClient] throws. Each case maps to one plain-language message in the UI. */
sealed class JointSpaceException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** Connection refused, no route, DNS failure or timeout. */
    class Unreachable(val host: String, cause: Throwable?) :
        JointSpaceException("Can't reach the TV at $host: ${cause?.message}", cause) {
        val timedOut: Boolean get() = cause is java.net.SocketTimeoutException
    }

    /** The TV's certificate differs from the one pinned at pairing time. */
    class CertificateChanged(val expected: String, val actual: String, cause: Throwable?) :
        JointSpaceException("The TV's certificate changed since pairing", cause)

    /** Digest credentials were rejected (401 after the challenge was answered). */
    class Unauthorized : JointSpaceException("The TV rejected our credentials")

    /** The TV answered a pairing call with an `error_id` other than `SUCCESS`. */
    class PairingRejected(val errorId: String?, val errorText: String?) :
        JointSpaceException("Pairing rejected: ${errorId ?: "unknown"} ${errorText.orEmpty()}".trim())

    /** Any other non-2xx status. */
    class HttpError(val code: Int, val body: String?) :
        JointSpaceException("TV answered HTTP $code")

    /** The TV answered 2xx but the body wasn't what we expected. */
    class BadResponse(message: String, cause: Throwable? = null) : JointSpaceException(message, cause)

    /** [JointSpaceClient.pairGrant] was called without a preceding pair request. */
    class NoPairingInProgress : JointSpaceException("Start pairing first")
}
