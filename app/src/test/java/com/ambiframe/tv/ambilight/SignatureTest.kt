package com.ambiframe.tv.ambilight

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected values come from the reference algorithm, computed outside Kotlin. Equivalent Python:
 *
 * ```python
 * import hmac, hashlib, base64
 * def sig(secret, ts, pin):
 *     h = hmac.new(base64.b64decode(secret), (ts + pin).encode(), hashlib.sha1).hexdigest()
 *     return base64.b64encode(h.encode())   # pylips then does str(...) -> "b'...'"
 * ```
 *
 * (Generated here with `openssl dgst -sha1 -mac HMAC -macopt hexkey:<base64-decoded secret>`
 * piped into `base64`, which computes the same thing.)
 */
class SignatureTest {

    @Test
    fun suborbSecretMatchesReferenceVectors() {
        assertEquals(
            "N2M0NTYzOWE1NjgzMmE2MTQzMDIzZDRjODUwOGNlYmFlZmUyNzU0Nw==",
            Signature.sign("1234567890", "5555", Signature.SECRET_SUBORB),
        )
        assertEquals(
            "MmY4MzhmMzkwNjE0NmQ1NzQ2MzczMGI4NDZjZjk1NjliZjNkM2M4MA==",
            Signature.sign("1700000000", "0042", Signature.SECRET_SUBORB),
        )
    }

    @Test
    fun pylipsSecretMatchesReferenceVectors() {
        assertEquals(
            "MDc5MmVkZDI2OTlhMmU3NTk1NjhmYWIyNmY2MzE4OGYxMGRkMDFhNw==",
            Signature.sign("1234567890", "5555", Signature.SECRET_PYLIPS),
        )
        assertEquals(
            "MDY3MjZhYmJmYTllNGUxZjM0NDQ0NzQ2OTJhM2MwZmZiNzlhMTI4Ng==",
            Signature.sign("1700000000", "0042", Signature.SECRET_PYLIPS),
        )
    }

    @Test
    fun wrappedFormMatchesPythonStrOfBytes() {
        assertEquals(
            "b'N2M0NTYzOWE1NjgzMmE2MTQzMDIzZDRjODUwOGNlYmFlZmUyNzU0Nw=='",
            Signature.sign("1234567890", "5555", Signature.SECRET_SUBORB, pythonBytesWrapped = true),
        )
        assertEquals(
            "b'MDc5MmVkZDI2OTlhMmU3NTk1NjhmYWIyNmY2MzE4OGYxMGRkMDFhNw=='",
            Signature.sign("1234567890", "5555", Signature.variants.single { it.name == "pylips-wrapped" }),
        )
    }

    @Test
    fun variantsTrySuborbCleanFirst() {
        assertEquals(
            listOf("suborb", "suborb-wrapped", "pylips", "pylips-wrapped"),
            Signature.variants.map { it.name },
        )
    }

    @Test
    fun leadingZerosInPinAreSigned() {
        // "0042" and "42" must sign differently: the PIN is concatenated as text.
        val a = Signature.sign("1700000000", "0042")
        val b = Signature.sign("1700000000", "42")
        assert(a != b)
    }
}
