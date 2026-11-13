package dev.past.jetbrains.junie

import dev.past.jetbrains.Redaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One Junie session read from its `events.jsonl`, as the prose that reaches past.dev.
 *
 * Junie writes every session, in the IDE's AI chat and in its own terminal alike, as an append-only
 * stream of events: one JSON object a line, named by `kind`, timed by `timestampMs`. What a person
 * typed is a `UserPromptEvent`; what Junie answered is a markdown or result block inside a
 * `SessionA2uxEvent`, rewritten under the same `stepId` as it streams. Everything else (tool runs,
 * file edits, the environment Junie records, model usage) stays on the machine. The model a session
 * is set to and its effort level are read too (`SessionModelEffortSetEvent`, and the two attachments
 * a prompt carries): they ride in a sitting's metadata and are never part of the prose.
 */
object JunieTranscript {
    const val SOURCE = "junie"
    const val AGENT = "Junie"
    /** past.dev's own recall block. Read back into memory, past.dev would cite itself a session later. */
    const val MARKER = "=== past · recalled from memory ==="

    enum class Role(val heading: String) { DEVELOPER("Developer"), AGENT("Junie") }

    /**
     * A turn of the conversation; `quietMs` is the longest silence in the session just before it,
     * `model` and `effort` what Junie was set to at it, where the session says.
     */
    data class Turn(val role: Role, val at: String, val text: String, val quietMs: Long, val model: String = "", val effort: String = "")

    data class Conversation(val sessionId: String, val project: String, val cwd: String, val turns: List<Turn>) {
        val firstAt: String get() = turns.first().at
    }

    /** `connection` is the one its prompts were typed under, or null where none was recorded. */
    data class Sitting(val index: Int, val firstAt: String, val turns: List<Turn>, val connection: String? = null)

    /** A connection, a fingerprint of the key and the address (empty with no key), and when it began. */
    data class Span(val atMs: Long, val connection: String)

    data class Part(val sitting: Sitting, val content: String, val hash: String) {
        val bytes: Int get() = content.toByteArray().size
    }

    /**
     * What a send carries now: every sitting rendered, the ones past.dev does not hold yet
     * (`changed`), and the ones that differ too but were typed under another connection, or under
     * none, and stay on this machine (`kept`).
     */
    data class Plan(val whole: String, val minutes: Int, val parts: List<Part>, val changed: List<Part>, val kept: List<Part> = emptyList())

