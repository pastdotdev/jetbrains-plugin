package dev.past.jetbrains.junie

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.useLines

/**
 * Where Junie keeps its sessions: `$JUNIE_HOME/sessions`, or `~/.junie/sessions`. One folder per
 * session holds its `events.jsonl`, and `index.jsonl` beside them names each session's project.
 * Only those two files are read. The rest of Junie's folder (its settings, credentials and the
 * snapshots that copy the environment) is never opened.
 */
object JunieStore {
    data class Session(val id: String, val events: Path, val projectDir: String, val modifiedMs: Long)

    fun sessionsDir(): Path {
        val home = System.getenv("JUNIE_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".junie")
        return home.resolve("sessions")
    }

    fun list(): List<Session> {
        val dir = sessionsDir()
        if (!dir.isDirectory()) return emptyList()
        val projects = projectDirs(dir.resolve("index.jsonl"))
        return dir.listDirectoryEntries().mapNotNull { folder ->
            val events = folder.resolve("events.jsonl")
            if (!folder.isDirectory() || !events.exists()) return@mapNotNull null
            // Junie appends to the file at every event, so its time is the session's last activity.
            Session(folder.name, events, projects[folder.name].orEmpty(), Files.getLastModifiedTime(events).toMillis())
        }
    }

    fun read(session: Session): JunieTranscript.Conversation? =
        runCatching { session.events.useLines { JunieTranscript.read(session.id, it, session.projectDir) } }.getOrNull()

    private fun projectDirs(index: Path): Map<String, String> {
        if (!index.exists()) return emptyMap()
        return runCatching {
            index.useLines { lines ->
                lines.mapNotNull { line ->
                    val record = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@mapNotNull null
                    val id = (record["sessionId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    id to (record["projectDir"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }
}
