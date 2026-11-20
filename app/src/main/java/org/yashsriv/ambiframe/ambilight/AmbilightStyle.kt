package org.yashsriv.ambiframe.ambilight

import kotlinx.serialization.Serializable

/**
 * What Ambilight was doing before we switched it off: whether it was on, and its exact style.
 * Serializable so the screensaver can persist it and restore it after a process death.
 */
@Serializable
data class AmbilightState(
    val on: Boolean,
    val configuration: AmbilightConfiguration?,
)

/** Reads the current power and, when on, the current style. */
suspend fun JointSpaceClient.captureAmbilight(): AmbilightState {
    val on = getAmbilightPower()
    return AmbilightState(on, if (on) getConfiguration() else null)
}

/**
 * Captures the current state, then switches Ambilight off. Returns the captured state for
 * [restoreAmbilight]. Does nothing beyond the capture when Ambilight is already off.
 */
suspend fun JointSpaceClient.switchOffKeepingStyle(): AmbilightState {
    val before = captureAmbilight()
    if (before.on) setAmbilightPower(false)
    return before
}

/**
 * Puts Ambilight back the way [state] describes. `{"power":"On"}` alone brings the lights back in
 * the TV's default style, so the saved configuration is posted instead, which switches the lights
 * on in that style. If the TV is still off afterwards, it is powered on and the style posted again.
 * Without a saved configuration, falls back to plain power on.
 */
suspend fun JointSpaceClient.restoreAmbilight(state: AmbilightState) {
    if (!state.on) return
    val configuration = state.configuration
    if (configuration == null) {
        setAmbilightPower(true)
        return
    }
    postConfigurationLeniently(configuration)
    if (!getAmbilightPower()) {
        setAmbilightPower(true)
        postConfigurationLeniently(configuration)
    }
}

/**
 * Posts [configuration]. `stringValue` is the TV's display label for the style; if a firmware
 * rejects it on the way back in, retry without it.
 */
private suspend fun JointSpaceClient.postConfigurationLeniently(configuration: AmbilightConfiguration) {
    try {
        setConfiguration(configuration)
    } catch (e: JointSpaceException.HttpError) {
        if (configuration.stringValue == null) throw e
        setConfiguration(configuration.copy(stringValue = null))
    }
}
