package app.pennotes.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.pennotes.model.Notebook
import app.pennotes.model.Page
import app.pennotes.model.Stroke
import app.pennotes.model.StrokePoint
import app.pennotes.model.ToolType
import kotlin.math.abs
import kotlin.math.hypot

/** Object eraser deletes whole strokes; ink eraser paints opaque white over them. */
enum class EraserMode { OBJECT, INK }

/** Mutable holder for the currently selected tool and its per-tool attributes. */
data class ToolSettings(
    var tool: ToolType = ToolType.PEN,
    var penColor: Int = Color.BLACK,
    var penSize: Float = 4f,
    var highlighterColor: Int = Color.YELLOW,
    var highlighterSize: Float = 28f,
    var eraserSize: Float = 40f,
    var eraserMode: EraserMode = EraserMode.OBJECT,
)

/**
 * A continuously scrolling, zoomable canvas that stacks every page of a
 * [Notebook] vertically. One pointer draws or erases on whichever page is under
 * it; two pointers pan (scroll) and zoom. Input is captured from stylus or
 * finger alike, with stylus pressure modulating pen width.
 */
class DrawingView(context: Context) : View(context) {

    private var notebook: Notebook = Notebook()
    var settings: ToolSettings = ToolSettings()

    /** When true, only a stylus draws; a single finger scrolls instead. */
    var penMode: Boolean = false

    /** Invoked when stroke content changes, so the host can autosave. */
    var onChanged: (() -> Unit)? = null

    /** Invoked when pages are added/removed/reoriented, so the host can refresh. */
    var onStructureChanged: (() -> Unit)? = null

    // Content transform: screen = content * scale + pan.
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private val minScale = 0.2f
    private val maxScale = 8f
    private val gap = 40f
    private val margin = 24f

    private val density = resources.displayMetrics.density
    // Extra scroll room so pages can clear the floating overlay toolbars,
    // plus a little deadspace above the first and below the last page.
    private val topReserve get() = 64f * density
    private val bottomReserve get() = 200f * density
    private val overscroll get() = 48f * density

