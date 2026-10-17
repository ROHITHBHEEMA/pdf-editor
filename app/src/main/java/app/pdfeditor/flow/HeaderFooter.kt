package app.pdfeditor.flow

import app.pdfeditor.engine.Bounds
import app.pdfeditor.engine.DrawNode
import kotlin.math.abs

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

/** Header/footer lines and graphics of one page. */
internal class HeaderSet(val lines: Set<TextLine>, val graphics: Set<DrawNode>) {
    companion object { val NONE = HeaderSet(emptySet(), emptySet()) }
}

/** Decides which band nodes are headers/footers. */
internal object HeaderFooter {

    private const val BAND = 0.12f

    /** 1 = centred in the top band, -1 = bottom band, 0 = body. */
    fun band(c: PageContent, top: Float, bottom: Float): Int {
        val centre = (top + bottom) / 2f
        val h = c.mediaBox.height
        return when {
            centre > c.mediaBox.upperRightY - BAND * h -> 1
            centre < c.mediaBox.lowerLeftY + BAND * h -> -1
            else -> 0
        }
    }

    private fun normalise(text: String) = text.map { if (it.isDigit()) '#' else it }.joinToString("").trim()
    private fun lineKey(l: TextLine) = HeaderKey(normalise(l.text), null, l.baseline)
    private fun graphicKey(g: DrawNode) = HeaderKey(null, g.bounds, (g.bounds!!.maxY + g.bounds.minY) / 2f)

    private fun bandLines(c: PageContent) = c.lines.filter { it.upright && band(c, it.top, it.bottom) != 0 }
    private fun bandGraphics(c: PageContent) = c.graphics.filter { band(c, it.bounds!!.maxY, it.bounds.minY) != 0 }

    /** Keys recurring (same text or bounds ±2, same y ±2) on at least half the pages; none for 1-page documents. */
    fun repeatedKeys(contents: List<PageContent>): List<HeaderKey> {
        if (contents.size < 2) return emptyList()
        val perPage = contents.map { c -> bandLines(c).map(::lineKey) + bandGraphics(c).map(::graphicKey) }
        return perPage.flatten().filter { key -> 2 * perPage.count { page -> page.any { it.matches(key) } } >= contents.size }
    }

    /**
     * Candidates by the repeat rule ([keys], multi-page) or, for a single page, by being at least
     * 3 × the median line advance away from the body (lines/graphics centred outside the bands).
     */
    fun candidates(c: PageContent, keys: List<HeaderKey>, singlePage: Boolean): HeaderSet {
        val body = c.lines.filter { band(c, it.top, it.bottom) == 0 }.map { it.top to it.bottom } +
            c.graphics.filter { band(c, it.bounds!!.maxY, it.bounds.minY) == 0 }.map { it.bounds!!.maxY to it.bounds.minY }
        val advance = medianAdvance(c.lines.filter { band(c, it.top, it.bottom) == 0 })
        fun ok(key: HeaderKey, b: Int, top: Float, bottom: Float) =
            if (!singlePage) keys.any { it.matches(key) }
            else advance != null && (innerGap(b, top, bottom, body) ?: -1f) >= 3f * advance
        return HeaderSet(
            bandLines(c).filter { ok(lineKey(it), band(c, it.top, it.bottom), it.top, it.bottom) }.toSet(),
            bandGraphics(c).filter { val b = it.bounds!!; ok(graphicKey(it), band(c, b.maxY, b.minY), b.maxY, b.minY) }.toSet(),
        )
    }

    /** Keeps candidates whose gap to the nearest body block on their inner side exceeds [threshold]. */
    fun confirm(c: PageContent, cand: HeaderSet, bodyBlocks: List<Block>, threshold: Float): HeaderSet {
        val body = bodyBlocks.map { it.top to it.bottom }
        fun ok(top: Float, bottom: Float) = (innerGap(band(c, top, bottom), top, bottom, body) ?: Float.MAX_VALUE) > threshold
        return HeaderSet(cand.lines.filter { ok(it.top, it.bottom) }.toSet(),
            cand.graphics.filter { ok(it.bounds!!.maxY, it.bounds.minY) }.toSet())
    }

    /** Highest top among bottom-band headers/footers, or null. */
    fun footerTop(c: PageContent, h: HeaderSet): Float? =
        (h.lines.map { it.top to it.bottom } + h.graphics.map { it.bounds!!.maxY to it.bounds.minY })
            .filter { band(c, it.first, it.second) < 0 }.maxOfOrNull { it.first }

    private fun medianAdvance(lines: List<TextLine>): Float? {
        val baselines = lines.filter { it.upright }.map { it.baseline }.sortedDescending()
        val advances = baselines.zipWithNext { a, b -> a - b }.filter { it > 0f }
        return if (advances.isEmpty()) null else median(advances)
    }

    /** Edge-to-edge gap from a band node to the nearest [body] extent (top to bottom) on its inner side; null if none. */
    private fun innerGap(band: Int, top: Float, bottom: Float, body: List<Pair<Float, Float>>): Float? {
        val centre = (top + bottom) / 2f
        return if (band > 0) body.filter { (it.first + it.second) / 2f < centre }.maxOfOrNull { it.first }?.let { bottom - it }
        else body.filter { (it.first + it.second) / 2f > centre }.minOfOrNull { it.second }?.let { it - top }
    }
}
