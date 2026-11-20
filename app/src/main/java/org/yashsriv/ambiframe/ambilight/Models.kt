package org.yashsriv.ambiframe.ambilight

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** JSON settings shared by the client: TVs add fields freely, so unknown keys are ignored. */
internal val JointSpaceJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

/** Response of `GET /6/system`. Only the fields we use are modelled. */
@Serializable
data class SystemInfo(
    val name: String? = null,
    val country: String? = null,
    @SerialName("menulanguage") val menuLanguage: String? = null,
    @SerialName("api_version") val apiVersion: ApiVersion? = null,
    val featuring: Featuring? = null,
) {
    /** True when the TV wants HTTPS on 1926 plus Digest auth (all 2016+ Android TVs). */
    val requiresDigestPairing: Boolean
        get() = featuring?.systemFeatures?.pairingType == PAIRING_TYPE_DIGEST

    companion object {
        const val PAIRING_TYPE_DIGEST = "digest_auth_pairing"
    }
}

@Serializable
data class ApiVersion(
    @SerialName("Major") val major: Int? = null,
    @SerialName("Minor") val minor: Int? = null,
    @SerialName("Patch") val patch: Int? = null,
)

@Serializable
data class Featuring(
    @SerialName("systemfeatures") val systemFeatures: SystemFeatures? = null,
)

@Serializable
data class SystemFeatures(
    @SerialName("pairing_type") val pairingType: String? = null,
)

/** The `device` object sent with both pairing calls. */
@Serializable
data class DeviceSpec(
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_os") val deviceOs: String = "Android",
    @SerialName("app_name") val appName: String = "AmbiFrame",
    val type: String = "native",
    @SerialName("app_id") val appId: String = "org.yashsriv.ambiframe",
    val id: String,
)

@Serializable
internal data class PairRequestBody(
    val scope: List<String> = listOf("read", "write", "control"),
    val device: DeviceSpec,
)

@Serializable
internal data class PairRequestResponse(
    @SerialName("error_id") val errorId: String? = null,
    @SerialName("error_text") val errorText: String? = null,
    @SerialName("auth_key") val authKey: String? = null,
    /** A number on the TVs we know of; kept as a primitive so it round-trips unchanged. */
    val timestamp: JsonPrimitive? = null,
    val timeout: Int? = null,
)

@Serializable
internal data class GrantAuth(
    @SerialName("auth_AppId") val authAppId: String = "1",
    val pin: String,
    @SerialName("auth_timestamp") val authTimestamp: JsonPrimitive,
    @SerialName("auth_signature") val authSignature: String,
)

@Serializable
internal data class PairGrantBody(
    val auth: GrantAuth,
    val device: DeviceSpec,
)

@Serializable
internal data class ErrorResponse(
    @SerialName("error_id") val errorId: String? = null,
    @SerialName("error_text") val errorText: String? = null,
)

@Serializable
internal data class AmbilightPowerBody(val power: String)

/**
 * `GET/POST /6/ambilight/currentconfiguration`. Saved before the screensaver starts and posted
 * back afterwards, so every field the TV reports is kept, including expert colour settings.
 */
@Serializable
data class AmbilightConfiguration(
    val styleName: String? = null,
    val isExpert: Boolean? = null,
    val menuSetting: String? = null,
    val stringValue: String? = null,
    val algorithm: String? = null,
    val colorSettings: JsonObject? = null,
)
