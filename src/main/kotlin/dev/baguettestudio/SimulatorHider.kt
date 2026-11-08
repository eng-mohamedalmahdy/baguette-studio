package dev.baguettestudio

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager

/**
 * Run configs (e.g. KMP iOS) open Simulator.app. When a run starts, hide that app so the
 * device only shows inside the tool window. The simulator itself keeps running.
 */
object SimulatorHider {
    private const val PREF = "baguettestudio.hideSimulatorApp"
    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(PREF, true)
        set(v) = PropertiesComponent.getInstance().setValue(PREF, v, true)

    private val script = """
        tell application "System Events"
            if exists process "Simulator" then set visible of process "Simulator" to false
        end tell
    """.trimIndent()

    fun hideSoon() {
        if (!enabled) return
        ApplicationManager.getApplication().executeOnPooledThread {
            // Simulator.app may launch late during a build; retry for ~2 min.
            repeat(60) {
                try {
                    ProcessBuilder("/usr/bin/osascript", "-e", script).redirectErrorStream(true).start().waitFor()
                } catch (_: Exception) { return@executeOnPooledThread }
                Thread.sleep(2000)
            }
        }
    }
}

class SimulatorHideListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        SimulatorHider.hideSoon()
    }
}
