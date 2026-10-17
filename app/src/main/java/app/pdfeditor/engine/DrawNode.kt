package app.pdfeditor.engine

import com.tom_roush.pdfbox.pdmodel.font.PDFont

enum class NodeKind { GROUP, TEXT, PATH, IMAGE }

// One node in the inspector tree, mapped to a contiguous run of content-stream
// tokens [startIndex, endIndex] so it can be highlighted, deleted, or rewritten.
// ctm is the CTM active at the node, used to map page-space edits into the local
// matrix of a q/cm/Q wrapper; font is the run's font, used to re-encode text.
class DrawNode(
    val id: Int,
    val kind: NodeKind,
    val label: String,
    val detail: String,
    val startIndex: Int,
    val endIndex: Int,
    val bounds: Bounds?,
    val colorArgb: Int?,
    val raw: String,
    val children: List<DrawNode>,
    val text: String? = null,
    val ctm: Affine? = null,
    val font: PDFont? = null,
    val fontResourceName: String? = null,
    val fontSize: Float = 0f,
    val colorSpace: String? = null,
    val run: TextRunInfo? = null,
)

class TextState(
    val charSpacing: Float,
    val wordSpacing: Float,
    val horizontalScaling: Float, // Tz, percent
    val leading: Float,
    val rise: Float,
    val renderMode: Int,
)

// Everything needed to re-lay-out and redraw one Tj/TJ/'/" run.
class TextRunInfo(
    val fontResourceName: String,
    val font: PDFont?,
    val fontSize: Float,
    /**
     * Tm at the first glyph; for ' and " this is after their implicit T* move, so a redraw
     * must show the string with Tj, not replay the operator.
     */
    val textMatrix: Affine,
    val ctm: Affine, // CTM at run start
    val state: TextState,
    val fillColorTokens: List<Any>, // tokens that re-establish the current fill
    val originX: Float, // baseline start, page user space
    val originY: Float,
    val endX: Float, // baseline end x, page user space
    val effectiveFontSize: Float, // fontSize x vertical scale of (Tm.CTM)
    val isUpright: Boolean, // (Tm.CTM) has no rotation/skew (b~0, c~0, d>0)
    val text: String,
    val showOperator: String, // "Tj" | "TJ" | "'" | "\""
)

class ParsedPage(
    val tokens: List<Any>,
    val root: DrawNode,
    val leaves: List<DrawNode>,
    val hasTextInForms: Boolean = false,
)

fun findNode(node: DrawNode, id: Int?): DrawNode? {
    if (id == null) return null
    if (node.id == id) return node
    for (c in node.children) findNode(c, id)?.let { return it }
    return null
}

fun collectGroupIds(node: DrawNode, into: MutableSet<Int> = HashSet()): Set<Int> {
    if (node.kind == NodeKind.GROUP) into.add(node.id)
    for (c in node.children) collectGroupIds(c, into)
    return into
}
