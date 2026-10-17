package app.pdfeditor.flow

import app.pdfeditor.engine.DrawNode
import app.pdfeditor.engine.TextRunInfo
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.contentstream.operator.Operator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Alignment { LEFT, RIGHT, CENTER, JUSTIFIED }

data class SpanStyle(
    val fontResourceName: String,
    val effectiveFontSize: Float,
    val horizontalScale: Float,
    val fillColorTokens: List<String>,
    val rise: Float,
    val charSpacing: Float,
) {
    var font: PDFont? = null // declared in the body, so not part of equals/hashCode
}

data class Span(val text: String, val style: SpanStyle)

class Paragraph(
    val id: ParagraphId,
    val lineBlocks: List<BlockId>,
    val spans: List<Span>,
    val left: Float,
    val right: Float,
    val firstLineIndent: Float,
    val lineAdvance: Float, // baseline-to-baseline
    val firstBaseline: Float,
    val alignment: Alignment,
    val canGrow: Boolean, // false inside a UNIT or on a pinned page
    val nonLatin: Boolean,
    val runNodes: List<DrawNode>, // exact run nodes composing the paragraph, in reading order
)

fun Paragraph.text(): String = spans.joinToString("") { it.text }

/** True when every char is Basic Latin, Latin-1 Supplement, Latin Extended-A/B or General Punctuation. */
fun isLatinText(s: String): Boolean = s.all { it.code <= 0x024F || it.code in 0x2000..0x206F }

object ParagraphDetector {
    private const val SIZE_TOL = 0.5f
    private const val EDGE_TOL = 2f
    private const val GAP_RATIO_TOL = 0.1f
    private const val MAX_GAP_IN_SIZES = 1.5f
    private const val SPACE_GAP_IN_SIZES = 0.2f
    private const val COLUMN_GAP_IN_SIZES = 1.5f // x gap that splits a UNIT row into separate column pieces

    /** One visual text line: its runs left to right plus the geometry the join rules use. */
    private class Line(val nodes: List<DrawNode>, val blockId: BlockId, val blockIndex: Int) {
        val runs: List<TextRunInfo> = nodes.map { it.run!! }
        val left = runs.minOf { it.originX }
        val right = runs.maxOf { it.endX }
        val size: Float = runs.groupBy { it.effectiveFontSize }
            .maxByOrNull { (_, rs) -> rs.sumOf { it.text.length } }!!.key
        val baseline: Float = runs.first { it.effectiveFontSize == size }.originY
        val mid get() = (left + right) / 2f
    }

    private class Group(val lines: List<Line>, val unit: Boolean, val firstBlock: Int)

    fun detect(page: PageAnalysis): List<Paragraph> {
        val groups = ArrayList<Group>()
        var bodyRight = Float.NEGATIVE_INFINITY
        page.blocks.forEachIndexed { index, b ->
            when (b.kind) {
                BlockKind.LINE -> {
                    val nodes = b.runNodes.filter { it.run != null }.sortedBy { it.run!!.originX }
                    if (nodes.isNotEmpty()) {
                        val line = Line(nodes, b.id, index)
                        bodyRight = max(bodyRight, line.right)
                        // A LINE block may directly follow the previous line group; collected into one run below.
                        groups += Group(listOf(line), false, index)
                    }
                }
                BlockKind.UNIT -> groups += unitGroups(b, index)
                BlockKind.GRAPHIC -> groups += Group(emptyList(), false, index) // breaks paragraphs
            }
        }
        val result = ArrayList<Paragraph>()
        val unitCounters = HashMap<Int, Int>() // UNIT block index -> paragraphs emitted so far
        // Consecutive single-line non-unit groups form one stream; unit groups and graphics stand alone.
        var stream = ArrayList<Pair<Line, Int>>()
        fun flush() {
            if (stream.isNotEmpty()) {
                build(stream.map { it.first }, false, page, bodyRight, result)
                stream = ArrayList()
            }
        }
        for (g in groups) {
            if (g.unit) {
                flush()
                build(g.lines, true, page, bodyRight, result, g.firstBlock, unitCounters)
            } else if (g.lines.isEmpty()) flush()
            else stream += g.lines.single() to g.firstBlock
        }
        flush()
        return result
    }

