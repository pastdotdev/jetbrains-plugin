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
}
