package app.pdfeditor.flow

import app.pdfeditor.engine.DrawNode
import app.pdfeditor.engine.NodeKind
import app.pdfeditor.engine.ParsedPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Page analysed up to everything that needs document-wide numbers (body area, oversized units). */
internal class PageDraft(
    val content: PageContent,
    val blocks: List<Block>,
    val fixedNodes: List<DrawNode>,
    val footerTop: Float?,
    val pinned: PinReason?,
)

/** Raw page content: text runs grouped into lines, plus graphic leaves. */
internal class PageContent(
    val pageIndex: Int,
    val mediaBox: PDRectangle,
    val rotation: Int,
    val parsed: ParsedPage,
    val runs: List<DrawNode>,
    val lines: List<TextLine>,
    val graphics: List<DrawNode>,
    val backgrounds: List<DrawNode>,
    val unplaced: List<DrawNode>, // leaves/runs without bounds: kept in fixedNodes so nothing is lost
)

/** Runs sharing one baseline. Non-upright runs each form their own line, sized from their bounds. */
internal class TextLine(
    val runs: List<DrawNode>, // sorted by x
    val baseline: Float,
    val size: Float,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val upright: Boolean,
) {
    val text: String get() = runs.joinToString("") { it.run!!.text }
}

/** Turns one parsed page into flow blocks, given the cross-page header/footer keys. */
internal object PageAnalyzer {

    private const val BACKGROUND_AREA = 0.8f

    fun extract(pageIndex: Int, mediaBox: PDRectangle, rotation: Int, parsed: ParsedPage): PageContent {
        val runs = parsed.leaves.filter { it.kind == NodeKind.TEXT }.flatMap { it.children }.filter { it.run != null }
        val pageArea = mediaBox.width * mediaBox.height
        val nonText = parsed.leaves.filter { it.kind != NodeKind.TEXT }
        val (backgrounds, graphics) = nonText.filter { it.bounds != null }
            .partition { it.bounds!!.width * it.bounds.height > BACKGROUND_AREA * pageArea }
        val unplaced = nonText.filter { it.bounds == null } +
            runs.filter { !it.run!!.isUpright && it.bounds == null } +
            parsed.leaves.filter { it.kind == NodeKind.TEXT && it.children.none { r -> r.run != null } }
        return PageContent(pageIndex, mediaBox, rotation, parsed, runs, buildLines(runs), graphics, backgrounds, unplaced)
    }

    private fun buildLines(runs: List<DrawNode>): List<TextLine> {
        val groups = ArrayList<MutableList<DrawNode>>()
        val upright = runs.filter { it.run!!.isUpright }.sortedWith(compareBy({ -it.run!!.originY }, { it.run!!.originX }))
        for (r in upright) {
            val info = r.run!!
            val group = groups.firstOrNull { g ->
                val ref = g.maxBy { it.run!!.effectiveFontSize }.run!!
                abs(info.originY - ref.originY) <= 0.2f * max(info.effectiveFontSize, ref.effectiveFontSize)
            }
            if (group != null) group.add(r) else groups.add(mutableListOf(r))
        }
        val lines = groups.map { g ->
            val sorted = g.sortedBy { it.run!!.originX }
            val ref = g.maxBy { it.run!!.effectiveFontSize }.run!!
            TextLine(
                runs = sorted,
                baseline = ref.originY,
                size = ref.effectiveFontSize,
                top = lineTop(ref.originY, ref.effectiveFontSize),
                bottom = lineBottom(ref.originY, ref.effectiveFontSize),
                left = g.minOf { min(it.run!!.originX, it.run!!.endX) },
                right = g.maxOf { max(it.run!!.originX, it.run!!.endX) },
                upright = true,
            )
        }
        val rotated = runs.filter { !it.run!!.isUpright && it.bounds != null }.map { r ->
            val b = r.bounds!!
            TextLine(listOf(r), r.run!!.originY, r.run.effectiveFontSize, b.maxY, b.minY, b.minX, b.maxX, upright = false)
        }
        return lines + rotated
    }

    /** Builds the page's body blocks with [headers] set aside as fixed nodes, plus the first three pin reasons. */
    fun draft(c: PageContent, headers: HeaderSet): PageDraft {
        val bodyLines = c.lines.filter { it !in headers.lines }
        val bodyGraphics = c.graphics.filter { it !in headers.graphics }
        val blocks = buildBlocks(c.pageIndex, bodyLines, bodyGraphics)
        val fixed = c.backgrounds + c.unplaced + headers.graphics + headers.lines.flatMap { it.runs }
        val pinned = when {
            c.runs.any { it.run!!.state.renderMode == 3 } -> PinReason.SCANNED_TEXT
            c.parsed.hasTextInForms -> PinReason.TEXT_IN_FORM
            ((c.rotation % 360) + 360) % 360 != 0 || c.runs.any { !it.run!!.isUpright } -> PinReason.ROTATED
            else -> null
        }
        return PageDraft(c, blocks, fixed, HeaderFooter.footerTop(c, headers), pinned)
    }

    private sealed class Element {
        abstract val top: Float
        abstract val bottom: Float
        abstract val left: Float
        abstract val right: Float
        class Line(val line: TextLine) : Element() {
            override val top get() = line.top
            override val bottom get() = line.bottom
            override val left get() = line.left
            override val right get() = line.right
        }
        class Graphic(val node: DrawNode) : Element() {
            override val top get() = node.bounds!!.maxY
            override val bottom get() = node.bounds!!.minY
            override val left get() = node.bounds!!.minX
            override val right get() = node.bounds!!.maxX
        }
    }

