package dev.baguettestudio

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel

class DevicePane(
    private val onClose: (DevicePane) -> Unit,
    private val otherSelected: (DevicePane) -> Set<String>,
) : JPanel(BorderLayout()), Disposable {
    private data class Sim(val udid: String, val name: String, val state: String, val runtime: String) {
        val booted get() = state == "Booted"
        override fun toString() = "$name ($runtime) - $state"
    }

    private val server = service<BaguetteServer>()
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()
    private val model = DefaultComboBoxModel<Sim>()
    private val combo = ComboBox(model)
    private var client: StreamClient? = null
    private var updating = false
    private val canvas = ScreenCanvas { json, droppable -> client?.send(json, droppable) }


    val selectedUdid: String? get() = (combo.selectedItem as? Sim)?.udid

    init {
        // Header: device picker stretches, close button is pinned right so it is never clipped.
        val header = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(combo, BorderLayout.CENTER)
            add(
                ActionButton(
                    action("Close this device view", AllIcons.Actions.Close) { onClose(this@DevicePane) },
                    null, "DevicePaneHeader", ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE,
                ),
                BorderLayout.EAST,
            )
        }
        val group = DefaultActionGroup(
            action("Refresh devices", AllIcons.Actions.Refresh) { refresh() },
            action("Boot selected simulator", AllIcons.Actions.Execute, enabled = { !(selectedSim()?.booted ?: true) }) { bootSelected() },
            Separator.getInstance(),
            action("Home", AllIcons.Nodes.HomeFolder, enabled = ::streaming) { button("swipe-to-home") },
            action("App switcher", AllIcons.Actions.SplitVertically, enabled = ::streaming) { button("swipe-to-app-switcher") },
            action("Lock", DeviceIcons.lock, enabled = ::streaming) { button("lock") },
            action("Volume up", DeviceIcons.volumeUp, enabled = ::streaming) { button("volume-up") },
            action("Volume down", DeviceIcons.volumeDown, enabled = ::streaming) { button("volume-down") },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("BaguetteDevice", group, true).apply {
            targetComponent = this@DevicePane
        }
        val north = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(toolbar.component, BorderLayout.CENTER)
        }
        add(north, BorderLayout.NORTH)
        add(canvas, BorderLayout.CENTER)
        combo.addActionListener { if (!updating) connectSelected() }
        preferredSize = java.awt.Dimension(340, 720)
    }

    private fun selectedSim() = combo.selectedItem as? Sim
    private fun streaming() = client != null

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, run: () -> Unit) =
        object : AnAction(text, text, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun button(name: String) = client?.send("""{"type":"button","button":"$name"}""")

    fun status(msg: String) = invokeLater { canvas.message = msg; canvas.image = null; canvas.repaint() }

    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val sims = try {
                val body = http.send(
                    HttpRequest.newBuilder(URI("${server.baseUrl}/simulators.json")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                ).body()
                val root = JsonParser.parseString(body).asJsonObject
                listOf("running", "available").flatMap { key ->
                    root.getAsJsonArray(key).map { it.asJsonObject }.map {
                        Sim(
                            it["udid"].asString, it["name"].asString,
                            it["state"]?.asString ?: "?", it["runtime"]?.asString ?: "",
                        )
                    }
                }.distinctBy { it.udid }
            } catch (e: Exception) {
                status("Could not list simulators: ${e.message}"); return@executeOnPooledThread
            }
            invokeLater {
                val prev = (combo.selectedItem as? Sim)?.udid
                updating = true
                model.removeAllElements()
                sims.sortedByDescending { it.booted }.forEach(model::addElement)
                val taken = otherSelected(this)
                val pick = sims.firstOrNull { it.udid == prev }
                    ?: sims.firstOrNull { it.booted && it.udid !in taken }
                    ?: sims.firstOrNull { it.booted } ?: sims.firstOrNull()
                model.selectedItem = pick
                updating = false
                connectSelected()
            }
        }
    }

    private fun connectSelected() {
        client?.close(); client = null
        val sim = combo.selectedItem as? Sim ?: return status("No simulators found")
        if (!sim.booted) return status("${sim.name} is ${sim.state}. Press Boot.")
        canvas.image = null
        status("Connecting…")
        client = StreamClient(
            server.baseUrl, sim.udid,
            onFrame = { img -> canvas.image = img; canvas.repaint() },
            onStatus = ::status,
        )
        canvas.requestFocusInWindow()
    }

    private fun bootSelected() {
        val sim = combo.selectedItem as? Sim ?: return
        status("Booting ${sim.name}…")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                http.send(
                    HttpRequest.newBuilder(URI("${server.baseUrl}/simulators/${sim.udid}/boot"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()
                )
            } catch (e: Exception) { status("Boot failed: ${e.message}"); return@executeOnPooledThread }
            refresh()
        }
    }

    override fun dispose() {
        client?.close(); client = null
    }
}
