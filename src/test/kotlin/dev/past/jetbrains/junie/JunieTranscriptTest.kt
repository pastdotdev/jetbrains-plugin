package dev.past.jetbrains.junie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Junie reader over a session written in Junie's event format: one JSON object a line, named by
 * `kind`, timed by `timestampMs`, answers streamed as blocks under a `stepId`.
 */
class JunieTranscriptTest {
    private val start = 1_790_000_000_000L // 2026-09-21T14:13:20Z

    private fun at(minutes: Int, seconds: Int = 0) = start + minutes * 60_000L + seconds * 1_000L

    private fun prompt(ms: Long, requestId: String, typed: String, sent: String = typed, delivery: String = "Delivered") =
        """{"kind":"UserPromptEvent","timestampMs":$ms,"requestId":"$requestId","presentablePrompt":"$typed","prompt":"$sent","delivery":"$delivery"}"""

    private fun block(ms: Long, kind: String, stepId: String, field: String, text: String) =
        """{"kind":"SessionA2uxEvent","timestampMs":$ms,"event":{"state":"IN_PROGRESS","agentEvent":{"kind":"$kind","stepId":"$stepId","$field":"$text"}}}"""

    private fun tool(ms: Long, stepId: String) =
        """{"kind":"SessionA2uxEvent","timestampMs":$ms,"event":{"state":"IN_PROGRESS","agentEvent":{"kind":"McpBlockUpdatedEvent","stepId":"$stepId","details":"ran the build"}}}"""

    private val environment =
        """{"kind":"SessionA2uxEvent","timestampMs":${start + 1},"event":{"agentEvent":{"kind":"EnvironmentVariablesUpdatedEvent","env":{"SECRET_TOKEN":"abc"}}}}"""

    private fun read(vararg lines: String) = JunieTranscript.read("session-1", lines.asSequence(), "/work/shop")

    @Test
    fun `keeps what the person typed and what Junie answered, nothing else`() {
        val conversation = read(
            prompt(at(0), "r1", "Why does the cart drop items?", sent = "Why does the cart drop items?\\n<ide context>"),
            environment,
            tool(at(1), "t1"),
            block(at(2), "MarkdownBlockUpdatedEvent", "s1", "text", "Looking"),
            block(at(2, 30), "MarkdownBlockUpdatedEvent", "s1", "text", "The cache key ignores the user id."),
            block(at(3), "ResultBlockUpdatedEvent", "s2", "result", "<!-- ANSWER -->Fixed in CartCache."),
            "not json at all",
        )!!

        assertEquals("shop", conversation.project)
        assertEquals(
            listOf("Why does the cart drop items?", "The cache key ignores the user id.", "Fixed in CartCache."),
            conversation.turns.map { it.text },
        )
        // A block keeps the time it started, so a rewritten answer does not move in the transcript.
        assertEquals("2026-09-21T14:15:20.000Z", conversation.turns[1].at)
        assertFalse(JunieTranscript.render(conversation).contains("ran the build"))
        assertFalse(JunieTranscript.render(conversation).contains("SECRET_TOKEN"))
    }

    @Test
    fun `renders the session as every other plugin does, redacted`() {
        val conversation = read(
            prompt(at(0), "r1", "Use past_sk_0123456789abcdefghij for the test"),
            block(at(1), "MarkdownBlockUpdatedEvent", "s1", "text", "Done. API_KEY=hunter2 is set."),
        )!!

        assertEquals(
            "# Junie session — shop\n\n" +
                "## Developer · 2026-09-21T14:13:20.000Z\nUse [redacted] for the test\n\n" +
                "## Junie · 2026-09-21T14:14:20.000Z\nDone. API_KEY=[redacted] is set.\n",
            JunieTranscript.render(conversation),
        )
    }

    @Test
    fun `redacts a claim token as it redacts a key`() {
        // A claim token is past_ct_ and 43 base64url characters, which can hold a dash or an underscore.
        val conversation = read(
            prompt(at(0), "r1", "The claim token is past_ct_0123456789-abcdefghijklmnopqrstuvwxyz_ABCDE"),
            block(at(1), "MarkdownBlockUpdatedEvent", "s1", "text", "Keep past_sk_0123456789abcdefghij, never past_mk_0123456789abcdefghij."),
        )!!

        assertEquals(
            "# Junie session — shop\n\n" +
                "## Developer · 2026-09-21T14:13:20.000Z\nThe claim token is [redacted]\n\n" +
                "## Junie · 2026-09-21T14:14:20.000Z\nKeep [redacted], never [redacted].\n",
            JunieTranscript.render(conversation),
        )
    }

