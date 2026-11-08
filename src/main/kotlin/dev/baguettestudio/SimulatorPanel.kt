package dev.baguettestudio

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import java.awt.BorderLayout
import java.awt.FlowLayout
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.OnePixelSplitter
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/** Tool window root: a row of independent [DevicePane]s, each streaming its own simulator. */
class SimulatorPanel(private val toolWindow: ToolWindow? = null) : JPanel(BorderLayout()), Disposable {
    private val server = service<BaguetteServer>()
    private val row = JPanel(BorderLayout())
    private val panes = mutableListOf<DevicePane>()
    private val statusLabel = JLabel(" ")
    @Volatile private var serverReady = false

    init {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        bar.add(JButton("Add device", AllIcons.General.Add).apply { addActionListener { addPane() } })
        bar.add(statusLabel)
        add(bar, BorderLayout.NORTH)
        add(row, BorderLayout.CENTER)
    }

    fun start() {
        statusLabel.text = "Starting baguette…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val err = server.ensureRunning()
            invokeLater {
                if (err != null) {
                    statusLabel.text = err
                } else {
                    statusLabel.text = " "
                    serverReady = true
                    addPane()
                }
            }
        }
    }

    private fun addPane() {
        val pane = DevicePane(
            onClose = ::removePane,
            otherSelected = { self -> panes.filter { it !== self }.mapNotNull { it.selectedUdid }.toSet() },
            onAspect = { _, a -> fitToolWindow(a) },
        )
        panes += pane
        relayout()
        if (serverReady) pane.refresh() else pane.status("Server not ready")
    }

    private fun removePane(pane: DevicePane) {
        panes.remove(pane)
        pane.shutdownIfEnabled(panes.mapNotNull { it.selectedUdid }.toSet())
        pane.dispose()
        relayout()
    }

    /** Panes side by side, separated by draggable splitters (equal shares). */
    private fun relayout() {
        row.removeAll()
        fun build(i: Int): JComponent =
            if (i == panes.lastIndex) panes[i]
            else OnePixelSplitter(false, 1f / (panes.size - i)).apply {
                firstComponent = panes[i]
                secondComponent = build(i + 1)
            }
        if (panes.isNotEmpty()) row.add(build(0), BorderLayout.CENTER)
        row.revalidate()
        row.repaint()
    }

    /** Grow the tool window (never shrink) so every pane gets the width its device aspect ratio needs. */
    private fun fitToolWindow(aspect: Double) {
        val tw = toolWindow as? ToolWindowEx ?: return
        if (height <= 0 || width <= 0) return
        val screenH = (height - 110).coerceAtLeast(300)
        val perPane = (screenH * aspect).toInt() + 16
        val need = perPane * panes.size.coerceAtLeast(1) + 8
        val delta = need - width
        if (delta > 12) tw.stretchWidth(delta)
    }

    override fun dispose() {
        panes.forEach { it.dispose() }
        panes.clear()
    }
}
