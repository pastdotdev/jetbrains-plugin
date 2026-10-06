package dev.past.jetbrains.junie

/**
 * Whether the person allowed this IDE to send Junie's sessions. Nothing is sent before they say yes,
 * even on a machine another past.dev plugin already connected, so an answer that is missing or
 * unreadable counts as not asked.
 */
enum class JunieConsent {
    NotAsked, Allowed, Refused;

    val sends: Boolean get() = this == Allowed

    companion object {
        fun parse(stored: String?): JunieConsent = entries.firstOrNull { it.name == stored } ?: NotAsked
    }
}
