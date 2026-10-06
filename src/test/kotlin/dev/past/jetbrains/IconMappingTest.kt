package dev.past.jetbrains

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The new UI finds the plugin's icons through `PastIconMappings.json`, whose nested keys are the
 * folders of the new icon, top key included. A mapped path that does not exist draws nothing but a
 * dot in the tool window bar, with no error anywhere.
 */
class IconMappingTest {
    @Test
    fun `every icon the mapping names exists, in both themes and both sizes`() {
        val text = javaClass.getResource("/PastIconMappings.json")!!.readText()
        val pairs = mutableListOf<Pair<String, String>>()
        fun walk(node: JsonElement, path: List<String>) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) -> walk(value, path + key) }
                is JsonPrimitive -> pairs += path.joinToString("/") to node.contentOrNull.orEmpty()
                else -> Unit
            }
        }
        walk(Json.parseToJsonElement(text), emptyList())
        assertTrue(pairs.isNotEmpty())
        for ((newUi, classic) in pairs) {
            val base = newUi.removeSuffix(".svg")
            for (variant in listOf("$base.svg", "${base}_dark.svg", "$base@20x20.svg", "$base@20x20_dark.svg")) {
                assertNotNull(javaClass.getResource("/$variant"), "missing $variant")
            }
            assertNotNull(javaClass.getResource("/$classic"), "missing $classic")
        }
    }
}
