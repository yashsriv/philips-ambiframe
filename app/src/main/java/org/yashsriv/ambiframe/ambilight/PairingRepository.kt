package org.yashsriv.ambiframe.ambilight

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.GeneralSecurityException

/** A TV we are paired with: everything needed to build a [JointSpaceClient] for it. */
data class PairedTv(
    val host: String,
    /** HTTPS + Digest on [port] when true; plain HTTP without auth when false (pre-2016 TVs). */
    val secure: Boolean,
    val port: Int,
    /** Null for TVs that don't use digest pairing. */
    val credentials: JointSpaceCredentials?,
    val certificateFingerprint: String?,
    val tvName: String?,
) {
    fun client(logger: JointSpaceLogger = JointSpaceLogger.NONE): JointSpaceClient = JointSpaceClient(
        host = host,
        secure = secure,
        credentials = credentials,
        certificateFingerprint = certificateFingerprint,
        httpPort = if (secure) JointSpaceClient.DEFAULT_HTTP_PORT else port,
        httpsPort = if (secure) port else JointSpaceClient.DEFAULT_HTTPS_PORT,
        logger = logger,
    )
}

/**
 * Persists the pairing in DataStore. The `auth_key` (the Digest password) is encrypted with
 * [cipher] (a Keystore AES key in production); everything else is not secret and stored as is.
 * If the key is lost (for example after "clear data" of the Keystore), the pairing reads as
 * absent and the user pairs again.
 */
class PairingRepository(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    val pairedTv: Flow<PairedTv?> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::decode)

    suspend fun current(): PairedTv? = pairedTv.first()

    suspend fun save(tv: PairedTv) {
        val encryptedKey = tv.credentials?.let { cipher.encrypt(it.authKey) }
        dataStore.edit { prefs ->
            prefs.clear()
            prefs[HOST] = tv.host
            prefs[SECURE] = tv.secure
            prefs[PORT] = tv.port
            tv.credentials?.let { prefs[DEVICE_ID] = it.deviceId }
            encryptedKey?.let { prefs[AUTH_KEY_ENCRYPTED] = it }
            tv.certificateFingerprint?.let { prefs[CERT_SHA256] = it }
            tv.tvName?.let { prefs[TV_NAME] = it }
            prefs[PAIRED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun forget() {
        dataStore.edit { it.clear() }
    }

    private fun decode(prefs: Preferences): PairedTv? {
        val host = prefs[HOST] ?: return null
        val secure = prefs[SECURE] ?: true
        val deviceId = prefs[DEVICE_ID]
        val encryptedKey = prefs[AUTH_KEY_ENCRYPTED]
        val credentials = if (deviceId != null && encryptedKey != null) {
            val authKey = try {
                cipher.decrypt(encryptedKey)
            } catch (_: GeneralSecurityException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            JointSpaceCredentials(deviceId, authKey)
        } else {
            null
        }
        // A secure TV without usable credentials is not paired.
        if (secure && credentials == null) return null
        return PairedTv(
            host = host,
            secure = secure,
            port = prefs[PORT] ?: if (secure) JointSpaceClient.DEFAULT_HTTPS_PORT else JointSpaceClient.DEFAULT_HTTP_PORT,
            credentials = credentials,
            certificateFingerprint = prefs[CERT_SHA256],
            tvName = prefs[TV_NAME],
        )
    }

    companion object {
        internal val HOST = stringPreferencesKey("host")
        internal val SECURE = booleanPreferencesKey("secure")
        internal val PORT = intPreferencesKey("port")
        internal val DEVICE_ID = stringPreferencesKey("device_id")
        internal val AUTH_KEY_ENCRYPTED = stringPreferencesKey("auth_key_enc")
        internal val CERT_SHA256 = stringPreferencesKey("cert_sha256")
        internal val TV_NAME = stringPreferencesKey("tv_name")
        internal val PAIRED_AT = longPreferencesKey("paired_at")

        @Volatile
        private var instance: PairingRepository? = null

        fun get(context: Context): PairingRepository =
            instance ?: synchronized(this) {
                instance ?: PairingRepository(
                    context.applicationContext.pairingDataStore,
                    KeystoreSecretCipher(),
                ).also { instance = it }
            }
    }
}

private val Context.pairingDataStore: DataStore<Preferences> by preferencesDataStore(name = "pairing")
