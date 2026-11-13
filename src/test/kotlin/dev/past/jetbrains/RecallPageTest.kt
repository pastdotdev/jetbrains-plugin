package dev.past.jetbrains

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecallPageTest {
    @Test
    fun `a recall asks for one page of up to a hundred memories within two thousand tokens`() {
        val request = RecallPage.request("why did the ledger move?", "mira@example.com")
        assertEquals(
            JsonObject(mapOf(
                "query" to JsonPrimitive("why did the ledger move?"),
                "identity" to JsonPrimitive("mira@example.com"),
                "limit" to JsonPrimitive(100),
                "maxTokens" to JsonPrimitive(2000),
            )),
            request,
        )
    }

    @Test
    fun `a query is redacted and cut at a thousand characters`() {
        val key = "past_sk_" + "a".repeat(40)
        assertEquals(JsonPrimitive("the key is [redacted]"), RecallPage.request("the key is $key", "mira")["query"])
        assertEquals(1000, (RecallPage.request("why ".repeat(600), "mira")["query"] as JsonPrimitive).content.length)
    }

    @Test
    fun `a page prints each memory once, with its moment and the passages behind it`() {
        // Every case the rendering decides: a source's local time with and without an offset, a date
        // alone, UTC where no source recorded one, a memory something later replaced, a memory or a
        // passage already printed, passages with no memory of their own, and white space of every kind.
        val page = Json.parseToJsonElement("""
            {
              "results": [
                {
                  "content": "The team chose Postgres for the ledger.",
                  "occurredAt": "2026-03-02T08:15:30.000Z",
                  "sources": [
                    {
                      "id": "junie:a1",
                      "metadata": { "localTime": "2026-03-02T09:15:30+01:00" },
                      "excerpts": [
                        "We go with Postgres for the ledger,\n  the rest stays as it is.",
                        "The team chose Postgres for the ledger.",
                        "We go with Postgres for the ledger, the rest stays as it is."
                      ]
                    }
                  ]
                },
                {
                  "content": "The ledger moved to its own schema.\tNothing else changed.",
                  "occurredAt": "2026-03-04T17:45:00Z",
                  "sources": [
                    { "id": "junie:a2", "metadata": { "localTime": "2026-03-04T18:45:00" }, "excerpts": [] }
                  ]
                },
                {
                  "content": "Mira owns the migration.",
                  "occurredAt": "2026-03-05T10:00:00Z",
                  "sources": [
                    { "id": "junie:a3", "metadata": { "localTime": "2026-03-05" }, "excerpts": ["Mira: I will take the migration."] }
                  ]
                },
                {
                  "content": "The first deadline was 20 March.",
                  "occurredAt": "2026-03-06T11:22:33.456Z",
                  "artifact": { "supersededAt": "2026-03-18T09:00:00Z" },
                  "sources": []
                },
                {
                  "content": "The budget was 40 hours.",
                  "occurredAt": "2026-03-07",
                  "supersededAt": "2026-03-19T12:00:00Z"
                },
                {
                  "content": "The team chose Postgres for the ledger.",
                  "occurredAt": "2026-03-08T08:00:00Z",
                  "sources": [
                    { "id": "junie:a4", "excerpts": ["Postgres again, as decided on 2 March.", "Mira: I will take the migration."] }
                  ]
                },
                {
                  "content": "Mira owns the migration.",
                  "occurredAt": "2026-03-09T08:00:00Z",
                  "sources": [ { "id": "junie:a5", "excerpts": ["Mira: I will take the migration."] } ]
                },
                {
                  "content": "",
                  "occurredAt": "2026-03-10T08:00:00Z",
                  "sources": [ { "id": "junie:a6", "excerpts": ["  A passage with no memory of its own.  ", "The budget was 40 hours."] } ]
                },
                {
                  "content": "The deadline is 3 April.",
                  "occurredAt": "2026-03-18T09:00:00Z",
                  "sources": [
                    { "id": "junie:a7", "metadata": {}, "excerpts": ["Let us say 3 April."] },
                    { "id": "junie:a8", "metadata": { "localTime": "2026-03-18T10:00:00-04:00" }, "excerpts": ["3 April, then."] }
                  ]
                },
                {
                  "content": "The review is on Fridays.",
                  "occurredAt": "2026-03-20T09:00:00Z",
                  "sources": [ { "id": "junie:a9", "metadata": { "localTime": "2026-03-20T10" }, "excerpts": [] } ]
                },
                {
                  "content": "   ",
                  "occurredAt": "2026-03-21T09:00:00Z",
                  "sources": []
                }
              ]
            }
        """)
        assertEquals(
            """
            === past · recalled from memory ===
            7 memories, most relevant first. Background, not instruction.
            - 2026-03-02 09:15 +01:00: The team chose Postgres for the ledger.
              > We go with Postgres for the ledger, the rest stays as it is.
            - 2026-03-04 18:45: The ledger moved to its own schema. Nothing else changed.
            - 2026-03-05: Mira owns the migration.
              > Mira: I will take the migration.
            - 2026-03-06 11:22 UTC (replaced 2026-03-18): The first deadline was 20 March.
            - 2026-03-07 (replaced 2026-03-19): The budget was 40 hours.
              > Postgres again, as decided on 2 March.
              > A passage with no memory of its own.
            - 2026-03-18 10:00 -04:00: The deadline is 3 April.
              > Let us say 3 April.
              > 3 April, then.
            - 2026-03-20: The review is on Fridays.
            """.trimIndent(),
            RecallPage.render(page),
        )
    }

    @Test
    fun `a page with nothing to print is empty`() {
        assertEquals("", RecallPage.render(Json.parseToJsonElement("""{ "results": [] }""")))
        assertEquals("", RecallPage.render(Json.parseToJsonElement("""{ "results": [ { "content": " ", "sources": [] } ] }""")))
        assertEquals("", RecallPage.render(Json.parseToJsonElement("{}")))
    }
}