    /** Splits a UNIT's runs into column pieces and returns one stream-group per column (top to bottom). */
    private fun unitGroups(b: Block, index: Int): List<Group> {
        val nodes = b.runNodes.filter { it.run != null }
        if (nodes.isEmpty()) return emptyList()
        val nodeOf = nodes.associateBy { it.run!! }
        val runs = nodes.map { it.run!! }
        // Rows by baseline.
        val rows = ArrayList<MutableList<TextRunInfo>>()
        for (r in runs.sortedByDescending { it.originY }) {
            val row = rows.lastOrNull()
            if (row != null && abs(row[0].originY - r.originY) <= 0.3f * r.effectiveFontSize) row += r else rows += mutableListOf(r)
        }
        // Pieces: split each row where the x gap is a column gutter.
        val pieces = ArrayList<List<TextRunInfo>>()
        for (row in rows) {
            var cur = ArrayList<TextRunInfo>()
            for (r in row.sortedBy { it.originX }) {
                if (cur.isNotEmpty() && r.originX - cur.maxOf { it.endX } > COLUMN_GAP_IN_SIZES * r.effectiveFontSize) {
                    pieces += cur; cur = ArrayList()
                }
                cur += r
            }
            if (cur.isNotEmpty()) pieces += cur
        }
        // Columns: merge horizontally overlapping pieces.
        class Col(var l: Float, var r: Float, val ps: MutableList<List<TextRunInfo>>)
        val cols = ArrayList<Col>()
        for (p in pieces.sortedBy { piece -> piece.minOf { it.originX } }) {
            val pl = p.minOf { it.originX }; val pr = p.maxOf { it.endX }
            val c = cols.firstOrNull { pl < it.r && pr > it.l }
            if (c == null) cols += Col(pl, pr, mutableListOf(p))
            else { c.l = min(c.l, pl); c.r = max(c.r, pr); c.ps += p }
        }
        return cols.sortedBy { it.l }.map { c ->
            val lines = c.ps.sortedByDescending { p -> p.first().originY }
                .map { p -> Line(p.map { nodeOf.getValue(it) }, b.id, index) }
            Group(lines, true, index)
        }
    }

    private fun joins(cur: List<Line>, next: Line): Boolean {
        val first = cur[0]
        val prev = cur.last()
        if (abs(next.size - first.size) > SIZE_TOL) return false
        val gap = prev.baseline - next.baseline
        if (gap <= 0f || gap > MAX_GAP_IN_SIZES * first.size) return false
        if (cur.size >= 2) {
            val firstGap = cur[0].baseline - cur[1].baseline
            if (abs(gap - firstGap) > GAP_RATIO_TOL * firstGap) return false
        }
        if (cur.size == 1) {
            // Second line: same left edge, first-line indent (up to 4 sizes), or same midpoint / right edge.
            val d = first.left - next.left
            return abs(d) <= EDGE_TOL || (d > EDGE_TOL && d <= 4f * first.size) ||
                abs(next.mid - first.mid) <= EDGE_TOL || abs(next.right - first.right) <= EDGE_TOL
        }
        val ref = cur[1]
        val leftOk = abs(next.left - ref.left) <= EDGE_TOL
        val centred = cur.all { abs(it.mid - first.mid) <= EDGE_TOL } && abs(next.mid - first.mid) <= EDGE_TOL
        val rightAligned = cur.all { abs(it.right - first.right) <= EDGE_TOL } && abs(next.right - first.right) <= EDGE_TOL
        return leftOk || centred || rightAligned
    }

