package org.yashsriv.ambiframe.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.Build
import org.yashsriv.ambiframe.ambilight.AmbilightConfiguration
import org.yashsriv.ambiframe.ambilight.AmbilightState
import org.yashsriv.ambiframe.ambilight.JointSpaceClient
import org.yashsriv.ambiframe.ambilight.LogcatLogger
import org.yashsriv.ambiframe.ambilight.PairedTv
import org.yashsriv.ambiframe.ambilight.PairingRepository
import org.yashsriv.ambiframe.ambilight.SystemInfo
import org.yashsriv.ambiframe.ambilight.describeChain
import org.yashsriv.ambiframe.ambilight.restoreAmbilight
import org.yashsriv.ambiframe.ambilight.switchOffKeepingStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class SetupStep { Detecting, ManualEntry, Found, EnterPin, Paired }

data class SetupUiState(
    val step: SetupStep = SetupStep.Detecting,
    val host: String? = null,
    val tvName: String? = null,
    /** False for older TVs whose API works without pairing. */
    val needsPairing: Boolean = true,
    val status: String = "Looking for the TV…",
    val error: String? = null,
    val busy: Boolean = false,
    val pin: String = "",
    val manualIp: String = "",
)

/**
 * Drives the setup flow: find the TV's JointSpace API, pair with a PIN, store the credentials
 * and offer an Ambilight on/off test.
 */
class SetupViewModel(application: Application) : AndroidViewModel(application), SetupActions {

    private val repository = PairingRepository.get(application)
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    /** Client for the TV being set up, or for the paired TV once paired. */
    private var client: JointSpaceClient? = null
    private var pairedTv: PairedTv? = null
    private var job: Job? = null

    /** Ambilight as it was before "Test: Ambilight off", so "on" can bring the same style back. */
    private var savedAmbilight: AmbilightState? = null

    init {
        log("setup started on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        launchExclusive {
            val saved = repository.current()
            log(if (saved != null) "stored pairing: host=${saved.host} secure=${saved.secure} port=${saved.port}" else "no stored pairing")
            if (saved != null) showPaired(saved, refresh = true) else detectNow()
        }
    }

    // --- detection --------------------------------------------------------------------------

    override fun detect() = launchExclusive { detectNow() }

    private suspend fun detectNow() {
        _state.update {
            it.copy(step = SetupStep.Detecting, status = "Looking for the TV's Ambilight API…", error = null)
        }
        val hosts = candidateHosts(getApplication())
        log("detect: candidates=$hosts")
        for (host in hosts) {
            _state.update { it.copy(status = "Trying $host…") }
            val found = try {
                probe(host)
            } catch (e: TimeoutCancellationException) {
                log("detect: $host timed out after ${PROBE_TIMEOUT_MS}ms")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("detect: $host failed: ${e.describeChain()}")
                null
            }
            if (found != null) {
                onFound(found.first, found.second)
                return
            }
        }
        _state.update {
            it.copy(
                step = SetupStep.ManualEntry,
                status = "Couldn't find the TV automatically. Enter its IP address.",
            )
        }
    }

    override fun enterManually() {
        job?.cancel()
        _state.update { it.copy(step = SetupStep.ManualEntry, status = "Enter the TV's IP address.", error = null) }
    }

    override fun typeManual(c: Char) = _state.update {
        if (it.manualIp.length >= MAX_IP_LENGTH) it else it.copy(manualIp = it.manualIp + c, error = null)
    }

    override fun backspaceManual() = _state.update { it.copy(manualIp = it.manualIp.dropLast(1)) }

    override fun submitManualIp() {
        val ip = state.value.manualIp.trim()
        if (!isValidIpv4(ip)) {
            _state.update { it.copy(error = "\"$ip\" isn't an IP address. It should look like 192.168.1.20.") }
            return
        }
        launchExclusive {
            _state.update { it.copy(status = "Connecting to $ip…", error = null) }
            runReporting(ip) {
                val (c, info) = probe(ip)
                onFound(c, info)
            }
            if (state.value.step == SetupStep.ManualEntry) {
                _state.update { it.copy(status = "Enter the TV's IP address.") }
            }
        }
    }

