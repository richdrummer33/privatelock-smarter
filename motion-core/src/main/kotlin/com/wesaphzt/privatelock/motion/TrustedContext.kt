package com.wesaphzt.privatelock.motion

/**
 * The result of asking one trust source whether the device is somewhere
 * familiar.
 *
 * ## What trust means here, and what it does not
 *
 * Trusted context **only ever lengthens the screen-off grace period**. It can
 * never unlock the device, never shorten a lock, and never suppress a
 * high-confidence pickup or a drop. This is a convenience feature, and the
 * asymmetry is deliberate: every signal available for deciding "am I at home"
 * -- Wi-Fi SSID, BSSID, a paired Bluetooth device, a charger -- is trivially
 * spoofable by an attacker who controls the surrounding radio environment. An
 * SSID is a broadcast string anyone can copy. A BSSID is a MAC address anyone
 * can set. Treating any of them as authentication would hand an attacker a
 * way to *weaken* the device's security by standing next to it with a
 * hotspot.
 *
 * Used only to stretch a grace period, the worst an attacker gains by
 * perfectly forging every trust signal is the grace period the user already
 * chose to accept at home. That is a bounded, understandable loss.
 */
data class TrustSignal(
    /** Whether this source currently considers the surroundings familiar. */
    val trusted: Boolean,
    /** Short identifier for the source, e.g. "wifi", "bluetooth", "charging". */
    val source: String,
    /**
     * How much this source should be believed, 0..1. Never authentication --
     * see the class documentation. Used only for display and for deciding
     * which source to name in the diagnostics line.
     */
    val confidence: Float = 0f,
    /** Human-readable detail, e.g. "matched saved BSSID". Never a raw secret. */
    val detail: String = "",
) {
    companion object {
        fun untrusted(source: String, detail: String = "") =
            TrustSignal(trusted = false, source = source, confidence = 0f, detail = detail)
    }
}

/**
 * One source of contextual trust.
 *
 * Kept as an interface with a single method so Wi-Fi, Bluetooth, charging
 * state and a manual toggle stay independent implementations that can be
 * enabled, disabled and tested separately. Nothing here knows about Android.
 */
interface TrustedContextProvider {
    /** Stable identifier, also used as the settings key. */
    val id: String

    /** Whether the user has enabled this source. */
    val enabled: Boolean

    /**
     * Current reading. Must not block: implementations cache the last value
     * observed from a callback rather than querying synchronously.
     */
    fun currentSignal(): TrustSignal
}

/**
 * Combines several providers.
 *
 * Any single enabled provider reporting trust is enough. Trust only lengthens
 * a grace period, so requiring agreement between sources would add complexity
 * without adding security -- and would make the feature fail confusingly when,
 * say, the user's Bluetooth headphones are charging in another room.
 */
class TrustedContextAggregator(
    private val providers: List<TrustedContextProvider>,
) {
    data class Result(
        val trusted: Boolean,
        val signals: List<TrustSignal>,
    ) {
        val trustedSources: List<String> get() = signals.filter { it.trusted }.map { it.source }

        /** Diagnostics line, e.g. "trusted Wi-Fi active". */
        fun describe(): String = when {
            !trusted -> "no trusted context"
            else -> signals.filter { it.trusted }
                .joinToString(", ") { s -> if (s.detail.isNotEmpty()) "${s.source} (${s.detail})" else s.source }
        }
    }

    fun evaluate(): Result {
        val signals = providers.filter { it.enabled }.map { it.currentSignal() }
        return Result(trusted = signals.any { it.trusted }, signals = signals)
    }

    companion object {
        val NONE = TrustedContextAggregator(emptyList())
    }
}
