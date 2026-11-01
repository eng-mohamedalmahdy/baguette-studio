package dev.baguettestudio

import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/** Small hand-drawn 16x16 glyphs, tinted with the current theme's icon color. */
object DeviceIcons {
    val lock: Icon = glyph { g ->
        g.draw(Arc2D.Double(4.5, 1.5, 7.0, 8.0, 0.0, 180.0, Arc2D.OPEN))
        g.draw(java.awt.geom.Line2D.Double(4.5, 5.5, 4.5, 7.5))
        g.draw(java.awt.geom.Line2D.Double(11.5, 5.5, 11.5, 7.5))
        g.fill(RoundRectangle2D.Double(3.0, 7.0, 10.0, 7.5, 2.0, 2.0))
    }

    val volumeUp: Icon = glyph { g -> speaker(g); wave(g, 9.5, 3.0); wave(g, 9.5, 5.0) }
    val volumeDown: Icon = glyph { g -> speaker(g); wave(g, 9.5, 3.0) }

    private fun speaker(g: Graphics2D) {
        val p = Path2D.Double().apply {
            moveTo(1.5, 6.0); lineTo(4.0, 6.0); lineTo(7.5, 3.0)
            lineTo(7.5, 13.0); lineTo(4.0, 10.0); lineTo(1.5, 10.0); closePath()
        }
        g.fill(p)
    }

    /** Sound wave: arc centered at x0,y=8, [size] is its radius. */
    private fun wave(g: Graphics2D, x0: Double, size: Double) {
        val r = size
        g.draw(Arc2D.Double(x0 - r + 1.5, 8.0 - r, r * 2, r * 2, -50.0, 100.0, Arc2D.OPEN))
    }

    private fun glyph(paint: (Graphics2D) -> Unit) = object : Icon {
        override fun getIconWidth() = 16
        override fun getIconHeight() = 16
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = UIUtil.getLabelForeground()
                g2.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.translate(x, y)
                paint(g2)
            } finally {
                g2.dispose()
            }
        }
    }
}
