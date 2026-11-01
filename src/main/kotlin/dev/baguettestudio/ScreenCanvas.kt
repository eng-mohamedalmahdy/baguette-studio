package dev.baguettestudio

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent

/** Paints the latest simulator frame (aspect-fit) and turns mouse/keyboard into Baguette JSON input. */
class ScreenCanvas(private val send: (json: String, droppable: Boolean) -> Unit) : JComponent() {
    @Volatile var image: BufferedImage? = null
    var message: String = "No device selected"

    private var dragging = false
    private var edge: String? = null

    init {
        isOpaque = true
        isFocusable = true
        preferredSize = Dimension(320, 640)
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                val p = map(e) ?: return
                dragging = true
                edge = edgeFor(p.second)
                send(touch("touch1-down", p), false)
            }
            override fun mouseDragged(e: MouseEvent) {
                if (!dragging) return
                send(touch("touch1-move", map(e, clamp = true) ?: return), true)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (!dragging) return
                dragging = false
                send(touch("touch1-up", map(e, clamp = true) ?: return), false)
                edge = null
            }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val dy = -e.preciseWheelRotation * 40
                send("""{"type":"scroll","deltaX":0,"deltaY":$dy}""", true)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val code = when (e.keyCode) {
                    KeyEvent.VK_BACK_SPACE -> "Backspace"
                    KeyEvent.VK_ENTER -> "Enter"
                    KeyEvent.VK_TAB -> "Tab"
                    KeyEvent.VK_ESCAPE -> "Escape"
                    KeyEvent.VK_LEFT -> "ArrowLeft"
                    KeyEvent.VK_RIGHT -> "ArrowRight"
                    KeyEvent.VK_UP -> "ArrowUp"
                    KeyEvent.VK_DOWN -> "ArrowDown"
                    else -> return
                }
                send("""{"type":"key","code":"$code"}""", false)
            }
            override fun keyTyped(e: KeyEvent) {
                val c = e.keyChar
                if (c.isISOControl() || e.isMetaDown || e.isControlDown) return
                send("""{"type":"type","text":${jsonString(c.toString())}}""", false)
            }
        })
    }

    private fun touch(type: String, p: Pair<Int, Int>): String {
        val img = image!!
        val e = edge?.let { ""","edge":"$it"""" } ?: ""
        return """{"type":"$type","x":${p.first},"y":${p.second},"width":${img.width},"height":${img.height}$e}"""
    }

    private fun edgeFor(y: Int): String? {
        val h = image?.height ?: return null
        return when {
            y > h * 0.93 -> "bottom"
            y < h * 0.07 -> "top"
            else -> null
        }
    }

    private fun fit(): java.awt.Rectangle? {
        val img = image ?: return null
        val s = minOf(width.toDouble() / img.width, height.toDouble() / img.height)
        val w = (img.width * s).toInt()
        val h = (img.height * s).toInt()
        return java.awt.Rectangle((width - w) / 2, (height - h) / 2, w, h)
    }

    /** Component point -> image pixel coords, or null if outside the frame (unless [clamp]). */
    private fun map(e: MouseEvent, clamp: Boolean = false): Pair<Int, Int>? {
        val img = image ?: return null
        val r = fit() ?: return null
        var x = (e.x - r.x).toDouble() / r.width
        var y = (e.y - r.y).toDouble() / r.height
        if (!clamp && (x !in 0.0..1.0 || y !in 0.0..1.0)) return null
        x = x.coerceIn(0.0, 1.0); y = y.coerceIn(0.0, 1.0)
        return Pair((x * (img.width - 1)).toInt(), (y * (img.height - 1)).toInt())
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = Color(0x1e1e1e)
        g2.fillRect(0, 0, width, height)
        val img = image
        val r = fit()
        if (img != null && r != null) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.drawImage(img, r.x, r.y, r.width, r.height, null)
        } else {
            g2.color = Color.GRAY
            val fm = g2.fontMetrics
            g2.drawString(message, (width - fm.stringWidth(message)) / 2, height / 2)
        }
    }

    companion object {
        fun jsonString(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
