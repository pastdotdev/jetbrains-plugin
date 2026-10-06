package dev.past.jetbrains.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import dev.past.jetbrains.PastApi
import dev.past.jetbrains.PastConfig
import dev.past.jetbrains.junie.JunieCapture
import dev.past.jetbrains.junie.JunieConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JTextField

/** The past.dev tool window: what this machine has sent of Junie's sessions, and a search over the project's memory. */
class PastPanel(parent: Disposable) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val output = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, 12))
    }
    private val query = JTextField()

    val component: JComponent = panel {
        row {
            button("Status") { show { status() } }
            button("Send now") { show { "${JunieCapture.getInstance().sendWaiting(now = true)} session(s) sent.\n\n${status()}" } }
            button("Send history…") { sendHistory() }
        }
        row {
            cell(query).align(AlignX.FILL).resizableColumn().comment("Search this project's memory")
            button("Search") { search() }
        }
        row { cell(JBScrollPane(output)).align(AlignX.FILL).align(AlignY.FILL) }.resizableRow()
    }

    init {
        query.addActionListener { search() }
        Disposer.register(parent) { scope.cancel() }
        show { status() }
    }

    private fun search() {
        val text = query.text.trim().takeIf { it.isNotEmpty() } ?: return
        show {
            val config = PastConfig.load()
            if (!config.connected) return@show NOT_CONNECTED
            val (memories, reply) = PastApi.recall(config, text, 8)
            when {
                reply is PastApi.Reply.Refused -> "past.dev refused the search: ${reply.status} ${reply.code}".trim()
                reply == PastApi.Reply.Unreachable -> "past.dev did not answer. Try again in a moment."
                memories.isEmpty() -> "Nothing in past.dev matches that yet."
                else -> memories.mapIndexed { index, memory ->
                    "[${index + 1}] ${memory.at.take(10)} — ${memory.content.replace(Regex("\\s+"), " ").trim()}"
                }.joinToString("\n\n")
            }
        }
    }

    private fun sendHistory() {
        scope.launch {
            val history = withContext(Dispatchers.IO) { JunieCapture.getInstance().history() }
            withContext(Dispatchers.EDT) {
                if (history.sessions == 0) {
                    output.text = "No Junie session from before capture began is waiting.\n\n${output.text}"
                    return@withContext
                }
                val answer = Messages.showYesNoDialog(
                    "Send ${history.sessions} Junie session(s) from before capture began? " +
                        "That is %.1f KB of prose in ${history.sittings} sitting(s), about ${history.credits} credits.".format(history.kilobytes),
                    "Send Junie History", "Send", "Cancel", null,
                )
                if (answer == Messages.YES) show { "${JunieCapture.getInstance().sendHistory()} session(s) sent.\n\n${status()}" }
            }
        }
    }

    private fun show(compute: () -> String) {
        output.text = "…"
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching(compute).getOrElse { "Failed: ${it.message}" } }
            withContext(Dispatchers.EDT) { output.text = text }
        }
    }

    private fun status(): String {
        val config = PastConfig.load()
        val capture = JunieCapture.getInstance()
        val junie = capture.status()
        val lines = mutableListOf(
            "API URL    ${config.apiUrl}" + if (PastApi.sendsKeyInClear(config.apiUrl)) "  (plain http: the key travels unencrypted)" else "",
            "Key        ${if (config.apiKey.isBlank()) "not set — Settings › Tools › past.dev" else "${config.apiKey.take(11)}…  (${PastConfig.path})"}",
            "Identity   ${config.identity.ifBlank { "not set — recall is off until it is" }}",
            "Audience   ${config.audience.ifBlank { "whole project" }}",
            "Junie      ${sending(config, capture.consent)} · ${junie.sent} sent · ${junie.waiting} waiting",
        )
        junie.nextSendMs?.let {
            lines += "Next send  ${TIME.format(Instant.ofEpochMilli(it))} if nothing more is said " +
                "(${config.sittingMinutes} min of quiet), or when the IDE quits"
        }
        if (junie.history > 0) lines += "History    ${junie.history} session(s) from before capture began, sent with Send history…"
        junie.lastError?.let {
            lines += "Last send  failed at ${TIME.format(Instant.ofEpochMilli(it.atMs))}: ${it.status} ${it.code}".trimEnd()
        }
        if (config.deny.isNotEmpty()) lines += "Ignored    ${config.deny.joinToString(", ")}"
        return lines.joinToString("\n")
    }

    private fun sending(config: PastConfig, consent: JunieConsent): String = when {
        !config.ingest || !config.connected -> "off"
        consent == JunieConsent.Allowed -> "on"
        consent == JunieConsent.Refused -> "off, as you chose (Settings › Tools › past.dev)"
        else -> "off until you allow it (Settings › Tools › past.dev)"
    }

    companion object {
        private const val NOT_CONNECTED = "past.dev is not connected. Set it up under Settings › Tools › past.dev."
        private val TIME = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault())
    }
}
