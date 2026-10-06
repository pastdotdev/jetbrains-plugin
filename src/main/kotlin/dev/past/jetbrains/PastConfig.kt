package dev.past.jetbrains

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The connection to past.dev, read from ~/.past/config.json.
 *
 * The file can be shared with the other past.dev plugins on this machine, so one key and one identity
 * serve all of them. This plugin writes it only through [PastConnect], which keeps every field it
 * does not own as it found it. `PAST_API_KEY`, `PAST_API_URL` and `PAST_IDENTITY` in the
 * environment take precedence over the file.
 */
data class PastConfig(
    val apiUrl: String,
    val apiKey: String,
    val identity: String,
    val audience: String,
    val recall: Boolean,
    val ingest: Boolean,
    val deny: List<String>,
    val sittingMinutes: Int,
) {
    val connected: Boolean get() = apiKey.isNotBlank() && identity.isNotBlank()

    /** A project whose folder matches an entry in `deny` is never read. */
    fun denies(projectDir: String): Boolean = deny.any { it.isNotBlank() && projectDir.contains(it) }

    companion object {
        const val DEFAULT_API_URL = "https://api.past.dev"
        const val DEFAULT_SITTING_MINUTES = 30
        const val MIN_SITTING_MINUTES = 5

        val home: Path = Path.of(System.getProperty("user.home"), ".past")
        val path: Path = home.resolve("config.json")

        fun load(): PastConfig = from(readFile(), System.getenv())

        fun from(file: JsonObject, env: Map<String, String>): PastConfig {
            fun text(name: String) = (file[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun flag(name: String) = (file[name] as? JsonPrimitive)?.booleanOrNull ?: true
            fun number(name: String) = (file[name] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            val sitting = number("sittingMinutes")
            return PastConfig(
                apiUrl = (env["PAST_API_URL"] ?: text("apiUrl")).ifBlank { DEFAULT_API_URL }.trimEnd('/'),
                apiKey = env["PAST_API_KEY"] ?: text("apiKey"),
                identity = env["PAST_IDENTITY"] ?: text("identity"),
                audience = text("audience").takeUnless { it == "project" }.orEmpty(),
                recall = flag("recall"),
                ingest = flag("ingest"),
                deny = (file["deny"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                sittingMinutes = if (sitting > 0) maxOf(MIN_SITTING_MINUTES, sitting.toInt()) else DEFAULT_SITTING_MINUTES,
            )
        }

        fun readFile(): JsonObject = try {
            if (path.exists()) Json.parseToJsonElement(path.readText()) as? JsonObject ?: JsonObject(emptyMap())
            else JsonObject(emptyMap())
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }
}