    @Test
    fun `leaves a name that only starts like a claim token as it is`() {
        // Only past_ct_, underscore included, starts a claim token. These names keep every byte, so a
        // session that holds them keeps its hash and is not sent again.
        val conversation = read(
            prompt(at(0), "r1", "Rename past_ctx_identifier_value to past_ctx_owner_reference"),
        )!!

        assertEquals(
            "# Junie session — shop\n\n" +
                "## Developer · 2026-09-21T14:13:20.000Z\nRename past_ctx_identifier_value to past_ctx_owner_reference\n",
            JunieTranscript.render(conversation),
        )
    }

    @Test
    fun `leaves out failed prompts and the recall block`() {
        val conversation = read(
            prompt(at(0), "r1", "This one never left", delivery = "Failed"),
            prompt(at(1), "r2", "=== past · recalled from memory === something"),
        )
        assertNull(conversation)
    }

    @Test
    fun `starts a sitting after the session went quiet, unless Junie was at work`() {
        val conversation = read(
            prompt(at(0), "r1", "First question"),
            block(at(1), "MarkdownBlockUpdatedEvent", "s1", "text", "First answer"),
            // Forty quiet minutes: the next prompt starts a sitting.
            prompt(at(41), "r2", "Back after lunch"),
            block(at(42), "MarkdownBlockUpdatedEvent", "s2", "text", "Second answer"),
            // Junie works for an hour, with a tool event every twenty minutes: no sitting starts.
            tool(at(62), "t1"),
            tool(at(82), "t2"),
            block(at(100), "MarkdownBlockUpdatedEvent", "s3", "text", "Long job finished"),
        )!!

        val sittings = JunieTranscript.sittings(conversation, 30)
        assertEquals(2, sittings.size)
        assertEquals(listOf("First question", "First answer"), sittings[0].turns.map { it.text })
        assertEquals("2026-09-21T14:54:20.000Z", sittings[1].firstAt)
        assertEquals("junie:session-1", JunieTranscript.sittingId("session-1", 0))
        assertEquals("junie:session-1:2", JunieTranscript.sittingId("session-1", 1))
    }

    @Test
    fun `a session sent after its sitting length of quiet never sends those sittings again`() {
        val quiet = read(
            prompt(at(0), "r1", "Plan the migration"),
            block(at(5), "MarkdownBlockUpdatedEvent", "s1", "text", "Three steps, here they are."),
        )!!
        // Sent at 14:48:20, exactly thirty quiet minutes after the last event.
        val first = JunieTranscript.plan(quiet, null, 30)
        val sent = JunieTranscript.Sent(first.whole, first.parts.map { it.hash }, first.minutes)

        val resumed = read(
            prompt(at(0), "r1", "Plan the migration"),
            block(at(5), "MarkdownBlockUpdatedEvent", "s1", "text", "Three steps, here they are."),
            prompt(at(35), "r2", "Step two failed"),
            block(at(36), "MarkdownBlockUpdatedEvent", "s2", "text", "The lock was held."),
        )!!
        val next = JunieTranscript.plan(resumed, sent, 30)
        assertEquals(first.parts[0].hash, next.parts[0].hash)
        assertEquals(listOf(1), next.changed.map { it.sitting.index })
    }

    @Test
    fun `a session that grew sends only its last sitting, and an unchanged one nothing`() {
        val before = read(
            prompt(at(0), "r1", "First question"),
            block(at(1), "MarkdownBlockUpdatedEvent", "s1", "text", "First answer"),
            prompt(at(41), "r2", "Back after lunch"),
        )!!
        val first = JunieTranscript.plan(before, null, 30)
        assertEquals(2, first.changed.size)

        val sent = JunieTranscript.Sent(first.whole, first.parts.map { it.hash }, first.minutes)
        assertTrue(JunieTranscript.plan(before, sent, 30).changed.isEmpty())

        val after = read(
            prompt(at(0), "r1", "First question"),
            block(at(1), "MarkdownBlockUpdatedEvent", "s1", "text", "First answer"),
            prompt(at(41), "r2", "Back after lunch"),
            block(at(42), "MarkdownBlockUpdatedEvent", "s2", "text", "Second answer"),
        )!!
        // A new setting does not move the boundaries of a session already in past.dev.
        val grown = JunieTranscript.plan(after, sent, 5)
        assertEquals(30, grown.minutes)
        assertEquals(listOf(1), grown.changed.map { it.sitting.index })
    }

