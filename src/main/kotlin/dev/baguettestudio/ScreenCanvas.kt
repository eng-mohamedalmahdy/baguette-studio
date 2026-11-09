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

    /** Clockwise quarter turns applied to the *view only*; independent of the simulator's orientation. */
    @Volatile var viewRotation = 0

    /** Width / height of the frame as displayed (after view rotation). */
    fun displayAspect(): Double? {
        val img = image ?: return null
        return if (viewRotation % 2 == 0) img.width.toDouble() / img.height else img.height.toDouble() / img.width
    }

    /** The frame as currently displayed (view rotation applied), for screenshots. */
    fun snapshot(): BufferedImage? {
        val img = image ?: return null
        val odd = viewRotation % 2 == 1
        val out = BufferedImage(if (odd) img.height else img.width, if (odd) img.width else img.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try { drawFrame(g, img, out.width, out.height) } finally { g.dispose() }
        return out
    }

    /** Draws [img] rotated by [viewRotation] to fill a [w]x[h] box at the origin (box is already rotated size). */
    private fun drawFrame(g2: Graphics2D, img: BufferedImage, w: Int, h: Int) {
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val odd = viewRotation % 2 == 1
        val iw = if (odd) h else w
        val ih = if (odd) w else h
        g2.translate(w / 2.0, h / 2.0)
        g2.rotate(Math.PI / 2 * viewRotation)
        g2.drawImage(img, -iw / 2, -ih / 2, iw, ih, null)
    }

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
                edge = image?.let { edgeFor(p.first, p.second, it.width, it.height) }
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

    /** Back gesture: swipe in from the left edge (LTR) or right edge (RTL), as seen on screen. */
    fun backGesture(rtl: Boolean) =
        if (rtl) swipe(0.99, 0.5, 0.55, 0.5) else swipe(0.01, 0.5, 0.45, 0.5)

    /** Notification center: swipe down from the top edge, as seen on screen. */
    fun notificationGesture() = swipe(0.25, 0.005, 0.25, 0.6)

    /**
     * Scripted swipe between two points given as fractions of the *displayed* frame, so it follows
     * both the simulator's orientation (frame size) and the view rotation.
     */
    private fun swipe(x0: Double, y0: Double, x1: Double, y1: Double) {
        val img = image ?: return
        val w = img.width; val h = img.height
        val steps = 8
        Thread({
            fun msg(t: String, f: Double): String {
                val (px, py) = toImage(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, w, h)
                val e = edgeFor(px, py, w, h)?.let { ""","edge":"$it"""" } ?: ""
                return """{"type":"$t","x":$px,"y":$py,"width":$w,"height":$h$e}"""
            }
            send(msg("touch1-down", 0.0), false)
            for (i in 1..steps) {
                Thread.sleep(16)
                send(msg("touch1-move", i.toDouble() / steps), false)
            }
            send(msg("touch1-up", 1.0), false)
        }, "baguette-gesture").apply { isDaemon = true }.start()
    }

    /** Displayed fraction (0..1) -> image pixel, undoing the view rotation. */
    private fun toImage(dx: Double, dy: Double, w: Int, h: Int): Pair<Int, Int> {
        val (x, y) = when (viewRotation % 4) {
            1 -> Pair(dy, 1 - dx)
            2 -> Pair(1 - dx, 1 - dy)
            3 -> Pair(1 - dy, dx)
            else -> Pair(dx, dy)
        }
        return Pair((x.coerceIn(0.0, 1.0) * (w - 1)).toInt(), (y.coerceIn(0.0, 1.0) * (h - 1)).toInt())
    }

    private fun touch(type: String, p: Pair<Int, Int>): String {
        val img = image!!
        val e = edge?.let { ""","edge":"$it"""" } ?: ""
        return """{"type":"$type","x":${p.first},"y":${p.second},"width":${img.width},"height":${img.height}$e}"""
    }

    private fun edgeFor(x: Int, y: Int, w: Int, h: Int): String? {
        return when {
            x < w * 0.03 -> "left"
            x > w * 0.97 -> "right"
            y > h * 0.93 -> "bottom"
            y < h * 0.07 -> "top"
            else -> null
        }
    }

    private fun fit(): java.awt.Rectangle? {
        val img = image ?: return null
        val odd = viewRotation % 2 == 1
        val dw = if (odd) img.height else img.width
        val dh = if (odd) img.width else img.height
        val s = minOf(width.toDouble() / dw, height.toDouble() / dh)
        val w = (dw * s).toInt()
        val h = (dh * s).toInt()
        return java.awt.Rectangle((width - w) / 2, (height - h) / 2, w, h)
    }

    /** Component point -> image pixel coords, or null if outside the frame (unless [clamp]). */
    private fun map(e: MouseEvent, clamp: Boolean = false): Pair<Int, Int>? {
        val img = image ?: return null
        val r = fit() ?: return null
        var dx = (e.x - r.x).toDouble() / r.width
        var dy = (e.y - r.y).toDouble() / r.height
        if (!clamp && (dx !in 0.0..1.0 || dy !in 0.0..1.0)) return null
        return toImage(dx, dy, img.width, img.height)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = Color(0x1e1e1e)
        g2.fillRect(0, 0, width, height)
        val img = image
        val r = fit()
        if (img != null && r != null) {
            val g3 = g2.create() as Graphics2D
            try {
                g3.translate(r.x, r.y)
                drawFrame(g3, img, r.width, r.height)
            } finally { g3.dispose() }
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
