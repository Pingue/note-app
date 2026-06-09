package app.pennotes.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.pennotes.model.Page
import app.pennotes.model.Stroke
import app.pennotes.model.StrokePoint
import app.pennotes.model.ToolType
import kotlin.math.hypot

/** Mutable holder for the currently selected tool and its per-tool attributes. */
data class ToolSettings(
    var tool: ToolType = ToolType.PEN,
    var penColor: Int = Color.BLACK,
    var penSize: Float = 4f,
    var highlighterColor: Int = Color.YELLOW,
    var highlighterSize: Float = 28f,
    var eraserSize: Float = 40f,
)

/**
 * A custom canvas that renders a single [Page] and captures freehand input from
 * stylus or finger. One pointer draws (or erases); two pointers pan and zoom.
 */
class DrawingView(context: Context) : View(context) {

    private var page: Page = Page()
    var settings: ToolSettings = ToolSettings()

    /** Invoked whenever the page content changes, so the host can autosave. */
    var onChanged: (() -> Unit)? = null

    // View-space transform applied on top of page coordinates.
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private val minScale = 0.25f
    private val maxScale = 8f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
    }
    private val tmpPath = Path()

    private var currentStroke: Stroke? = null
    private val erasedThisGesture = mutableListOf<Stroke>()

    // ---- Undo / redo -------------------------------------------------------
    private sealed interface EditOp
    private data class AddOp(val stroke: Stroke) : EditOp
    private data class EraseOp(val strokes: List<Stroke>) : EditOp

    private val undoStack = ArrayDeque<EditOp>()
    private val redoStack = ArrayDeque<EditOp>()

    fun setPage(newPage: Page) {
        page = newPage
        undoStack.clear()
        redoStack.clear()
        post { fitToWidth() }
        invalidate()
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        when (op) {
            is AddOp -> page.strokes.remove(op.stroke)
            is EraseOp -> page.strokes.addAll(op.strokes)
        }
        redoStack.addLast(op)
        changed()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        when (op) {
            is AddOp -> page.strokes.add(op.stroke)
            is EraseOp -> page.strokes.removeAll(op.strokes.toSet())
        }
        undoStack.addLast(op)
        changed()
    }

    fun resetView() {
        fitToWidth()
        invalidate()
    }

    private fun changed() {
        invalidate()
        onChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToWidth()
    }

    private fun fitToWidth() {
        if (width == 0) return
        val margin = 24f
        scale = (width - margin * 2) / page.width
        offsetX = margin
        offsetY = margin
    }

    private fun toPageX(sx: Float) = (sx - offsetX) / scale
    private fun toPageY(sy: Float) = (sy - offsetY) / scale

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var prevFocusX = 0f
            private var prevFocusY = 0f

            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                prevFocusX = d.focusX
                prevFocusY = d.focusY
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val newScale = (scale * d.scaleFactor).coerceIn(minScale, maxScale)
                val fx = d.focusX
                val fy = d.focusY
                // Keep the focal point anchored while zooming, and pan with it.
                offsetX = fx - (fx - offsetX) * (newScale / scale) + (fx - prevFocusX)
                offsetY = fy - (fy - offsetY) * (newScale / scale) + (fy - prevFocusY)
                scale = newScale
                prevFocusX = fx
                prevFocusY = fy
                invalidate()
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        // Two or more pointers: navigation gesture, never drawing.
        if (event.pointerCount >= 2) {
            if (currentStroke != null) {
                currentStroke = null
                invalidate()
            }
            return true
        }

        val px = toPageX(event.x)
        val py = toPageY(event.y)
        val pressure = event.pressure.takeIf { it > 0f } ?: 1f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                redoStack.clear()
                if (settings.tool == ToolType.ERASER) {
                    erasedThisGesture.clear()
                    eraseAt(px, py)
                } else {
                    currentStroke = newStroke().also {
                        it.points.add(StrokePoint(px, py, pressure))
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (settings.tool == ToolType.ERASER) {
                    eraseAt(px, py)
                } else {
                    currentStroke?.points?.add(StrokePoint(px, py, pressure))
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (settings.tool == ToolType.ERASER) {
                    if (erasedThisGesture.isNotEmpty()) {
                        undoStack.addLast(EraseOp(erasedThisGesture.toList()))
                        erasedThisGesture.clear()
                        onChanged?.invoke()
                    }
                } else {
                    currentStroke?.let { s ->
                        if (s.points.isNotEmpty()) {
                            page.strokes.add(s)
                            undoStack.addLast(AddOp(s))
                            onChanged?.invoke()
                        }
                    }
                    currentStroke = null
                }
                invalidate()
            }
        }
        return true
    }

    private fun newStroke(): Stroke = when (settings.tool) {
        ToolType.PEN -> Stroke(ToolType.PEN, settings.penColor, settings.penSize)
        ToolType.HIGHLIGHTER ->
            Stroke(ToolType.HIGHLIGHTER, settings.highlighterColor, settings.highlighterSize)
        ToolType.ERASER -> Stroke(ToolType.ERASER, Color.TRANSPARENT, settings.eraserSize)
    }

    private fun eraseAt(px: Float, py: Float) {
        val radius = settings.eraserSize / 2f
        val hit = page.strokes.filter { strokeNear(it, px, py, radius) }
        if (hit.isNotEmpty()) {
            page.strokes.removeAll(hit.toSet())
            erasedThisGesture.addAll(hit)
            invalidate()
        }
    }

    private fun strokeNear(stroke: Stroke, px: Float, py: Float, radius: Float): Boolean {
        val pts = stroke.points
        val tol = radius + stroke.width / 2f
        if (pts.size == 1) {
            return hypot(pts[0].x - px, pts[0].y - py) <= tol
        }
        for (i in 0 until pts.size - 1) {
            if (distToSegment(px, py, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y) <= tol) {
                return true
            }
        }
        return false
    }

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(px - ax, py - ay).toFloat()
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = t.coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // Page sheet.
        canvas.drawRect(0f, 0f, page.width, page.height, pagePaint)
        canvas.drawRect(0f, 0f, page.width, page.height, borderPaint)

        for (stroke in page.strokes) drawStroke(canvas, stroke)
        currentStroke?.let { drawStroke(canvas, it) }

        canvas.restore()
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        StrokeRenderer.draw(canvas, stroke, paint, tmpPath)
    }
}
