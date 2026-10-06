package dev.past.jetbrains.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.past.jetbrains.PastApi
import dev.past.jetbrains.PastConfig
import dev.past.jetbrains.PastConnect
import dev.past.jetbrains.junie.JunieCapture
import dev.past.jetbrains.junie.JunieConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings › Tools › past.dev: the connection, and whether Junie's sessions are sent. */
class PastConfigurable : BoundConfigurable("past.dev") {
    // One form for the page's lifetime: the fields are bound to it when the panel is built.
    private val form = Form().apply { fill() }

    override fun createPanel(): DialogPanel = panel {
        row {
            text("Create a project API key in the past.dev console under Build › API keys. The connection is kept in " +
                "~/.past/config.json, shared with every past.dev plugin on this machine.")
        }
        row("Project API key:") {
            passwordField().bindText(form::apiKey).align(AlignX.FILL)
                .comment("Starts with past_sk_. Readable only by you.")
        }
        row("Identity:") {
            textField().bindText(form::identity).align(AlignX.FILL)
                .comment("The email or id every memory is attributed to.")
        }
        row("API URL:") {
            textField().bindText(form::apiUrl).align(AlignX.FILL)
        }
        row("Audience:") {
            textField().bindText(form::audience).align(AlignX.FILL)
                .comment("An audience slug from the console. Leave it empty and the whole project sees what is sent.")
        }
        group("Junie") {
            row {
                checkBox("Send Junie's sessions to past.dev when they are over")
                    .bindSelected(form::junie)
                    .comment("Only what you typed and what Junie answered. Tool runs, file contents and diffs stay on this machine.")
            }
        }
    }

    override fun reset() {
        form.fill()
        super.reset()
    }

    override fun apply() {
        super.apply()
        // A ticked box is a yes, and unticking it turns a yes into a no. A box never ticked leaves the
        // question open, so the notice still asks.
        val capture = JunieCapture.getInstance()
        if (form.junie) capture.consent = JunieConsent.Allowed
        else if (capture.consent == JunieConsent.Allowed) capture.consent = JunieConsent.Refused
        val saved = PastConfig.load()
        val changed = form.apiKey.trim() != saved.apiKey || form.identity.trim() != saved.identity ||
            form.apiUrl.trim().trimEnd('/') != saved.apiUrl || form.audience.trim() != saved.audience
        if (!changed) return
        val refusal = runWithModalProgressBlocking(ModalTaskOwner.guess(), "Connecting to past.dev") {
            withContext(Dispatchers.IO) { PastConnect.connect(form.apiKey, form.identity, form.apiUrl, form.audience) }
        }
        if (refusal != null) throw ConfigurationException(refusal, "past.dev")
        if (PastApi.sendsKeyInClear(form.apiUrl.trim())) {
            NotificationGroupManager.getInstance().getNotificationGroup("past.dev").createNotification(
                "${form.apiUrl.trim()} is plain http, so the key travels unencrypted. Use https unless this network is yours.",
                NotificationType.WARNING,
            ).notify(null)
        }
    }

    private class Form {
        var apiKey = ""
        var identity = ""
        var apiUrl = ""
        var audience = ""
        var junie = false

        fun fill() {
            val config = PastConfig.load()
            apiKey = config.apiKey
            identity = config.identity
            apiUrl = config.apiUrl
            audience = config.audience
            junie = JunieCapture.getInstance().consent.sends
        }
    }
}
