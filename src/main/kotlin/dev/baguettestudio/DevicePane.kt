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
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.ide.actions.RevealFileAction
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.intellij.ide.util.PropertiesComponent
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
    private val onAspect: (DevicePane, Double) -> Unit = { _, _ -> },
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

    /** Two rows: simulator and screen controls on top, input controls below. */
    private val toolbars = mutableListOf<ActionToolbar>()

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
        // Row 1: simulator lifecycle, display, capture, preferences.
        val simRow = DefaultActionGroup(
            action("Refresh devices", AllIcons.Actions.Refresh) { refresh() },
            action("Boot selected simulator", AllIcons.Actions.Execute, enabled = { !(selectedSim()?.booted ?: true) }) { bootSelected() },
            action("Shut down selected simulator", AllIcons.Actions.Suspend, enabled = { selectedSim()?.booted == true }) { shutdownSelected() },
            Separator.getInstance(),
            action("Rotate device 90° (simulator orientation)", DeviceIcons.rotateDevice, enabled = ::streaming) { rotate() },
            action("Rotate view 90° (this window only, device untouched)", DeviceIcons.rotateView, enabled = ::streaming) { rotateView() },
            toggle("Dark mode", AllIcons.Actions.Preview, enabled = ::streaming, selected = { dark }) { setDark(it) },
            object : DefaultActionGroup("iPhone Duo Hinge", true), DumbAware {
                init {
                    templatePresentation.icon = AllIcons.Actions.SplitHorizontally
                    add(action("Close hinge (0°)", AllIcons.Actions.Collapseall) { hinge("closed") })
                    add(action("Open hinge (130°)", AllIcons.Actions.Expandall) { hinge("open") })
                    add(action("Flat (180°)", AllIcons.Actions.MoveToButton) { hinge("flat") })
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = foldable
                    e.presentation.isEnabled = streaming()
                }
            },
            Separator.getInstance(),
            action("Screenshot (as shown) to ~/Pictures/BaguetteStudio", DeviceIcons.camera, enabled = ::streaming) { screenshot() },
            toggle(
                "Record screen to ~/Movies/BaguetteStudio", AllIcons.Debugger.Db_set_breakpoint,
                enabled = ::streaming, selected = { recorder != null },
            ) { if (it) startRecording() else stopRecording() },
            Separator.getInstance(),
            object : ToggleAction("Shut down simulator when closing this view"), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = shutdownOnClose
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    shutdownOnClose = state
                    PropertiesComponent.getInstance().setValue(PREF_SHUTDOWN_ON_CLOSE, state, true)
                }
            }.apply { templatePresentation.icon = AllIcons.Actions.Exit },
            object : ToggleAction("Hide Simulator.app when a run launches it"), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = SimulatorHider.enabled
                override fun setSelected(e: AnActionEvent, state: Boolean) { SimulatorHider.enabled = state }
            }.apply { templatePresentation.icon = AllIcons.Actions.ToggleVisibility },
        )
        // Row 2: system navigation gestures, then hardware buttons.
        val inputRow = DefaultActionGroup(
            action("Home", AllIcons.Nodes.HomeFolder, enabled = ::streaming) { button("swipe-to-home") },
            action("Back gesture, LTR (swipe from left edge)", AllIcons.Actions.Back, enabled = ::streaming) { canvas.backGesture(rtl = false) },
            action("Back gesture, RTL (swipe from right edge)", AllIcons.Actions.Forward, enabled = ::streaming) { canvas.backGesture(rtl = true) },
            action("App switcher", AllIcons.Actions.SplitVertically, enabled = ::streaming) { button("swipe-to-app-switcher") },
            action("Notification center (swipe down from top)", DeviceIcons.bell, enabled = ::streaming) { canvas.notificationGesture() },
            action("Toggle software keyboard", AllIcons.Nodes.Services, enabled = ::streaming) { button("software-keyboard") },
            Separator.getInstance(),
            action("Lock", DeviceIcons.lock, enabled = ::streaming) { button("lock") },
            action("Volume up", DeviceIcons.volumeUp, enabled = ::streaming) { button("volume-up") },
            action("Volume down", DeviceIcons.volumeDown, enabled = ::streaming) { button("volume-down") },
            action("Action button", AllIcons.Actions.Lightning, enabled = ::streaming) { button("action") },
            action("Shake", AllIcons.Actions.Refresh, enabled = ::streaming) { post("shake") },
            object : DefaultActionGroup("Face ID", true), DumbAware {
                init {
                    templatePresentation.icon = AllIcons.Actions.Preview
                    add(action("Matching face", AllIcons.Actions.Checked, enabled = ::streaming) { faceId("match") })
                    add(action("Non-matching face", AllIcons.Actions.Cancel, enabled = ::streaming) { faceId("nomatch") })
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = streaming()
                }
            },
        )
        toolbars += listOf(simRow, inputRow).mapIndexed { i, group ->
            ActionManager.getInstance().createActionToolbar("BaguetteDevice$i", group, true).apply {
                targetComponent = this@DevicePane
            }
        }
        val north = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JPanel(java.awt.GridLayout(0, 1)).apply { toolbars.forEach { add(it.component) } }, BorderLayout.CENTER)
        }
        add(north, BorderLayout.NORTH)
        add(canvas, BorderLayout.CENTER)
        canvas.onFilesDropped = ::handleDrop
        combo.addActionListener { if (!updating) connectSelected() }
        preferredSize = java.awt.Dimension(340, 720)
        minimumSize = java.awt.Dimension(220, 300)
    }

    private var shutdownOnClose = PropertiesComponent.getInstance().getBoolean(PREF_SHUTDOWN_ON_CLOSE, true)

    private fun selectedSim() = combo.selectedItem as? Sim
    private fun streaming() = client != null

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, run: () -> Unit) =
        object : AnAction(text, text, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun toggle(
        text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true },
        selected: () -> Boolean, set: (Boolean) -> Unit,
    ) = object : ToggleAction(text, text, icon), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { super.update(e); e.presentation.isEnabled = enabled() }
        override fun isSelected(e: AnActionEvent) = selected()
        override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
    }

    @Volatile private var foldable = false
    @Volatile private var dark = false
    @Volatile private var orientation = 0
    private var recorder: Process? = null
    private var recordingFile: File? = null

    private val orientations = listOf("portrait", "landscape-left", "portrait-upside-down", "landscape-right")

    /** Fire-and-forget POST to `/simulators/<udid>/<path>` off the EDT. */
    private fun post(path: String, json: String? = null, then: () -> Unit = {}) {
        val udid = selectedUdid ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val req = HttpRequest.newBuilder(URI("${server.baseUrl}/simulators/$udid/$path"))
                    .apply {
                        if (json != null) header("Content-Type", "application/json")
                    }
                    .POST(if (json != null) HttpRequest.BodyPublishers.ofString(json) else HttpRequest.BodyPublishers.noBody())
                    .build()
                http.send(req, HttpResponse.BodyHandlers.discarding())
            } catch (e: Exception) { status("Request failed: ${e.message}"); return@executeOnPooledThread }
            then()
        }
    }

    private fun rotate() {
        orientation = (orientation + 1) % orientations.size
        post("orientation?value=${orientations[orientation]}")
    }

    private fun rotateView() {
        canvas.viewRotation = (canvas.viewRotation + 1) % 4
        canvas.repaint()
        canvas.displayAspect()?.let { onAspect(this, it) }
    }

    private fun screenshot() {
        val img = canvas.snapshot() ?: return status("No frame to capture")
        val sim = selectedSim()
        ApplicationManager.getApplication().executeOnPooledThread {
            val dir = File(System.getProperty("user.home"), "Pictures/BaguetteStudio").apply { mkdirs() }
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val name = (sim?.name ?: "device").replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "$name-$stamp.png")
            try { javax.imageio.ImageIO.write(img, "png", file) } catch (e: Exception) { return@executeOnPooledThread status("Screenshot failed: ${e.message}") }
            invokeLater { RevealFileAction.openFile(file) }
        }
    }

    /** Fold change: after the simulator applies it, restart the stream so the view shows the new layout. */
    private fun hinge(pose: String) = post("hinge?pose=$pose") {
        Thread.sleep(400)
        invokeLater { startStream() }
    }

    private fun setDark(on: Boolean) {
        dark = on
        post("interface", """{"appearance":"${if (on) "dark" else "light"}"}""")
    }

    /** Foldable (iPhone Duo) and appearance state of the selected simulator, read when we connect. */
    private fun probeDevice(udid: String) {
        foldable = false; dark = false; orientation = 0
        invokeLater { toolbars.forEach { it.updateActionsAsync() } }
        ApplicationManager.getApplication().executeOnPooledThread {
            fun get(path: String) = try {
                JsonParser.parseString(
                    http.send(
                        HttpRequest.newBuilder(URI("${server.baseUrl}/simulators/$udid/$path")).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                    ).body()
                ).asJsonObject
            } catch (_: Exception) { null }
            get("hinge")?.let { foldable = it["foldable"]?.asBoolean == true }
            get("interface.json")?.let { dark = it["appearance"]?.asString == "dark" }
            invokeLater { toolbars.forEach { it.updateActionsAsync() } }
        }
    }

    private fun startRecording() {
        val sim = selectedSim() ?: return
        val bin = server.findBinary() ?: return status("baguette binary not found")
        val dir = File(System.getProperty("user.home"), "Movies/BaguetteStudio").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val out = File(dir, "${sim.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}-$stamp.mp4")
        recorder = try {
            ProcessBuilder(bin.path, "record", "--udid", sim.udid, "--output", out.path)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) { status("Record failed: ${e.message}"); return }
        recordingFile = out
    }

    private fun stopRecording() {
        val proc = recorder ?: return
        val file = recordingFile
        recorder = null; recordingFile = null
        ApplicationManager.getApplication().executeOnPooledThread {
            // SIGINT lets baguette flush a playable file.
            runCatching { ProcessBuilder("/bin/kill", "-INT", proc.pid().toString()).start().waitFor() }
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroy()
            if (file != null && file.exists()) invokeLater { RevealFileAction.openFile(file) }
        }
    }

    /**
     * Drop handler, mirrors Simulator.app: `.app`/`.ipa` are installed, images/videos/contacts go to
     * Photos/Contacts via `simctl addmedia`. Anything else is reported as unsupported.
     */
    private fun handleDrop(files: List<File>) {
        val udid = selectedUdid ?: return
        if (!streaming()) return canvas.run { notice = "Boot the simulator first" }
        val installable = setOf("app", "ipa")
        val media = setOf("jpg", "jpeg", "png", "gif", "heic", "heif", "tiff", "bmp", "webp", "mov", "mp4", "m4v", "vcf")
        canvas.notice = "Copying ${files.size} file(s)…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = files.map { f ->
                val ext = f.extension.lowercase()
                val args = when (ext) {
                    in installable -> listOf("install", udid, f.path)
                    in media -> listOf("addmedia", udid, f.path)
                    else -> return@map copyToFilesApp(udid, f)
                }
                try {
                    val p = ProcessBuilder(listOf("/usr/bin/xcrun", "simctl") + args).redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); "${f.name}: timed out" }
                    else if (p.exitValue() == 0) null
                    else "${f.name}: ${out.lineSequence().lastOrNull().orEmpty()}"
                } catch (e: Exception) { "${f.name}: ${e.message}" }
            }
            val errors = results.filterNotNull()
            invokeLater {
                canvas.notice = if (errors.isEmpty()) "Added ${files.size} file(s)" else errors.first()
            }
        }
    }

    /** Copies [f] into the sim's Files app "On My iPhone" storage. Returns an error string or null. */
    private fun copyToFilesApp(udid: String, f: File): String? {
        return try {
            val groups = File(System.getProperty("user.home"),
                "Library/Developer/CoreSimulator/Devices/$udid/data/Containers/Shared/AppGroup")
            val marker = "group.com.apple.FileProvider.LocalStorage".toByteArray()
            val group = groups.listFiles()?.firstOrNull { d ->
                val meta = File(d, ".com.apple.mobile_container_manager.metadata.plist")
                meta.exists() && String(meta.readBytes(), Charsets.ISO_8859_1)
                    .contains(String(marker, Charsets.ISO_8859_1))
            } ?: return "${f.name}: open the Files app once in the simulator, then drop again"
            val dest = File(group, "File Provider Storage").apply { mkdirs() }
            f.copyTo(File(dest, f.name), overwrite = true)
            null
        } catch (e: Exception) { "${f.name}: ${e.message}" }
    }

    /**
     * Simulated Face ID result, delivered as a Darwin notification to the booted simulator
     * (`simctl spawn <udid> notifyutil -p`). Notification names are not verified against Xcode 27.
     */
    private fun faceId(result: String) {
        val udid = selectedUdid ?: return
        val name = "com.apple.BiometricKit_Sim.pearl.$result"
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val p = ProcessBuilder("/usr/bin/xcrun", "simctl", "spawn", udid, "notifyutil", "-p", name)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); status("Face ID: timed out") }
                else if (p.exitValue() != 0) status("Face ID failed: ${out.lineSequence().lastOrNull().orEmpty()}")
                else canvas.notice = if (result == "match") "Face ID: matching face" else "Face ID: non-matching face"
            } catch (e: Exception) { status("Face ID failed: ${e.message}") }
        }
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
        stopRecording()
        val sim = combo.selectedItem as? Sim ?: return status("No simulators found")
        if (!sim.booted) return status("${sim.name} is ${sim.state}. Press Boot.")
        probeDevice(sim.udid)
        canvas.image = null
        status("Connecting…")
        startStream()
    }

    /** (Re)opens the frame stream for the selected simulator; keeps recording and probe state. */
    private fun startStream() {
        val sim = selectedSim() ?: return
        if (!sim.booted) return
        client?.close(); client = null
        client = StreamClient(
            server.baseUrl, sim.udid,
            onFrame = { img ->
                val first = canvas.image == null || canvas.image!!.width != img.width || canvas.image!!.height != img.height
                canvas.image = img; canvas.repaint()
                if (first) invokeLater { onAspect(this, canvas.displayAspect() ?: (img.width.toDouble() / img.height)) }
            },
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

    private fun postShutdown(udid: String, then: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                http.send(
                    HttpRequest.newBuilder(URI("${server.baseUrl}/simulators/$udid/shutdown"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()
                )
            } catch (_: Exception) { }
            then()
        }
    }

    private fun shutdownSelected() {
        val sim = selectedSim() ?: return
        client?.close(); client = null
        status("Shutting down ${sim.name}…")
        postShutdown(sim.udid) { refresh() }
    }

    /** Called when the pane is closed. Skips the shutdown if another pane still shows this device. */
    fun shutdownIfEnabled(stillUsed: Set<String>) {
        val sim = selectedSim() ?: return
        if (shutdownOnClose && sim.booted && sim.udid !in stillUsed) postShutdown(sim.udid)
    }

    override fun dispose() {
        stopRecording()
        client?.close(); client = null
    }
}

private const val PREF_SHUTDOWN_ON_CLOSE = "baguettestudio.shutdownOnClose"
