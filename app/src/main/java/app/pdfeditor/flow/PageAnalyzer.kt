package app.pdfeditor.flow

import app.pdfeditor.engine.Bounds
import app.pdfeditor.engine.DrawNode
import app.pdfeditor.engine.NodeKind
import app.pdfeditor.engine.ParsedPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A header/footer candidate: a text line (normalised [text], digits → '#', at baseline [y]) or a graphic
 * ([bounds], at its vertical centre [y]) centred in the top or bottom 12% of the page.
 */
internal class HeaderKey(val text: String?, val bounds: Bounds?, val y: Float) {
    fun matches(o: HeaderKey): Boolean {
        if (abs(y - o.y) > TOL) return false
        if (text != null) return text == o.text
        val a = bounds ?: return false
        val b = o.bounds ?: return false
        return abs(a.minX - b.minX) <= TOL && abs(a.maxX - b.maxX) <= TOL &&
            abs(a.minY - b.minY) <= TOL && abs(a.maxY - b.maxY) <= TOL
    }

    private companion object { const val TOL = 2f }
}

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

    private const val BAND = 0.12f
    private const val BACKGROUND_AREA = 0.8f

    fun extract(pageIndex: Int, mediaBox: PDRectangle, rotation: Int, parsed: ParsedPage): PageContent {
        val runs = parsed.leaves.filter { it.kind == NodeKind.TEXT }.flatMap { it.children }.filter { it.run != null }
        val pageArea = mediaBox.width * mediaBox.height
        val (backgrounds, graphics) = parsed.leaves
            .filter { it.kind != NodeKind.TEXT && it.bounds != null }
            .partition { it.bounds!!.width * it.bounds.height > BACKGROUND_AREA * pageArea }
        return PageContent(pageIndex, mediaBox, rotation, parsed, runs, buildLines(runs), graphics, backgrounds)
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

    private fun centreY(top: Float, bottom: Float) = (top + bottom) / 2f

    private fun inBand(c: PageContent, centre: Float): Int {
        val h = c.mediaBox.height
        return when {
            centre > c.mediaBox.upperRightY - BAND * h -> 1
            centre < c.mediaBox.lowerLeftY + BAND * h -> -1
            else -> 0
        }
    }

    private fun normalise(text: String) = text.map { if (it.isDigit()) '#' else it }.joinToString("").trim()

    private fun lineKey(l: TextLine) = HeaderKey(normalise(l.text), null, l.baseline)
    private fun graphicKey(g: DrawNode) = HeaderKey(null, g.bounds, centreY(g.bounds!!.maxY, g.bounds.minY))

    /** Header/footer candidates on this page (upright lines and graphics centred in a band). */
    fun candidates(c: PageContent): List<HeaderKey> =
        c.lines.filter { it.upright && inBand(c, centreY(it.top, it.bottom)) != 0 }.map(::lineKey) +
            c.graphics.filter { inBand(c, centreY(it.bounds!!.maxY, it.bounds.minY)) != 0 }.map(::graphicKey)

    /**
     * [headerKeys] are the keys repeated on at least half of a multi-page document's pages; for a
     * single-page document ([singlePage]) a band node is a header/footer when it sits at least
     * 3 × the median line advance away from the body instead.
     */
    fun analyze(c: PageContent, headerKeys: List<HeaderKey>, singlePage: Boolean): PageDraft {
        val headerLines = HashSet<TextLine>()
        val headerGraphics = HashSet<DrawNode>()
        var footerTop: Float? = null
        val bodyLinesForSep = c.lines.filter { inBand(c, centreY(it.top, it.bottom)) == 0 }
        val bodyGraphicsForSep = c.graphics.filter { inBand(c, centreY(it.bounds!!.maxY, it.bounds.minY)) == 0 }
        val advance = medianAdvance(bodyLinesForSep)
        val bodyExtents = bodyLinesForSep.map { it.top to it.bottom } +
            bodyGraphicsForSep.map { it.bounds!!.maxY to it.bounds.minY }

        fun isHeader(key: HeaderKey, band: Int, top: Float, bottom: Float): Boolean =
            if (!singlePage) headerKeys.any { it.matches(key) }
            else separated(band, top, bottom, bodyExtents, advance)

        for (l in c.lines) {
            if (!l.upright) continue
            val band = inBand(c, centreY(l.top, l.bottom))
            if (band != 0 && isHeader(lineKey(l), band, l.top, l.bottom)) {
                headerLines += l
                if (band < 0) footerTop = max(footerTop ?: l.top, l.top)
            }
        }
        for (g in c.graphics) {
            val b = g.bounds!!
            val band = inBand(c, centreY(b.maxY, b.minY))
            if (band != 0 && isHeader(graphicKey(g), band, b.maxY, b.minY)) {
                headerGraphics += g
                if (band < 0) footerTop = max(footerTop ?: b.maxY, b.maxY)
            }
        }

        val bodyLines = c.lines.filter { it !in headerLines }
        val bodyGraphics = c.graphics.filter { it !in headerGraphics }
        val blocks = buildBlocks(c.pageIndex, bodyLines, bodyGraphics)
        val fixed = c.backgrounds + headerGraphics + headerLines.flatMap { it.runs }

        val pinned = when {
            c.runs.any { it.run!!.state.renderMode == 3 } -> PinReason.SCANNED_TEXT
            c.parsed.hasTextInForms -> PinReason.TEXT_IN_FORM
            ((c.rotation % 360) + 360) % 360 != 0 || bodyLines.any { !it.upright } -> PinReason.ROTATED
            else -> null
        }
        return PageDraft(c, blocks, fixed, footerTop, pinned)
    }

    private fun medianAdvance(lines: List<TextLine>): Float? {
        val baselines = lines.filter { it.upright }.map { it.baseline }.sortedDescending()
        val advances = baselines.zipWithNext { a, b -> a - b }.filter { it > 0f }
        return if (advances.isEmpty()) null else median(advances)
    }

    /** Edge-to-edge distance from a band node to the nearest body element on its inner side ≥ 3 × advance. */
    private fun separated(band: Int, top: Float, bottom: Float, body: List<Pair<Float, Float>>, advance: Float?): Boolean {
        if (advance == null) return false
        val centre = centreY(top, bottom)
        val gap = if (band > 0) {
            val below = body.filter { centreY(it.first, it.second) < centre }
            if (below.isEmpty()) return false
            bottom - below.maxOf { it.first }
        } else {
            val above = body.filter { centreY(it.first, it.second) > centre }
            if (above.isEmpty()) return false
            above.minOf { it.second } - top
        }
        return gap >= 3f * advance
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