    // Single-finger pan state (used in pen mode).
    private var fingerPanning = false
    private var lastPanPointerX = 0f
    private var lastPanPointerY = 0f

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
        color = Color.parseColor("#D0D0D0")
        strokeWidth = 1f
    }
    private val tmpPath = Path()

    // Vertical layout of pages in content space.
    private class Placed(val page: Page, val xOffset: Float, val topY: Float)
    private var placed: List<Placed> = emptyList()
    private var contentWidth = Page.A4_SHORT
    private var contentHeight = Page.A4_LONG

    private var currentStroke: Stroke? = null
    private var activePage: Placed? = null
    private val erasedThisGesture = mutableListOf<Stroke>()

    // ---- Undo / redo (ops remember which page they touched) ---------------
    private sealed interface EditOp
    private data class AddOp(val page: Page, val stroke: Stroke) : EditOp
    private data class EraseOp(val page: Page, val strokes: List<Stroke>) : EditOp

    private val undoStack = ArrayDeque<EditOp>()
    private val redoStack = ArrayDeque<EditOp>()

    init {
        setBackgroundColor(Color.parseColor("#ECECEC"))
    }

    fun setNotebook(nb: Notebook) {
        notebook = nb
        undoStack.clear()
        redoStack.clear()
        recomputeLayout()
        post {
            fitToWidth()
            clampPan()
            invalidate()
        }
        invalidate()
    }

    fun pageCount(): Int = notebook.pages.size

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        when (op) {
            is AddOp -> op.page.strokes.remove(op.stroke)
            is EraseOp -> op.page.strokes.addAll(op.strokes)
        }
        redoStack.addLast(op)
        onChanged?.invoke()
        invalidate()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        when (op) {
            is AddOp -> op.page.strokes.add(op.stroke)
            is EraseOp -> op.page.strokes.removeAll(op.strokes.toSet())
        }
        undoStack.addLast(op)
        onChanged?.invoke()
        invalidate()
    }

    fun resetView() {
        fitToWidth()
        clampPan()
        invalidate()
    }

    // ---- Page structure operations (act on the page nearest the viewport) --

    fun focusedPageIndex(): Int {
        if (placed.isEmpty()) return 0
        val centerContentY = (height / 2f - panY) / scale
        val contained = placed.indexOfFirst {
            centerContentY >= it.topY && centerContentY <= it.topY + it.page.height
        }
        if (contained >= 0) return contained
        return placed.indices.minByOrNull { i ->
            abs(placed[i].topY + placed[i].page.height / 2f - centerContentY)
        } ?: 0
    }

    fun addPage(orientation: app.pennotes.model.PageOrientation) {
        notebook.pages.add(Page(orientation = orientation))
        recomputeLayout()
        onChanged?.invoke()
        onStructureChanged?.invoke()
        post { scrollToPage(notebook.pages.lastIndex) }
        invalidate()
    }

    fun setFocusedPageOrientation(orientation: app.pennotes.model.PageOrientation) {
        val i = focusedPageIndex()
        notebook.pages.getOrNull(i)?.orientation = orientation
        recomputeLayout()
        clampPan()
        onChanged?.invoke()
        onStructureChanged?.invoke()
        invalidate()
    }

    fun deleteFocusedPage() {
        if (notebook.pages.size <= 1) return
        val i = focusedPageIndex()
        notebook.pages.removeAt(i)
        recomputeLayout()
        clampPan()
        onChanged?.invoke()
        onStructureChanged?.invoke()
        invalidate()
    }

    private fun scrollToPage(index: Int) {
        val p = placed.getOrNull(index) ?: return
        panY = margin - p.topY * scale
        clampPan()
        invalidate()
    }

    // ---- Layout & transform -----------------------------------------------

    private fun recomputeLayout() {
        val pages = notebook.pages
        contentWidth = pages.maxOfOrNull { it.width } ?: Page.A4_SHORT
        var y = 0f
        val list = ArrayList<Placed>(pages.size)
        for (p in pages) {
            list.add(Placed(p, (contentWidth - p.width) / 2f, y))
            y += p.height + gap
        }
        contentHeight = if (pages.isEmpty()) Page.A4_LONG else y - gap
        placed = list
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0) fitToWidth()
        clampPan()
    }

    private fun fitToWidth() {
        if (width == 0) return
        scale = ((width - 2 * margin) / contentWidth).coerceIn(minScale, maxScale)
        panX = (width - contentWidth * scale) / 2f
        panY = margin
    }

    private fun clampPan() {
        if (width == 0) return
        val cw = contentWidth * scale
        val ch = contentHeight * scale
        panX = if (cw <= width) (width - cw) / 2f else panX.coerceIn(width - cw, 0f)

        // Vertical bounds keep a band of deadspace at both ends and reserve room
        // so the last/first page can scroll clear of the overlay toolbars.
        val maxPanY = margin + topReserve + overscroll
        val minPanY = height - ch - margin - bottomReserve - overscroll
        panY = if (minPanY > maxPanY) (height - ch) / 2f else panY.coerceIn(minPanY, maxPanY)
    }

    private fun toContentX(sx: Float) = (sx - panX) / scale
    private fun toContentY(sy: Float) = (sy - panY) / scale

    private fun placedAt(cy: Float): Placed? {
        if (placed.isEmpty()) return null
        return placed.firstOrNull { cy >= it.topY && cy <= it.topY + it.page.height }
            ?: placed.minByOrNull { abs(it.topY + it.page.height / 2f - cy) }
    }

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
                panX = fx - (fx - panX) * (newScale / scale) + (fx - prevFocusX)
                panY = fy - (fy - panY) * (newScale / scale) + (fy - prevFocusY)
                scale = newScale
                prevFocusX = fx
                prevFocusY = fy
                clampPan()
                invalidate()
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        // Two or more pointers: scroll/zoom only, never drawing.
        if (event.pointerCount >= 2) {
            if (currentStroke != null) {
                currentStroke = null
                invalidate()
            }
            fingerPanning = false
            return true
        }

        // In pen mode only a stylus draws; a single finger scrolls the canvas.
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (penMode && !isStylus) {
            handleFingerPan(event)
            return true
        }

        val cx = toContentX(event.x)
        val cy = toContentY(event.y)
        val pressure = event.pressure.takeIf { it > 0f } ?: 1f
        val objectErase = settings.tool == ToolType.ERASER && settings.eraserMode == EraserMode.OBJECT

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                redoStack.clear()
                val pl = placedAt(cy) ?: return true
                activePage = pl
                if (objectErase) {
                    erasedThisGesture.clear()
                    eraseAt(pl, cx, cy)
                } else {
                    currentStroke = newStroke().also {
                        it.points.add(localPoint(pl, cx, cy, pressure))
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val pl = activePage ?: return true
                if (objectErase) {
                    eraseAt(pl, cx, cy)
                } else {
                    currentStroke?.points?.add(localPoint(pl, cx, cy, pressure))
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val pl = activePage
                if (objectErase) {
                    if (pl != null && erasedThisGesture.isNotEmpty()) {
                        undoStack.addLast(EraseOp(pl.page, erasedThisGesture.toList()))
                        erasedThisGesture.clear()
                        onChanged?.invoke()
                    }
                } else {
                    currentStroke?.let { s ->
                        if (s.points.isNotEmpty() && pl != null) {
                            pl.page.strokes.add(s)
                            undoStack.addLast(AddOp(pl.page, s))
                            onChanged?.invoke()
                        }
                    }
                    currentStroke = null
                }
                activePage = null
                invalidate()
            }
        }
        return true
    }

    private fun handleFingerPan(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerPanning = true
                lastPanPointerX = event.x
                lastPanPointerY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (fingerPanning) {
                panX += event.x - lastPanPointerX
                panY += event.y - lastPanPointerY
                lastPanPointerX = event.x
                lastPanPointerY = event.y
                clampPan()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerPanning = false
        }
    }

    private fun localPoint(pl: Placed, cx: Float, cy: Float, pressure: Float) =
        StrokePoint(cx - pl.xOffset, cy - pl.topY, pressure)

    private fun newStroke(): Stroke = when (settings.tool) {
        ToolType.PEN -> Stroke(ToolType.PEN, settings.penColor, settings.penSize)
        ToolType.HIGHLIGHTER ->
            Stroke(ToolType.HIGHLIGHTER, settings.highlighterColor, settings.highlighterSize)
        // The ink eraser is a white opaque stroke; object erase never reaches here.
        ToolType.ERASER, ToolType.ERASE_INK ->
            Stroke(ToolType.ERASE_INK, Color.WHITE, settings.eraserSize)
    }

    private fun eraseAt(pl: Placed, cx: Float, cy: Float) {
        val lx = cx - pl.xOffset
        val ly = cy - pl.topY
        val radius = settings.eraserSize / 2f
        val hit = pl.page.strokes.filter { strokeNear(it, lx, ly, radius) }
        if (hit.isNotEmpty()) {
            pl.page.strokes.removeAll(hit.toSet())
            erasedThisGesture.addAll(hit)
            invalidate()
        }
    }

    private fun strokeNear(stroke: Stroke, px: Float, py: Float, radius: Float): Boolean {
        val pts = stroke.points
        val tol = radius + stroke.width / 2f
        if (pts.size == 1) return hypot(pts[0].x - px, pts[0].y - py) <= tol
        for (i in 0 until pts.size - 1) {
            if (distToSegment(px, py, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y) <= tol) return true
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
        canvas.translate(panX, panY)
        canvas.scale(scale, scale)

        val active = activePage
        for (pl in placed) {
            canvas.save()
            canvas.translate(pl.xOffset, pl.topY)
            canvas.clipRect(0f, 0f, pl.page.width, pl.page.height)
            canvas.drawRect(0f, 0f, pl.page.width, pl.page.height, pagePaint)
            for (stroke in pl.page.strokes) StrokeRenderer.draw(canvas, stroke, paint, tmpPath)
            if (active != null && pl.page === active.page) {
                currentStroke?.let { StrokeRenderer.draw(canvas, it, paint, tmpPath) }
            }
            canvas.restore()
            // Border drawn outside the clip so it isn't clipped to half-width.
            canvas.drawRect(
                pl.xOffset, pl.topY,
                pl.xOffset + pl.page.width, pl.topY + pl.page.height,
                borderPaint,
            )
        }
        canvas.restore()
    }
}
