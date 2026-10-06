package dev.past.jetbrains

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo

/**
 * The JetBrains IDE this plugin runs in, as the platform names it: "DataGrip 2026.2.3", build
 * "DB-262.10968.92". It goes with every request (User-Agent) and with every session sent
 * (`sentFrom`), so past.dev can tell which IDE a call came from. Empty outside an IDE, in tests.
 */
object Ide {
    val name: String by lazy {
        runCatching { "${ApplicationNamesInfo.getInstance().fullProductName} ${ApplicationInfo.getInstance().fullVersion}" }
            .getOrDefault("")
    }

    val build: String by lazy { runCatching { ApplicationInfo.getInstance().build.asString() }.getOrDefault("") }
}
