package org.yashsriv.ambiframe.ambilight

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException

class PairingRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Stand-in for the Keystore cipher, which only exists on a device. */
    private class FakeCipher : SecretCipher {
        var broken = false
        override fun encrypt(plaintext: String) = "enc:" + plaintext.reversed()
        override fun decrypt(ciphertext: String): String {
            if (broken || !ciphertext.startsWith("enc:")) throw GeneralSecurityException("bad key")
            return ciphertext.removePrefix("enc:").reversed()
        }
    }

    private val cipher = FakeCipher()
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        folder.newFile("pairing.preferences_pb").also { it.delete() }
    }
    private val repository = PairingRepository(dataStore, cipher)

    private val tv = PairedTv(
        host = "192.168.1.20",
        secure = true,
        port = 1926,
        credentials = JointSpaceCredentials("AbCdEfGh12345678", "the-auth-key"),
        certificateFingerprint = "ab".repeat(32),
        tvName = "Living room",
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun savesAndReadsBack() = runBlocking {
        assertNull(repository.current())
        repository.save(tv)
        assertEquals(tv, repository.current())
    }

    @Test
    fun authKeyIsStoredEncrypted() = runBlocking {
        repository.save(tv)
        val raw = dataStore.data.first()
        val stored = raw[PairingRepository.AUTH_KEY_ENCRYPTED]!!
        assertFalse(stored.contains("the-auth-key"))
        assertTrue(raw.asMap().values.none { it == "the-auth-key" })
    }

    @Test
    fun forgetClearsEverything() = runBlocking {
        repository.save(tv)
        repository.forget()
        assertNull(repository.current())
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun undecryptableKeyMeansNotPaired() = runBlocking {
        repository.save(tv)
        cipher.broken = true
        assertNull(repository.current())
    }

    @Test
    fun plainHttpTvNeedsNoCredentials() = runBlocking {
        val legacy = tv.copy(secure = false, port = 1925, credentials = null, certificateFingerprint = null)
        repository.save(legacy)
        assertEquals(legacy, repository.current())
    }

    @Test
    fun clientUsesStoredPort() {
        val client = tv.copy(port = 4443).client()
        assertEquals(4443, client.httpsPort)
        assertEquals(JointSpaceClient.DEFAULT_HTTP_PORT, client.httpPort)
        assertEquals(tv.certificateFingerprint, client.certificateFingerprint)
    }
}
