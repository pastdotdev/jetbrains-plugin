@file:OptIn(ExperimentalSerializationApi::class)

package dev.past.jetbrains

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writes the connection into ~/.past/config.json, with the checks every past.dev plugin makes: a
 * project API key, an identity, an audience that exists. Fields this plugin does not own (recall,
 * ingest, deny, idleMinutes, sittingMinutes) are kept as they were.
 */
object PastConnect {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** null when saved; otherwise the sentence that says why not, for the settings page to show. */
    fun connect(apiKey: String, identity: String, apiUrl: String, audience: String): String? {
        val key = apiKey.trim()
        if (key.startsWith("past_mk_")) {
            return "That is the organization's management key. The plugin needs a project API key (past_sk_…)."
        }
        if (!key.startsWith("past_sk_")) {
            return "That is not a project API key. It starts with past_sk_. Create one in the past.dev console under Build › API keys."
        }
        if (identity.isBlank()) return "An identity is needed too: the email or id every memory is attributed to."

        val url = apiUrl.trim().trimEnd('/').ifBlank { PastConfig.DEFAULT_API_URL }
        val slug = audience.trim().takeUnless { it == "project" }.orEmpty()
        if (slug.isNotEmpty()) {
            val probe = PastConfig.load().copy(apiKey = key, apiUrl = url)
            if (PastApi.audienceExists(probe, slug) == false) {
                return "No audience \"$slug\" in this project. Create it in the console under Audiences, or leave it empty " +
                    "and the whole project sees what is sent."
            }
        }

        val file = PastConfig.readFile().toMutableMap()
        file["apiKey"] = JsonPrimitive(key)
        file["identity"] = JsonPrimitive(identity.trim())
        file["apiUrl"] = JsonPrimitive(url)
        if (slug.isEmpty()) file.remove("audience") else file["audience"] = JsonPrimitive(slug)
        return try {
            PrivateFiles.directory(PastConfig.home)
            // Written as the other past.dev plugins write it, so a person reading the file sees one format.
            PrivateFiles.write(PastConfig.path, pretty.encodeToString(JsonObject.serializer(), JsonObject(file)) + "\n")
            null
        } catch (error: Exception) {
            "Could not write ${PastConfig.path}: ${error.message}"
        }
    }
}