    private suspend fun probe(host: String): Pair<JointSpaceClient, SystemInfo> {
        val candidate = JointSpaceClient(host, secure = false, logger = LogcatLogger)
        val info = withTimeout(PROBE_TIMEOUT_MS) { candidate.system() }
        return candidate to info
    }

    private fun onFound(found: JointSpaceClient, info: SystemInfo) {
        client = found
        val name = info.name ?: "Philips TV"
        _state.update {
            it.copy(
                step = SetupStep.Found,
                host = found.host,
                tvName = name,
                needsPairing = found.secure,
                pin = "",
                error = null,
                status = if (found.secure) {
                    "Found $name at ${found.host}. Choose \"Pair\" and a PIN will appear on the TV."
                } else {
                    "Found $name at ${found.host}. This TV doesn't need pairing."
                },
            )
        }
    }

    // --- pairing ----------------------------------------------------------------------------

    override fun pair() = launchExclusive {
        val c = client ?: return@launchExclusive
        val host = c.host
        runReporting(host) {
            if (!c.secure) {
                val tv = PairedTv(host, secure = false, port = c.httpPort, credentials = null,
                    certificateFingerprint = null, tvName = state.value.tvName)
                repository.save(tv)
                showPaired(tv, refresh = true)
                return@runReporting
            }
            _state.update { it.copy(status = "Asking the TV for a PIN…", error = null) }
            val session = c.pairRequest()
            val seconds = session.timeoutSeconds?.let { " You have $it seconds." }.orEmpty()
            _state.update {
                it.copy(step = SetupStep.EnterPin, pin = "", status = "Enter the PIN shown on the TV.$seconds")
            }
        }
    }

    override fun typePin(c: Char) = _state.update {
        if (it.pin.length >= MAX_PIN_LENGTH) it else it.copy(pin = it.pin + c, error = null)
    }

    override fun backspacePin() = _state.update { it.copy(pin = it.pin.dropLast(1)) }

    override fun submitPin() {
        val pin = state.value.pin
        if (pin.length < MIN_PIN_LENGTH) {
            _state.update { it.copy(error = "Enter the $MIN_PIN_LENGTH-digit PIN shown on the TV.") }
            return
        }
        launchExclusive {
            val c = client ?: return@launchExclusive
            _state.update { it.copy(status = "Checking the PIN…", error = null) }
            runReporting(c.host) {
                val result = c.pairGrant(pin)
                val tv = PairedTv(
                    host = c.host,
                    secure = true,
                    port = c.httpsPort,
                    credentials = result.credentials,
                    certificateFingerprint = result.certificateFingerprint,
                    tvName = state.value.tvName,
                )
                repository.save(tv)
                showPaired(tv, refresh = true)
            }
            if (state.value.step == SetupStep.EnterPin) {
                _state.update { it.copy(pin = "", status = "Enter the PIN shown on the TV.") }
            }
        }
    }

    override fun cancelPairing() {
        job?.cancel()
        val c = client
        if (c != null && state.value.host != null) {
            _state.update { it.copy(step = SetupStep.Found, pin = "", error = null, status = "Pairing cancelled.") }
        } else {
            enterManually()
        }
    }

    // --- paired -----------------------------------------------------------------------------

    private suspend fun showPaired(tv: PairedTv, refresh: Boolean) {
        pairedTv = tv
        client = tv.client(LogcatLogger)
        _state.update {
            it.copy(
                step = SetupStep.Paired,
                host = tv.host,
                tvName = tv.tvName,
                needsPairing = tv.secure,
                pin = "",
                error = null,
                status = pairedSummary(tv, null),
            )
        }
        if (refresh) refreshNow()
    }

    override fun refreshStatus() = launchExclusive { refreshNow() }

