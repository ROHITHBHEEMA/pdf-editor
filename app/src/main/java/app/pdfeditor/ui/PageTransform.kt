package app.pdfeditor.ui

import androidx.compose.ui.geometry.Rect
import app.pdfeditor.engine.Bounds

// Maps PDF user-space bounds onto the rendered page bitmap (pixels). Rotation
// 0 is exact; 90/180/270 are best-effort and may be slightly off on the canvas
// highlight, but tree selection and deletion never depend on this mapping.
class PageTransform(
    private val cropX: Float,
    private val cropY: Float,
    private val cropW: Float,
    private val cropH: Float,
    private val rotation: Int,
    private val scale: Float,
) {
    fun toRect(b: Bounds): Rect {
        val xs = floatArrayOf(b.minX, b.maxX, b.maxX, b.minX)
        val ys = floatArrayOf(b.minY, b.minY, b.maxY, b.maxY)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        val rot = ((rotation % 360) + 360) % 360
        for (i in 0..3) {
            val u = xs[i] - cropX
            val v = ys[i] - cropY
            val dx: Float
            val dy: Float
            when (rot) {
                90 -> { dx = v; dy = u }
                180 -> { dx = cropW - u; dy = v }
                270 -> { dx = cropH - v; dy = cropW - u }
                else -> { dx = u; dy = cropH - v }
            }
            val bx = dx * scale
            val by = dy * scale
            if (bx < left) left = bx
            if (by < top) top = by
            if (bx > right) right = bx
            if (by > bottom) bottom = by
        }
        return Rect(left, top, right, bottom)
    }
}