    private fun overlaps(aTop: Float, aBottom: Float, bTop: Float, bBottom: Float) = aBottom < bTop && aTop > bBottom

    /** A row is multi-segment when its runs, in x order, leave a horizontal gap wider than 2 × size. */
    private fun isMultiSegment(l: TextLine): Boolean {
        if (!l.upright) return false
        var reach = -Float.MAX_VALUE
        for ((i, r) in l.runs.withIndex()) {
            val info = r.run!!
            if (i > 0 && min(info.originX, info.endX) - reach > 2f * l.size) return true
            reach = max(reach, max(info.originX, info.endX))
        }
        return false
    }

    private fun buildBlocks(pageIndex: Int, lines: List<TextLine>, graphics: List<DrawNode>): List<Block> {
        val lineEls = lines.map { Element.Line(it) }.sortedByDescending { it.top }
        val free = LinkedHashSet<Element>(lineEls + graphics.map { Element.Graphic(it) })

        // Units: maximal consecutive stretches of multi-segment rows, then everything overlapping them.
        val units = ArrayList<MutableList<Element>>()
        var current: MutableList<Element>? = null
        for (e in lineEls) {
            if (isMultiSegment(e.line)) {
                if (current == null) current = mutableListOf<Element>().also { units += it }
                current += e
            } else current = null
        }
        units.forEach { free.removeAll(it.toSet()) }
        var changed = units.isNotEmpty()
        while (changed) {
            changed = false
            for (u in units) {
                val top = u.maxOf { it.top }
                val bottom = u.minOf { it.bottom }
                val hit = free.filter { overlaps(it.top, it.bottom, top, bottom) }
                if (hit.isNotEmpty()) { u += hit; free.removeAll(hit.toSet()); changed = true }
            }
            loop@ for (i in units.indices) for (j in i + 1 until units.size) {
                val a = units[i]; val b = units[j]
                if (overlaps(a.maxOf { it.top }, a.minOf { it.bottom }, b.maxOf { it.top }, b.minOf { it.bottom })) {
                    a += b; units.removeAt(j); changed = true; break@loop
                }
            }
        }

        // Graphics overlapping a free line's vertical range by more than half their height join that line.
        val freeLines = free.filterIsInstance<Element.Line>()
        val attached = HashMap<Element.Line, MutableList<DrawNode>>()
        for (g in free.filterIsInstance<Element.Graphic>()) {
            val h = g.top - g.bottom
            val host = freeLines.maxByOrNull { overlapLen(g, it) }?.takeIf { l ->
                if (h < 0.01f) g.top > l.bottom && g.top < l.top else overlapLen(g, l) > 0.5f * h
            } ?: continue
            attached.getOrPut(host) { mutableListOf() } += g.node
            free.remove(g)
        }

        class Draft(val kind: BlockKind, val top: Float, val bottom: Float, val left: Float, val right: Float,
                    val runs: List<DrawNode>, val leaves: List<DrawNode>)
        val drafts = ArrayList<Draft>()
        for (u in units) {
            drafts += Draft(
                BlockKind.UNIT, u.maxOf { it.top }, u.minOf { it.bottom }, u.minOf { it.left }, u.maxOf { it.right },
                u.filterIsInstance<Element.Line>().sortedByDescending { it.top }.flatMap { it.line.runs },
                u.filterIsInstance<Element.Graphic>().map { it.node },
            )
        }
        for (e in free) when (e) {
            is Element.Line -> drafts += Draft(BlockKind.LINE, e.top, e.bottom, e.left, e.right, e.line.runs, attached[e].orEmpty())
            is Element.Graphic -> drafts += Draft(BlockKind.GRAPHIC, e.top, e.bottom, e.left, e.right, emptyList(), listOf(e.node))
        }
        drafts.sortWith(compareBy({ -it.top }, { it.left }))
        return drafts.mapIndexed { i, d ->
            val gap = if (i == 0) 0f else drafts[i - 1].bottom - d.top
            Block(BlockId.Orig(pageIndex, i), d.kind, pageIndex, d.top, d.bottom, d.left, d.right, gap, d.runs, d.leaves)
        }
    }

    private fun overlapLen(a: Element, b: Element) = min(a.top, b.top) - max(a.bottom, b.bottom)

    /**
     * Completes the page with the document-wide numbers: body area and the OVERSIZED_UNIT pin.
     * [lowestBodyBottom] is the lowest body bottom on any page (null when no page has blocks).
     */
    fun finish(d: PageDraft, medianBodyTop: Float, paragraphGap: Float, lowestBodyBottom: Float?): PageAnalysis {
        val mb = d.content.mediaBox
        val bodyTop = d.blocks.firstOrNull()?.top ?: medianBodyTop
        var bodyLimit = mb.lowerLeftY + (mb.upperRightY - medianBodyTop)
        if (lowestBodyBottom != null) bodyLimit = min(lowestBodyBottom, bodyLimit)
        d.footerTop?.let { bodyLimit = max(bodyLimit, it + paragraphGap) }
        val pinned = d.pinned ?: PinReason.OVERSIZED_UNIT.takeIf {
            d.blocks.any { b -> b.kind == BlockKind.UNIT && b.top - b.bottom > 0.9f * (bodyTop - bodyLimit) }
        }
        return PageAnalysis(d.content.pageIndex, mb, d.content.parsed, bodyTop, bodyLimit, d.blocks, d.fixedNodes, pinned)
    }
}

internal fun median(values: List<Float>): Float {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
}
