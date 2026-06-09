package app.pennotes.drawing

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.pennotes.model.Stroke
import app.pennotes.model.ToolType

/**
 * Single source of truth for how a [Stroke] is painted, shared by the live
 * editor ([DrawingView]) and the PDF/JPG exporter so on-screen and exported
 * output match exactly.
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
                path.reset()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                if (pts.size == 1) path.lineTo(pts[0].x + 0.1f, pts[0].y)
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
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    val pressure = (a.pressure + b.pressure) / 2f
                    paint.strokeWidth = stroke.width * (0.4f + 0.6f * pressure.coerceIn(0f, 1f))
                    canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                }
            }

            ToolType.ERASER -> { /* erasers mutate the stroke list, nothing to draw */ }
        }
    }
}
