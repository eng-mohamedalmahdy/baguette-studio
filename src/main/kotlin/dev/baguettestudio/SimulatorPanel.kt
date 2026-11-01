package dev.baguettestudio

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/** Tool window root: a row of independent [DevicePane]s, each streaming its own simulator. */
class SimulatorPanel : JPanel(BorderLayout()), Disposable {
    private val server = service<BaguetteServer>()
    private val row = JPanel(java.awt.GridLayout(1, 0, 4, 0))
    private val panes = mutableListOf<DevicePane>()
    private val statusLabel = JLabel(" ")
    @Volatile private var serverReady = false

    init {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        bar.add(JButton("Add device", AllIcons.General.Add).apply { addActionListener { addPane() } })
        bar.add(statusLabel)
        add(bar, BorderLayout.NORTH)
        add(
            JBScrollPane(row, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED),
            BorderLayout.CENTER,
        )
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
        )
        panes += pane
        row.add(pane)
        row.revalidate()
        if (serverReady) pane.refresh() else pane.status("Server not ready")
    }

    private fun removePane(pane: DevicePane) {
        panes.remove(pane)
        row.remove(pane)
        pane.dispose()
        row.revalidate()
        row.repaint()
    }

    override fun dispose() {
        panes.forEach { it.dispose() }
        panes.clear()
    }
}