    private suspend fun refreshNow() {
        val tv = pairedTv ?: return
        val c = client ?: return
        _state.update { it.copy(status = pairedSummary(tv, "Checking Ambilight…"), error = null) }
        runReporting(tv.host) {
            val on = c.getAmbilightPower()
            val style = if (on) runCatching { c.getConfiguration().styleName }.getOrNull() else null
            val ambilight = when {
                !on -> "Ambilight is off."
                style != null -> "Ambilight is on (${style.lowercase().replace('_', ' ')})."
                else -> "Ambilight is on."
            }
            _state.update { it.copy(status = pairedSummary(tv, ambilight)) }
        }
        if (state.value.error != null) _state.update { it.copy(status = pairedSummary(tv, null)) }
    }

    override fun testAmbilight(on: Boolean) = launchExclusive {
        val tv = pairedTv ?: return@launchExclusive
        val c = client ?: return@launchExclusive
        _state.update {
            it.copy(status = pairedSummary(tv, "Turning Ambilight ${if (on) "on" else "off"}…"), error = null)
        }
        runReporting(tv.host) {
            val detail = if (on) restoreForTest(c) else switchOffForTest(c)
            _state.update { it.copy(status = pairedSummary(tv, detail)) }
        }
    }

    private suspend fun switchOffForTest(c: JointSpaceClient): String {
        val before = c.switchOffKeepingStyle()
        log("test off: before=$before")
        if (!before.on) return "Ambilight was already off."
        // Keep the first saved style if "off" is pressed twice.
        savedAmbilight = before
        return "Ambilight should now be off. Its style (${styleLabel(before.configuration)}) is saved for \"on\"."
    }

    private suspend fun restoreForTest(c: JointSpaceClient): String {
        val saved = savedAmbilight
        if (saved != null) {
            log("test on: restoring $saved")
            c.restoreAmbilight(saved)
            savedAmbilight = null
            return "Ambilight should now be on, in its earlier style (${styleLabel(saved.configuration)})."
        }
        if (c.getAmbilightPower()) return "Ambilight is already on."
        log("test on: no saved style, powering on")
        c.setAmbilightPower(true)
        return "Ambilight should now be on. No earlier style was saved, so the TV may use its default."
    }

    private fun styleLabel(configuration: AmbilightConfiguration?): String =
        configuration?.stringValue
            ?: listOfNotNull(configuration?.styleName, configuration?.menuSetting)
                .joinToString(", ") { it.lowercase().replace('_', ' ') }
                .ifEmpty { "unknown" }

    /** Pairs again with the same TV. The old pairing stays stored until the new one succeeds. */
    override fun rePair() = launchExclusive {
        val host = pairedTv?.host ?: state.value.host
        if (host == null) {
            detectNow()
            return@launchExclusive
        }
        _state.update { it.copy(status = "Connecting to $host…", error = null) }
        runReporting(host) {
            val (c, info) = probe(host)
            onFound(c, info)
        }
    }

    override fun forget() {
        job?.cancel()
        launchExclusive {
            repository.forget()
            pairedTv = null
            client = null
            _state.value = SetupUiState(manualIp = state.value.manualIp)
            detectNow()
        }
    }

    // --- plumbing ---------------------------------------------------------------------------

    private fun pairedSummary(tv: PairedTv, detail: String?): String {
        val name = tv.tvName ?: "the TV"
        return listOfNotNull("Paired with $name at ${tv.host}.", detail).joinToString(" ")
    }

    /** Runs [block], turning any failure into a plain-language error on screen. */
    private suspend fun runReporting(host: String?, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: TimeoutCancellationException) {
            log("error (host=$host): timed out")
            _state.update { it.copy(error = plainLanguage(e, host)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("error (host=$host): ${e.describeChain()}")
            _state.update { it.copy(error = plainLanguage(e, host)) }
        }
    }

    private fun log(message: String) = LogcatLogger.log("setup: $message")

    /** One network action at a time; presses while busy are ignored. */
    private fun launchExclusive(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        const val MAX_IP_LENGTH = 15
    }
}
