package dev.past.jetbrains.junie

import com.intellij.ide.AppLifecycleListener

/** The Junie sessions in the IDE's chat end with the IDE, so what waits is sent as it quits. */
class JunieShutdown : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) {
        JunieCapture.getInstance().sendOnQuit()
    }
}