    /** What this machine last sent for a session. */
    data class Sent(val hash: String, val sittings: List<String>, val sittingMinutes: Int)

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** The conversation in one session, or null when nobody said anything worth keeping. */
    fun read(sessionId: String, lines: Sequence<String>, projectDir: String): Conversation? {
        val entries = mutableListOf<Entry>()
        val prompts = mutableMapOf<String, Entry>()
        val blocks = mutableMapOf<String, Entry>()
        var last = Long.MIN_VALUE
        var quiet = 0L
        var model = ""
        var effort = ""

        fun open(role: Role, at: Long, key: String, index: MutableMap<String, Entry>): Entry {
            if (key.isNotEmpty()) index[key]?.let { return it }
            val entry = Entry(role, at, quiet, model, effort)
            quiet = 0
            entries += entry
            if (key.isNotEmpty()) index[key] = entry
            return entry
        }

        for (line in lines) {
            val event = parse(line) ?: continue
            val at = (event["timestampMs"] as? JsonPrimitive)?.longOrNull
            // Every event is activity, the ones that are not sent included: an agent at work is not quiet.
            if (at != null) {
                if (last != Long.MIN_VALUE && at - last > quiet) quiet = at - last
                if (at > last) last = at
            }
            when (event.text("kind")) {
                // What the session is set to run on, written when it starts and when a person changes it.
                "SessionModelEffortSetEvent" -> {
                    event.text("modelId").takeIf { it.isNotBlank() }?.let { model = it.take(100) }
                    effort = event.text("effortWireId").take(40)
                }
                "UserPromptEvent" -> {
                    if (event.text("delivery") == "Failed") continue
                    // A prompt carries the model and the effort it was launched with.
                    for (item in (event["customAttachments"] as? JsonArray).orEmpty()) {
                        val attachment = item as? JsonObject ?: continue
                        when (attachment.text("kind")) {
                            "ModelForLaunchAttachment" -> attachment.text("modelId").takeIf { it.isNotBlank() }?.let { model = it.take(100) }
                            "EffortForLaunchAttachment" -> effort = attachment.text("effort").take(40)
                        }
                    }
                    // The prompt as the person typed it; `prompt` can carry what the IDE attached to it.
                    val typed = event.text("presentablePrompt").ifBlank { event.text("prompt") }
                    open(Role.DEVELOPER, at ?: last, event.text("requestId"), prompts).text = typed
                }
                "UserResponseEvent" -> open(Role.DEVELOPER, at ?: last, "", prompts).text = event.text("prompt")
                "UserAsyncResponseEvent" -> {
                    // Junie asked, the person answered: the answer only reads with its question.
                    val answers = (event["entries"] as? JsonArray).orEmpty().mapNotNull { item ->
                        val pair = item as? JsonObject ?: return@mapNotNull null
                        listOf(pair.text("question"), pair.text("answer")).filter { it.isNotBlank() }.joinToString("\n")
                            .takeIf { it.isNotBlank() }
                    }
                    open(Role.DEVELOPER, at ?: last, "", prompts).text = answers.joinToString("\n\n")
                }
                "SessionA2uxEvent" -> {
                    val block = ((event["event"] as? JsonObject)?.get("agentEvent") as? JsonObject) ?: continue
                    val text = when (block.text("kind")) {
                        "MarkdownBlockUpdatedEvent" -> block.text("text")
                        "ResultBlockUpdatedEvent" -> block.text("result").removePrefix("<!-- ANSWER -->")
                        else -> continue
                    }
                    // A block streams: each update rewrites it under the same step, and the last word stands.
                    open(Role.AGENT, at ?: last, block.text("stepId"), blocks).text = text
                }
            }
        }

        val turns = mutableListOf<Turn>()
        var carried = 0L
        for (entry in entries) {
            val text = entry.text.trim()
            // A dropped entry's silence still happened, so it passes to the next turn that is kept.
            if (text.length < 2 || text.startsWith(MARKER) || entry.at == Long.MIN_VALUE) {
                carried = maxOf(carried, entry.quiet)
                continue
            }
            turns += Turn(entry.role, ISO.format(Instant.ofEpochMilli(entry.at)), text, maxOf(carried, entry.quiet), entry.model, entry.effort)
            carried = 0
        }
        if (turns.isEmpty()) return null
        return Conversation(sessionId, projectDir.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'), projectDir, turns)
    }

    /** The prose of some turns, each with its own time kept in the text, redacted as a whole. */
    fun render(conversation: Conversation, turns: List<Turn> = conversation.turns): String {
        val head = "# $AGENT session — ${conversation.project.ifBlank { "unknown project" }}"
        val body = turns.joinToString("\n\n") { "## ${it.role.heading} · ${it.at}\n${it.text}" }
        return Redaction.redact("$head\n\n$body\n")
    }

    /**
     * A session goes to past.dev in sittings: a new one starts at the first turn after the session was
     * quiet for `minutes`, whoever speaks. past.dev dates every memory at its data point's time, so a
     * decision taken on the third day of a long session is dated that day. A session only grows at
     * its end, so a sitting never changes once the next one has begun.
     *
     * A sitting belongs to one connection: a prompt typed under another than the sitting's starts a
     * new one (`spans`, the connections this machine recorded). A prompt from before any was
     * recorded names none and starts nothing, so a session already in past.dev keeps its boundaries.
     */
    fun sittings(conversation: Conversation, minutes: Int, spans: List<Span> = emptyList()): List<Sitting> {
        class Open(var connection: String?) { val turns = mutableListOf<Turn>() }
        val sittings = mutableListOf<Open>()
        for (turn in conversation.turns) {
            val open = sittings.lastOrNull()
            // Junie's turns stay with the prompt they answer, whatever was connected meanwhile.
            val connection = if (turn.role == Role.DEVELOPER || open == null) connectionAt(spans, turn.at) else open.connection
            val moved = open != null && connection != null && open.connection != null && connection != open.connection
            if (open == null || turn.quietMs >= minutes * 60_000L || moved) sittings += Open(connection)
            val sitting = sittings.last()
            if (sitting.connection == null) sitting.connection = connection
            sitting.turns += turn
        }
        return sittings.mapIndexed { index, sitting -> Sitting(index, sitting.turns.first().at, sitting.turns, sitting.connection) }
    }

    /** The fingerprint of a connection: empty while the machine has no key. */
    fun connectionOf(apiUrl: String, apiKey: String): String =
        if (apiKey.isBlank()) "" else sha256("$apiUrl\n$apiKey").take(16)

    /** The connection in force at a turn: the last one recorded before it, or null before the first. */
    private fun connectionAt(spans: List<Span>, at: String): String? {
        val time = runCatching { Instant.parse(at).toEpochMilli() }.getOrNull() ?: return null
        return spans.lastOrNull { it.atMs <= time }?.connection
    }

    /** The first sitting is the session's own id; the others follow it, numbered from 2. */
    fun sittingId(sessionId: String, index: Int): String = "$SOURCE:$sessionId" + if (index > 0) ":${index + 1}" else ""

    /**
     * A session keeps the sitting length it was first sent with, so a changed setting never moves
     * the boundaries of a session already in past.dev. The hash of the whole rendering says whether
     * anything changed at all; one hash per sitting says which. A sitting is sent only with the
     * connection it was typed under (`connection`, the one in force): the others stay on the machine.
     */
    fun plan(conversation: Conversation, sent: Sent?, sittingMinutes: Int, spans: List<Span> = emptyList(), connection: String = ""): Plan {
        val minutes = lengthOf(sent, sittingMinutes)
        val parts = sittings(conversation, minutes, spans).map { sitting ->
            val content = render(conversation, sitting.turns)
            Part(sitting, content, sha256(content))
        }
        val whole = sha256(render(conversation))
        if (sent?.hash == whole) return Plan(whole, minutes, parts, emptyList())
        val known = sent?.sittings.orEmpty()
        val differing = parts.filter { known.getOrNull(it.sitting.index) != it.hash }
        fun mine(part: Part) = part.sitting.connection == null || (connection.isNotEmpty() && part.sitting.connection == connection)
        return Plan(whole, minutes, parts, differing.filter(::mine), differing.filterNot(::mine))
    }

    /** The sitting length a session is cut with: the one it was first sent with, or the setting. */
    fun lengthOf(sent: Sent?, sittingMinutes: Int): Int = sent?.sittingMinutes?.takeIf { it > 0 } ?: sittingMinutes

    /** Every data point is priced on its own and rounds up on its own. */
    fun credits(parts: List<Part>): Int = parts.sumOf { (it.bytes + 349) / 350 }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private class Entry(val role: Role, val at: Long, val quiet: Long, val model: String, val effort: String) {
        var text: String = ""
    }

    private fun parse(line: String): JsonObject? =
        if (line.isBlank()) null else runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()

    private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