    @Test
    fun `a turn carries the model and the effort Junie was set to, and neither is rendered`() {
        val set = """{"kind":"SessionModelEffortSetEvent","modelId":"gemini-3.7-flash","providerCode":"jetbrains-ai","effortWireId":"medium","timestampMs":${at(0)}}"""
        val launched = """{"kind":"UserPromptEvent","timestampMs":${at(5)},"requestId":"r2","presentablePrompt":"Now the tests","prompt":"Now the tests",""" +
            """"customAttachments":[{"kind":"ModelForLaunchAttachment","modelId":"gpt-6-terra"},{"kind":"EffortForLaunchAttachment","effort":"high"}]}"""
        val conversation = read(
            set,
            prompt(at(0, 1), "r1", "Plan the migration"),
            block(at(1), "MarkdownBlockUpdatedEvent", "s1", "text", "Three steps."),
            launched,
            block(at(6), "MarkdownBlockUpdatedEvent", "s2", "text", "Two tests fail."),
        )!!

        assertEquals(listOf("gemini-3.7-flash", "gemini-3.7-flash", "gpt-6-terra", "gpt-6-terra"), conversation.turns.map { it.model })
        assertEquals(listOf("medium", "medium", "high", "high"), conversation.turns.map { it.effort })
        val rendered = JunieTranscript.render(conversation)
        assertFalse(rendered.contains("gemini") || rendered.contains("terra") || rendered.contains("medium"))

        // A session that names neither reads as before.
        val bare = read(prompt(at(0), "r1", "Plan the migration"))!!
        assertEquals("", bare.turns.single().model)
        assertEquals("", bare.turns.single().effort)
    }

    @Test
    fun `a sitting is sent with the connection it was typed under, and with no other`() {
        val conversation = read(
            prompt(at(0), "r1", "Typed before the machine was connected"),
            block(at(1), "MarkdownBlockUpdatedEvent", "s1", "text", "Answered before it was connected."),
            // The reply to the first prompt lands after the machine connected: it stays with its prompt.
            block(at(11), "MarkdownBlockUpdatedEvent", "s2", "text", "Still the first answer."),
            prompt(at(12), "r2", "Typed once connected"),
            block(at(13), "MarkdownBlockUpdatedEvent", "s3", "text", "Answered once connected."),
        )!!
        val spans = listOf(JunieTranscript.Span(at(0) - 60_000, ""), JunieTranscript.Span(at(10), "first-key"))

        // No record of connections: one sitting, as before.
        assertEquals(1, JunieTranscript.sittings(conversation, 30).size)

        val sittings = JunieTranscript.sittings(conversation, 30, spans)
        assertEquals(listOf("", "first-key"), sittings.map { it.connection })
        assertEquals(listOf(3, 2), sittings.map { it.turns.size })

        val connected = JunieTranscript.plan(conversation, null, 30, spans, "first-key")
        assertEquals(listOf(1), connected.changed.map { it.sitting.index })
        assertEquals(listOf(0), connected.kept.map { it.sitting.index })
        assertFalse(connected.changed.single().content.contains("before the machine was connected"))

        // Under another key nothing of it goes, and with no key nothing does.
        assertTrue(JunieTranscript.plan(conversation, null, 30, spans, "second-key").changed.isEmpty())
        assertTrue(JunieTranscript.plan(conversation, null, 30, spans, "").changed.isEmpty())
    }

    @Test
    fun `a session from before connections were recorded is cut and sent as it was`() {
        val conversation = read(
            prompt(at(0), "r1", "Typed before the plugin kept a record"),
            block(at(1), "MarkdownBlockUpdatedEvent", "s1", "text", "An answer."),
            prompt(at(12), "r2", "Typed after it began"),
        )!!
        val spans = listOf(JunieTranscript.Span(at(10), "first-key"))

        val before = JunieTranscript.plan(conversation, null, 30)
        val after = JunieTranscript.plan(conversation, null, 30, spans, "first-key")
        assertEquals(before.parts.map { it.hash }, after.parts.map { it.hash })
        assertEquals(before.changed.map { it.sitting.index }, after.changed.map { it.sitting.index })
        assertTrue(after.kept.isEmpty())
    }

    @Test
    fun `the fingerprint of a connection is empty with no key, and differs by key and by address`() {
        assertEquals("", JunieTranscript.connectionOf("https://api.past.dev", ""))
        val one = JunieTranscript.connectionOf("https://api.past.dev", "past_sk_one")
        assertEquals(16, one.length)
        assertFalse(one == JunieTranscript.connectionOf("https://api.past.dev", "past_sk_two"))
        assertFalse(one == JunieTranscript.connectionOf("https://staging.example", "past_sk_one"))
    }
}
