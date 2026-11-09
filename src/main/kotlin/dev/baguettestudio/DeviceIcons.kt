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

    val bell: Icon = glyph { g ->
        val body = Path2D.Double().apply {
            moveTo(3.0, 11.5); lineTo(3.0, 10.5); quadTo(4.5, 9.5, 4.5, 6.5)
            curveTo(4.5, 3.0, 11.5, 3.0, 11.5, 6.5)
            quadTo(11.5, 9.5, 13.0, 10.5); lineTo(13.0, 11.5); closePath()
        }
        g.fill(body)
        g.draw(java.awt.geom.Line2D.Double(8.0, 1.8, 8.0, 3.2))
        g.fill(Arc2D.Double(6.2, 11.0, 3.6, 3.6, 180.0, 180.0, Arc2D.CHORD))
    }

    /** Phone outline with a curved arrow: rotate the device 90 degrees. */
    val rotateDevice: Icon = glyph { g ->
        g.draw(RoundRectangle2D.Double(2.5, 5.5, 6.0, 9.0, 1.5, 1.5))
        g.draw(Arc2D.Double(6.0, 1.0, 8.0, 8.0, 90.0, -150.0, Arc2D.OPEN))
        val head = Path2D.Double().apply { moveTo(12.6, 6.6); lineTo(14.6, 7.2); lineTo(13.6, 9.2); closePath() }
        g.fill(head)
    }

    /** Circular arrow around a small screen: rotate only the on-screen view. */
    val rotateView: Icon = glyph { g ->
        g.draw(Arc2D.Double(2.0, 2.0, 12.0, 12.0, 60.0, 270.0, Arc2D.OPEN))
        g.fill(Path2D.Double().apply { moveTo(10.5, 0.8); lineTo(14.8, 3.0); lineTo(10.2, 5.2); closePath() })
        g.fill(RoundRectangle2D.Double(5.5, 5.5, 5.0, 5.0, 1.0, 1.0))
    }

    val camera: Icon = glyph { g ->
        g.draw(RoundRectangle2D.Double(1.5, 4.5, 13.0, 9.0, 2.0, 2.0))
        g.fill(RoundRectangle2D.Double(5.5, 2.5, 5.0, 2.5, 1.0, 1.0))
        g.draw(java.awt.geom.Ellipse2D.Double(5.2, 6.5, 5.6, 5.6))
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
