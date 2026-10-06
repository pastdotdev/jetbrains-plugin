package dev.past.jetbrains

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * past.dev's public Memory API, with the project's key. Every call is time-boxed and answers with a
 * [Reply] rather than throwing, so a slow or absent network never breaks the IDE.
 */
object PastApi {
    const val VERSION = "0.1.0"

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // The IDE in parentheses tells DataGrip from Rider in the request log, for recall and sends alike.
    private val userAgent: String by lazy {
        val ide = listOf(Ide.name, Ide.build).filter { it.isNotBlank() }.joinToString("; ")
        "past-junie/$VERSION" + if (ide.isNotEmpty()) " ($ide)" else ""
    }

    sealed interface Reply {
        data class Ok(val body: JsonElement) : Reply
        /** The API answered with an error; `code` is past.dev's own error code when the body names one. */
        data class Refused(val status: Int, val code: String) : Reply
        data object Unreachable : Reply
    }

    data class Memory(val at: String, val content: String)

    fun recall(config: PastConfig, query: String, limit: Int, timeout: Duration = Duration.ofSeconds(15)): Pair<List<Memory>, Reply> {
        val reply = call(config, "POST", "/api/v1/recall", buildJsonObject {
            // A query leaves the machine as content does, so it is redacted as content is.
            put("query", Redaction.redact(query).take(1000))
            put("identity", config.identity)
            put("limit", limit)
            put("level", "low")
        }, timeout)
        val results = ((reply as? Reply.Ok)?.body as? JsonObject)?.get("results") as? JsonArray
        val memories = results.orEmpty().mapNotNull { result ->
            val document = result as? JsonObject ?: return@mapNotNull null
            val content = (document["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Memory((document["occurredAt"] as? JsonPrimitive)?.contentOrNull.orEmpty(), content)
        }
        return memories to reply
    }

    fun ingestBatch(config: PastConfig, items: JsonArray): Reply =
        call(config, "POST", "/api/v1/ingest/batch", buildJsonObject { put("items", items) }, Duration.ofSeconds(30))

    /** True, false (no such audience), or null when the API could not be asked. */
    fun audienceExists(config: PastConfig, slug: String): Boolean? =
        when (val reply = call(config, "GET", "/api/v1/audiences/${URLEncoder.encode(slug, StandardCharsets.UTF_8)}", null, Duration.ofSeconds(8))) {
            is Reply.Ok -> true
            is Reply.Refused -> if (reply.status == 404) false else null
            Reply.Unreachable -> null
        }

    /** Over plain http the key can be read on the way; this machine is the one exception. */
    fun sendsKeyInClear(apiUrl: String): Boolean {
        val uri = runCatching { URI(apiUrl) }.getOrNull() ?: return false
        return uri.scheme == "http" && uri.host !in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }

    private fun call(config: PastConfig, method: String, path: String, body: JsonObject?, timeout: Duration): Reply {
        if (config.apiKey.isBlank()) return Reply.Unreachable
        return try {
            val request = HttpRequest.newBuilder(URI("${config.apiUrl}$path"))
                .timeout(timeout)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val parsed = runCatching { Json.parseToJsonElement(response.body()) }.getOrDefault(JsonObject(emptyMap()))
            if (response.statusCode() in 200..299) Reply.Ok(parsed)
            else Reply.Refused(response.statusCode(), ((parsed as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull.orEmpty())
        } catch (_: Exception) {
            Reply.Unreachable
        }
    }
}
