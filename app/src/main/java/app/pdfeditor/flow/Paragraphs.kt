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
    private const val EDGE_FIT = 0.01f // page units of rounding slack for "the next word would have fit"
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

        val spans = buildSpans(lines, Geometry(left, right, firstLineIndent))
        val id = ParagraphId(page.pageIndex, lines[0].blockIndex, sub)
        val text = spans.joinToString("") { it.text }
        return Paragraph(
            id, lines.map { it.blockId }, spans, left, right, firstLineIndent, lineAdvance,
            first.baseline, alignment, canGrow = !unit && page.pinned == null, nonLatin = !isLatinText(text), runNodes = lines.flatMap { it.nodes },
        )
    }

    /** Paragraph box the hard-break test measures against. */
    private class Geometry(val left: Float, val right: Float, val firstLineIndent: Float)

    /**
     * Spans of whole visual lines (each a list of run nodes), built exactly as a detected paragraph's spans; the
     * hard-break test uses [p]'s box.
     */
    internal fun spansOf(lines: List<List<DrawNode>>, p: Paragraph): List<Span> =
        buildSpans(
            lines.filter { l -> l.any { it.run != null } }.map { l -> Line(l.filter { it.run != null }.sortedBy { it.run!!.originX }, BlockId.Orig(-1, -1), -1) },
            Geometry(p.left, p.right, p.firstLineIndent),
        )

    /**
     * True when [prev] ended with a hard line break: the next line's first word (plus a space) would still have fit
     * on [prev] inside the paragraph box. Unmeasurable widths count as a hard break.
     */
    private fun hardBreak(prev: Line, next: Line, isFirst: Boolean, g: Geometry): Boolean {
        val run = next.runs[0]
        val word = run.text.trimStart().takeWhile { !it.isWhitespace() }
        if (word.isEmpty()) return false
        val last = prev.runs.last()
        return try {
            val wordW = measure(word, run) ?: return true
            val spaceW = if (last.text.endsWith(" ")) 0f else measure(" ", last) ?: return true
            val avail = g.right - g.left - (if (isFirst) g.firstLineIndent else 0f)
            (prev.right - prev.left) + spaceW + wordW <= avail + EDGE_FIT
        } catch (_: Exception) {
            true
        }
    }

    /** Page-space width of [text] in [run]'s font and state, or null when the font can't measure it. */
    private fun measure(text: String, run: TextRunInfo): Float? {
        val font = run.font ?: return null
        val style = styleOf(run)
        return try {
            ParagraphLayout.textWidth(text, style, font)
        } catch (_: Exception) {
            // Subset fonts with a built-in encoding can't encode: measure through the font's own code → Unicode map.
            val widths = GlyphWidths.of(font)
            var glyphs = 0f
            for (c in text) glyphs += widths[c] ?: return null
            glyphs / 1000f * style.effectiveFontSize * style.horizontalScale + style.charSpacing * text.length
        }
    }

    private fun buildSpans(lines: List<Line>, g: Geometry): List<Span> {
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
        fun appendBreak() {
            val last = spans.lastOrNull() ?: return
            spans[spans.lastIndex] = Span(last.text.trimEnd(' ') + "\n", last.style)
        }
        val hard = BooleanArray(lines.size) { li -> li > 0 && hardBreak(lines[li - 1], lines[li], li == 1, g) }
        if (hard.any { it }) {
            // A block with hard breaks (address, list): its widest line can't be tested (nothing fits past the box
            // edge it defines), so it breaks hard too.
            val widest = lines.maxOf { it.right }
            for (li in 1 until lines.size) if (lines[li - 1].right >= widest - EDGE_TOL) hard[li] = true
        }
        lines.forEachIndexed { li, line ->
            if (li > 0) {
                if (hard[li]) appendBreak() else appendSpace(line.runs[0].text)
            }
            line.runs.forEachIndexed { ri, r ->
                if (ri > 0) {
                    val gap = r.originX - line.runs[ri - 1].endX
                    if (gap > SPACE_GAP_IN_SIZES * line.size) appendSpace(r.text)
                }
                append(r.text, styleOf(r))
            }
        }
        return spans
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

/** Per font, glyph-space width of each character the font maps back to Unicode (cached by font dictionary). */
internal object GlyphWidths {
    private val cache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Map<Char, Float>>())

    fun of(font: PDFont): Map<Char, Float> = cache.getOrPut(font.cosObject) {
        val out = HashMap<Char, Float>()
        val maxCode = if (font is com.tom_roush.pdfbox.pdmodel.font.PDType0Font) 0xFFFF else 0xFF
        for (code in 0..maxCode) {
            val u = try { font.toUnicode(code) } catch (_: Exception) { null } ?: continue
            if (u.length != 1 || u[0] in out) continue
            val w = try { font.getWidth(code) } catch (_: Exception) { continue }
            if (w > 0f || u[0] == ' ') out[u[0]] = w
        }
        out
    }
}
