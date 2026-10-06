package dev.past.jetbrains.junie

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import dev.past.jetbrains.Ide
import dev.past.jetbrains.PastApi
import dev.past.jetbrains.PastConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends Junie's sessions to past.dev while the IDE runs.
 *
 * Junie runs no hook inside the IDE, so nothing says when a session is over. Once a minute this
 * looks at every session that changed since it was last sent, and sends the ones that have been
 * quiet for their sitting length (`sittingMinutes`, thirty minutes by default), or were left by an
 * earlier run of the IDE. Quiet for that long, a session cannot change what it sends: whatever is
 * said next starts a new sitting, so the sittings already in past.dev keep their hash and are never
 * sent again. The IDE quitting sends what waits at once ([sendOnQuit]).
 *
 * Nothing is sent on its own before the person allows it ([consent]).
 *
 * Sessions last active before capture first ran on this machine are the history. They are sent only
 * when the person asks, after seeing what it costs ([history], [sendHistory]).
 */
@Service(Service.Level.APP)
class JunieCapture(private val scope: CoroutineScope) {
    private val ideStartedMs = System.currentTimeMillis()
    private val started = AtomicBoolean(false)

    /** The person's answer in this IDE; until it is [JunieConsent.Allowed], nothing is sent on its own. */
    var consent: JunieConsent
        get() = JunieConsent.parse(PropertiesComponent.getInstance().getValue(CONSENT_KEY))
        set(value) = PropertiesComponent.getInstance().setValue(CONSENT_KEY, value.name)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { sendWaiting(now = false) }.onFailure { log.warn("Junie capture failed", it) }
                delay(60_000)
            }
        }
    }

    /**
     * Sends the sessions that changed since their last send. With `now`, every one of them goes,
     * idle or not; otherwise only the ones that are over. Blocking: call it off the UI thread.
     */
    fun sendWaiting(now: Boolean): Int {
        val config = PastConfig.load()
        if (!consent.sends || !config.ingest || !config.connected) return 0
        return JunieState.locked {
            var state = JunieState.load()
            val clock = System.currentTimeMillis()
            if (state.sinceMs == 0L) {
                state = state.copy(sinceMs = clock)
                JunieState.save(state)
            }
            // After a failure the API is left alone for a while, so a refusal does not repeat every minute.
            val resting = state.lastError?.let { clock - it.atMs < RETRY_AFTER_MS } ?: false
            if (resting && !now) return@locked 0
            var sent = 0
            for (session in waiting(config, state)) {
                val over = session.modifiedMs < ideStartedMs || clock >= dueMs(config, state, session)
                if (!now && !over) continue
                val (next, didSend) = send(config, state, session)
                // A refused or unreachable send stops the round: the next session would meet the same answer.
                val failed = next.lastError != null && next.lastError != state.lastError
                state = next
                JunieState.save(state)
                if (didSend) sent++
                if (failed) break
            }
            sent
        }
    }

    data class History(val sessions: Int, val sittings: Int, val kilobytes: Double, val credits: Int)

    /** What sending the history would carry: the sessions from before capture began, never sent. */
    fun history(): History {
        val config = PastConfig.load()
        val sessions = historySessions(config, JunieState.load())
        val parts = sessions.mapNotNull { JunieStore.read(it) }
            .flatMap { JunieTranscript.plan(it, null, config.sittingMinutes).changed }
        return History(sessions.size, parts.size, parts.sumOf { it.bytes } / 1024.0, JunieTranscript.credits(parts))
    }

    fun sendHistory(): Int {
        val config = PastConfig.load()
        if (!config.ingest || !config.connected) return 0
        return JunieState.locked {
            var state = JunieState.load()
            var sent = 0
            for (session in historySessions(config, state)) {
                val (next, didSend) = send(config, state, session)
                state = next
                JunieState.save(state)
                if (didSend) sent++
            }
            sent
        }
    }

    data class Status(val sent: Int, val waiting: Int, val nextSendMs: Long?, val history: Int, val lastError: JunieState.Failure?)

    fun status(): Status {
        val config = PastConfig.load()
        val state = JunieState.load()
        val waiting = if (state.sinceMs == 0L) emptyList() else waiting(config, state)
        val next = waiting.map { dueMs(config, state, it) }.minOrNull()
        return Status(state.sessions.count { it.value.hash.isNotEmpty() }, waiting.size, next,
            if (state.sinceMs == 0L) 0 else historySessions(config, state).size, state.lastError)
    }

    /**
     * Sends what waits as the IDE quits, the Junie sessions in its chat ending with it. Shutdown
     * waits a few seconds at most; whatever is not sent by then goes at the next start.
     */
    fun sendOnQuit() {
        val worker = Thread({ runCatching { sendWaiting(now = true) } }, "past: send Junie sessions on quit")
        worker.isDaemon = true
        worker.start()
        worker.join(QUIT_BUDGET_MS)
    }

    /** When a session counts as over: its last event plus its sitting length. */
    private fun dueMs(config: PastConfig, state: JunieState.State, session: JunieStore.Session): Long =
        session.modifiedMs + JunieTranscript.lengthOf(state.sessions[session.id]?.toSent(), config.sittingMinutes) * 60_000L

    /** Sessions active since capture began that changed after their last send. */
    private fun waiting(config: PastConfig, state: JunieState.State): List<JunieStore.Session> =
        JunieStore.list().filter { session ->
            session.modifiedMs >= state.sinceMs &&
                session.modifiedMs > (state.sessions[session.id]?.sentAtMs ?: 0L) &&
                !config.denies(session.projectDir)
        }

    private fun historySessions(config: PastConfig, state: JunieState.State): List<JunieStore.Session> {
        val since = if (state.sinceMs == 0L) Long.MAX_VALUE else state.sinceMs
        return JunieStore.list().filter { it.modifiedMs < since && it.id !in state.sessions && !config.denies(it.projectDir) }
    }

    private fun send(config: PastConfig, state: JunieState.State, session: JunieStore.Session): Pair<JunieState.State, Boolean> {
        val clock = System.currentTimeMillis()
        val conversation = JunieStore.read(session)
        val record = state.sessions[session.id]
        // Nothing a person said (an empty session, a cancelled prompt): noted, so it is read again
        // only when it changes.
        if (conversation == null) {
            val noted = record?.copy(sentAtMs = clock) ?: JunieState.Record("", emptyList(), 0, clock, 0)
            return state.copy(sessions = state.sessions + (session.id to noted)) to false
        }
        val plan = JunieTranscript.plan(conversation, record?.toSent(), config.sittingMinutes)
        // past.dev charges nothing for an unchanged send, but the call would still spend a request.
        if (plan.changed.isEmpty()) {
            val noted = record?.copy(sentAtMs = clock) ?: JunieState.Record(plan.whole, plan.parts.map { it.hash }, plan.minutes, clock, conversation.turns.size)
            return state.copy(sessions = state.sessions + (session.id to noted)) to false
        }
        val label = "${JunieTranscript.AGENT} · ${conversation.project.ifBlank { "unknown project" }}"
        val items = JsonArray(plan.changed.map { part ->
            buildJsonObject {
                put("id", JunieTranscript.sittingId(session.id, part.sitting.index))
                put("content", part.content)
                put("label", label)
                put("timestamp", part.sitting.firstAt)
                put("identity", config.identity)
                if (config.audience.isNotEmpty()) put("audience", config.audience)
                putJsonObject("metadata") {
                    put("source", JunieTranscript.SOURCE)
                    put("client", JunieTranscript.SOURCE)
                    put("conversationId", session.id)
                    put("sessionId", session.id)
                    put("project", conversation.project)
                    put("cwd", conversation.cwd)
                    put("sitting", part.sitting.index + 1)
                    put("turns", part.sitting.turns.size)
                    // The IDE that sent it, which every IDE with the plugin may be: they all watch the
                    // same sessions, so it is not always where the conversation took place.
                    if (Ide.name.isNotEmpty()) put("sentFrom", Ide.name)
                }
            }
        })
        return when (val reply = PastApi.ingestBatch(config, items)) {
            is PastApi.Reply.Ok -> {
                val sent = JunieState.Record(plan.whole, plan.parts.map { it.hash }, plan.minutes, clock, conversation.turns.size)
                state.copy(sessions = state.sessions + (session.id to sent), lastError = null) to true
            }
            is PastApi.Reply.Refused -> state.copy(lastError = JunieState.Failure(clock, reply.status.toString(), reply.code)) to false
            PastApi.Reply.Unreachable -> state.copy(lastError = JunieState.Failure(clock, "unreachable", "")) to false
        }
    }

    companion object {
        private const val CONSENT_KEY = "dev.past.jetbrains.junie.consent"
        private const val RETRY_AFTER_MS = 15 * 60_000L
        private const val QUIT_BUDGET_MS = 5_000L
        private val log = logger<JunieCapture>()

        fun getInstance(): JunieCapture = service()
    }
}
