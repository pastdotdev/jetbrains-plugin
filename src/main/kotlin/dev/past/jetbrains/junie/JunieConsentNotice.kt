package dev.past.jetbrains.junie

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.past.jetbrains.PastConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks whether Junie's sessions may be sent, once per IDE run, when the machine is connected and the
 * person has not answered. A machine connected by another past.dev plugin needs no visit to Settings,
 * so the question has to come to the person. Closing it without an answer asks again next time.
 */
object JunieConsentNotice {
    private const val GROUP = "past.dev: Junie's sessions"
    private val asked = AtomicBoolean(false)

    fun askIfNeeded(project: Project) {
        val capture = JunieCapture.getInstance()
        if (capture.consent != JunieConsent.NotAsked) return
        val config = PastConfig.load()
        if (!config.connected || !config.ingest) return
        if (!asked.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(
            "Send your Junie sessions to past.dev?",
            "This IDE is connected to past.dev as ${config.identity}. Each Junie session would go there once it " +
                "is over: what you typed and what Junie answered. Tool runs, file contents and diffs stay on this " +
                "machine. You can change it later in Settings › Tools › past.dev.",
            NotificationType.INFORMATION,
        )
            .addAction(NotificationAction.createSimpleExpiring("Send them") { capture.consent = JunieConsent.Allowed })
            .addAction(NotificationAction.createSimpleExpiring("Don't send") { capture.consent = JunieConsent.Refused })
            .notify(project)
    }
}
