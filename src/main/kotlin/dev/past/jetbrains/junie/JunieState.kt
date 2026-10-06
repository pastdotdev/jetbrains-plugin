@file:OptIn(ExperimentalSerializationApi::class)

package dev.past.jetbrains.junie

import dev.past.jetbrains.PastConfig
import dev.past.jetbrains.PrivateFiles
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * What this machine has sent of Junie's sessions, in ~/.past/junie/state.json.
 *
 * Two IDEs can run the plugin at once (Rider and IntelliJ IDEA, say), and both watch the same
 * sessions. Every read-change-write happens under [locked], which holds a file lock across
 * processes, so a session is sent by one of them only.
 */
object JunieState {
    data class Record(
        val hash: String,
        val sittings: List<String>,
        val sittingMinutes: Int,
        val sentAtMs: Long,
        val turns: Int,
    ) {
        fun toSent() = JunieTranscript.Sent(hash, sittings, sittingMinutes)
    }

    data class Failure(val atMs: Long, val status: String, val code: String)

    /**
     * `sinceMs` is when capture first ran on this machine. Sessions last active before it are the
     * history, and are sent only when the person asks for it.
     */
    data class State(val sinceMs: Long, val sessions: Map<String, Record>, val lastError: Failure?)

    private val dir = PastConfig.home.resolve("junie")
    private val file = dir.resolve("state.json")
    private val lockFile = dir.resolve("state.lock")
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val inProcess = Any()

    fun <T> locked(block: () -> T): T = synchronized(inProcess) {
        PrivateFiles.directory(dir)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }

    fun load(): State {
        val root = runCatching { if (file.exists()) Json.parseToJsonElement(file.readText()) as? JsonObject else null }.getOrNull()
            ?: return State(0, emptyMap(), null)
        val sessions = (root["sessions"] as? JsonObject).orEmpty().mapNotNull { (id, value) ->
            val record = value as? JsonObject ?: return@mapNotNull null
            id to Record(
                hash = record.text("hash"),
                sittings = (record["sittings"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() },
                sittingMinutes = (record["sittingMinutes"] as? JsonPrimitive)?.intOrNull ?: 0,
                sentAtMs = (record["sentAtMs"] as? JsonPrimitive)?.longOrNull ?: 0,
                turns = (record["turns"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }.toMap()
        val error = (root["lastError"] as? JsonObject)?.let {
            Failure((it["atMs"] as? JsonPrimitive)?.longOrNull ?: 0, it.text("status"), it.text("code"))
        }
        return State((root["sinceMs"] as? JsonPrimitive)?.longOrNull ?: 0, sessions, error)
    }

    fun save(state: State) {
        val root = buildJsonObject {
            put("sinceMs", state.sinceMs)
            putJsonObject("sessions") {
                for ((id, record) in state.sessions) putJsonObject(id) {
                    put("hash", record.hash)
                    putJsonArray("sittings") { record.sittings.forEach { add(JsonPrimitive(it)) } }
                    put("sittingMinutes", record.sittingMinutes)
                    put("sentAtMs", record.sentAtMs)
                    put("turns", record.turns)
                }
            }
            state.lastError?.let { error ->
                putJsonObject("lastError") {
                    put("atMs", error.atMs)
                    put("status", error.status)
                    put("code", error.code)
                }
            }
        }
        PrivateFiles.write(file, pretty.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
