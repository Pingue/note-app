package app.pennotes.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** The three drawing tools the editor exposes. */
enum class ToolType { PEN, HIGHLIGHTER, ERASER }

/** A page can be laid out tall or wide; this also drives PDF/JPG export size. */
enum class PageOrientation { PORTRAIT, LANDSCAPE }

/**
 * A single sampled input point in *page* coordinate space (not screen space),
 * so strokes render and export identically regardless of zoom or device size.
 */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
)

/**
 * One continuous stroke. Colour is stored as a packed ARGB Int. [width] is the
 * base stroke width in page units; pen strokes additionally scale by pressure.
 */
@Serializable
data class Stroke(
    val tool: ToolType,
    val color: Int,
    val width: Float,
    val points: MutableList<StrokePoint> = mutableListOf(),
)

/**
 * A page holds an ordered list of strokes. Logical dimensions default to A4 at
 * ~150dpi; orientation swaps width/height.
 */
@Serializable
data class Page(
    val id: String = UUID.randomUUID().toString(),
    var orientation: PageOrientation = PageOrientation.PORTRAIT,
    val strokes: MutableList<Stroke> = mutableListOf(),
) {
    val width: Float get() = if (orientation == PageOrientation.PORTRAIT) A4_SHORT else A4_LONG
    val height: Float get() = if (orientation == PageOrientation.PORTRAIT) A4_LONG else A4_SHORT

    companion object {
        const val A4_SHORT = 1240f
        const val A4_LONG = 1754f
    }
}

/** A notebook file: a titled, ordered collection of pages. */
@Serializable
data class Notebook(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Untitled",
    val pages: MutableList<Page> = mutableListOf(Page()),
    var updatedAt: Long = System.currentTimeMillis(),
)
