package com.ambiframe.tv.ambilight

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The `auth_signature` sent with `POST /6/pair/grant`.
 *
 * Reference implementations compute
 * `base64( hex( HMAC-SHA1( base64decode(SECRET), timestamp + pin ) ) )`.
 * Two different secrets circulate (suborb/philips_android_tv and pylips), and pylips' Python 3
 * code wraps the result in `str(bytes)`, so the value it sends is literally `b'…'`. We don't
 * know which form a given firmware accepts, so [variants] lists them in the order to try.
 */
object Signature {

    /** Secret used by suborb/philips_android_tv. Primary. */
    const val SECRET_SUBORB =
        "ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA=="

    /** Secret used by eslavnov/pylips. Fallback. */
    const val SECRET_PYLIPS =
        "JCqdN5AcnAHgJYseUn7ER5k3qgtemfUvMRghQpTfTZq7Cvv8EPQPqfz6dDxPQPSu4gKFPWkJGw32zyASgJkHwCjU"

    /** One way of producing the signature. */
    data class Variant(val name: String, val secret: String, val pythonBytesWrapped: Boolean)

    /** Attempt order for pairing: suborb clean, suborb `b'…'`, pylips clean, pylips `b'…'`. */
    val variants: List<Variant> = listOf(
        Variant("suborb", SECRET_SUBORB, pythonBytesWrapped = false),
        Variant("suborb-wrapped", SECRET_SUBORB, pythonBytesWrapped = true),
        Variant("pylips", SECRET_PYLIPS, pythonBytesWrapped = false),
        Variant("pylips-wrapped", SECRET_PYLIPS, pythonBytesWrapped = true),
    )

    /**
     * Computes the signature for [timestamp] (as returned by `pair/request`) and [pin].
     * When [pythonBytesWrapped] is true the result is wrapped as `b'<value>'`.
     */
    fun sign(
        timestamp: String,
        pin: String,
        secret: String = SECRET_SUBORB,
        pythonBytesWrapped: Boolean = false,
    ): String {
        val key = Base64.getDecoder().decode(secret)
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val digest = mac.doFinal((timestamp + pin).toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val encoded = Base64.getEncoder().encodeToString(hex.toByteArray(Charsets.US_ASCII))
        return if (pythonBytesWrapped) "b'$encoded'" else encoded
    }

    fun sign(timestamp: String, pin: String, variant: Variant): String =
        sign(timestamp, pin, variant.secret, variant.pythonBytesWrapped)
}
