package dev.past.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.past.jetbrains.junie.JunieCapture
import dev.past.jetbrains.junie.JunieConsentNotice

/**
 * Starts watching Junie's sessions with the first project the IDE opens (later projects find it
 * running), and asks whether they may be sent if nobody has answered yet.
 */
class PastStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        JunieCapture.getInstance().start()
        JunieConsentNotice.askIfNeeded(project)
    }
}
