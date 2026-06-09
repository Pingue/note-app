package app.pennotes.drawing

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.pennotes.model.Stroke
import app.pennotes.model.StrokePoint
import app.pennotes.model.ToolType

/**
 * Single source of truth for how a [Stroke] is painted, shared by the live
 * editor ([DrawingView]) and the PDF/JPG exporter so on-screen and exported
 * output match exactly.
 *
 * Sampled points are joined with quadratic curves through segment midpoints
 * (each point acts as the control of the curve between its neighbouring
 * midpoints), so handwriting stays rounded instead of polygonal.
 */
object StrokeRenderer {

    fun draw(canvas: Canvas, stroke: Stroke, paint: Paint, path: Path) {
        val pts = stroke.points
        if (pts.isEmpty()) return

        when (stroke.tool) {
            ToolType.HIGHLIGHTER -> {
                paint.color = stroke.color
                paint.alpha = 90
                paint.strokeWidth = stroke.width
                buildSmoothPath(path, pts)
                canvas.drawPath(path, paint)
            }

            ToolType.PEN -> {
                paint.color = stroke.color
                paint.alpha = 255
                if (pts.size == 1) {
                    paint.strokeWidth = stroke.width
                    canvas.drawPoint(pts[0].x, pts[0].y, paint)
                    return
                }
                // Pressure varies along the stroke, so it's drawn as a chain of
                // short quadratic spans — midpoint to midpoint with the sample
                // point as control — each at its own width. Round caps blend
                // the width steps together.
                var prevMidX = pts[0].x
                var prevMidY = pts[0].y
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    val midX = if (i == pts.size - 1) b.x else (a.x + b.x) / 2f
                    val midY = if (i == pts.size - 1) b.y else (a.y + b.y) / 2f
                    val pressure = (a.pressure + b.pressure) / 2f
                    paint.strokeWidth = stroke.width * (0.4f + 0.6f * pressure.coerceIn(0f, 1f))
                    path.reset()
                    path.moveTo(prevMidX, prevMidY)
                    path.quadTo(a.x, a.y, midX, midY)
                    canvas.drawPath(path, paint)
                    prevMidX = midX
                    prevMidY = midY
                }
            }

            ToolType.ERASE_INK -> {
                // "Rub out to white": an opaque, constant-width background-coloured stroke.
                paint.color = stroke.color
                paint.alpha = 255
                paint.strokeWidth = stroke.width
                buildSmoothPath(path, pts)
                canvas.drawPath(path, paint)
            }

            ToolType.ERASER -> { /* object eraser mutates the stroke list, nothing to draw */ }
        }
    }

    /** One smooth constant-width path: quads through midpoints, line caps at the ends. */
    private fun buildSmoothPath(path: Path, pts: List<StrokePoint>) {
        path.reset()
        path.moveTo(pts[0].x, pts[0].y)
        when (pts.size) {
            1 -> path.lineTo(pts[0].x + 0.1f, pts[0].y)
            2 -> path.lineTo(pts[1].x, pts[1].y)
            else -> {
                for (i in 1 until pts.size - 1) {
                    val midX = (pts[i].x + pts[i + 1].x) / 2f
                    val midY = (pts[i].y + pts[i + 1].y) / 2f
                    path.quadTo(pts[i].x, pts[i].y, midX, midY)
                }
                path.lineTo(pts[pts.size - 1].x, pts[pts.size - 1].y)
            }
        }
    }
}