    private fun build(
        lines: List<Line>, unit: Boolean, page: PageAnalysis,
        bodyRight: Float, out: MutableList<Paragraph>, unitIndex: Int = 0, unitCounters: MutableMap<Int, Int> = HashMap(),
    ) {
        var i = 0
        while (i < lines.size) {
            val cur = mutableListOf(lines[i])
            i++
            while (i < lines.size && joins(cur, lines[i])) { cur += lines[i]; i++ }
            val sub = if (unit) unitCounters.merge(unitIndex, 1, Int::plus)!! else 0
            out += make(cur, sub, unit, page, bodyRight)
        }
    }

    private fun make(lines: List<Line>, sub: Int, unit: Boolean, page: PageAnalysis, bodyRight: Float): Paragraph {
        val first = lines[0]
        val n = lines.size
        val left = lines.minOf { it.left }
        var right = lines.maxOf { it.right }
        if (n == 1 && !unit) right = max(right, bodyRight)
                val lineAdvance = if (n >= 2) first.baseline - lines[1].baseline else 1.2f * first.size
        val lineRight = lines.maxOf { it.right }

        val alignment = when {
            n < 2 -> Alignment.LEFT
            // Needs >= 3 lines: with 2, the single non-last line is trivially at the right edge (vacuous).
            n >= 3 && lines.dropLast(1).all { abs(it.right - lineRight) <= EDGE_TOL } &&
                lines.drop(1).all { abs(it.left - lines[1].left) <= EDGE_TOL } -> Alignment.JUSTIFIED
            lines.all { abs(it.left - first.left) <= EDGE_TOL } -> Alignment.LEFT
            lines.all { abs(it.mid - first.mid) <= EDGE_TOL } -> Alignment.CENTER
            lines.all { abs(it.right - first.right) <= EDGE_TOL } -> Alignment.RIGHT
            else -> Alignment.LEFT
        }

        val firstLineIndent =
            if (n >= 2 && (alignment == Alignment.LEFT || alignment == Alignment.JUSTIFIED)) max(0f, first.left - lines[1].left) else 0f

        val spans = ArrayList<Span>()
        fun append(text: String, style: SpanStyle) {
            if (text.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.style == style) {
                spans[spans.lastIndex] = Span(last.text + text, last.style)
            } else spans += Span(text, style)
        }
        fun appendSpace(next: String) {
            val last = spans.lastOrNull() ?: return
            if (!last.text.endsWith(" ") && !next.firstOrNull().let { it != null && it.isWhitespace() }) spans[spans.lastIndex] = Span(last.text + " ", last.style)
        }
        lines.forEachIndexed { li, line ->
            if (li > 0) appendSpace(line.runs[0].text)
            line.runs.forEachIndexed { ri, r ->
                if (ri > 0) {
                    val gap = r.originX - line.runs[ri - 1].endX
                    if (gap > SPACE_GAP_IN_SIZES * line.size) appendSpace(r.text)
                }
                append(r.text, styleOf(r))
            }
        }
        val id = ParagraphId(page.pageIndex, lines[0].blockIndex, sub)
        val text = spans.joinToString("") { it.text }
        return Paragraph(
            id, lines.map { it.blockId }, spans, left, right, firstLineIndent, lineAdvance,
            first.baseline, alignment, canGrow = !unit && page.pinned == null, nonLatin = !isLatinText(text), runNodes = lines.flatMap { it.nodes },
        )
    }

    private fun styleOf(r: TextRunInfo) = SpanStyle(
        r.fontResourceName, r.effectiveFontSize, r.state.horizontalScaling / 100f,
        r.fillColorTokens.map(::tokenString), r.state.rise, r.state.charSpacing,
    ).also { it.font = r.font }

    private fun tokenString(t: Any): String = when (t) {
        is COSNumber -> {
            java.math.BigDecimal(t.floatValue().toString()).stripTrailingZeros().toPlainString()
        }
        is Operator -> t.name
        is COSName -> "/" + t.name
        else -> t.toString()
    }
}
