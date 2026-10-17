package app.pdfeditor.flow

import app.pdfeditor.engine.ContentStreamEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument

/** Analyses every page of a document into flow blocks, deciding the cross-page numbers. */
object DocumentAnalyzer {

    private const val DEFAULT_PARAGRAPH_GAP = 8f
    private const val DEFAULT_TOP_MARGIN = 72f

    fun analyze(doc: PDDocument): DocumentAnalysis {
        val contents = doc.pages.mapIndexed { i, page ->
            PageAnalyzer.extract(i, page.mediaBox, page.rotation, ContentStreamEngine.parse(page))
        }
        val keys = headerKeys(contents)
        val drafts = contents.map { PageAnalyzer.analyze(it, keys, singlePage = contents.size == 1) }

        val tops = drafts.mapNotNull { it.blocks.firstOrNull()?.top }
        val medianBodyTop = if (tops.isNotEmpty()) median(tops)
        else (contents.firstOrNull()?.mediaBox?.upperRightY ?: 792f) - DEFAULT_TOP_MARGIN
        val paragraphGap = paragraphGap(drafts)
        val lowestBottom = drafts.flatMap { it.blocks }.minOfOrNull { it.bottom }

        val pages = drafts.map { PageAnalyzer.finish(it, medianBodyTop, paragraphGap, lowestBottom) }
        return DocumentAnalysis(pages, paragraphGap, medianBodyTop)
    }

    /** Candidates that recur (same text or bounds, same y) on at least half the pages; none for 1-page documents. */
    private fun headerKeys(contents: List<PageContent>): List<HeaderKey> {
        if (contents.size < 2) return emptyList()
        val perPage = contents.map { PageAnalyzer.candidates(it) }
        return perPage.flatten().filter { key ->
            2 * perPage.count { page -> page.any { it.matches(key) } } >= contents.size
        }
    }

    /** Median gapBefore of blocks whose (positive) gap exceeds 1.3 × the median line gap. */
    private fun paragraphGap(drafts: List<PageDraft>): Float {
        val gapped = drafts.flatMap { it.blocks.drop(1) }
        val lineGaps = gapped.filter { it.kind == BlockKind.LINE }.map { it.gapBefore }
        if (lineGaps.isEmpty()) return DEFAULT_PARAGRAPH_GAP
        val threshold = 1.3f * median(lineGaps)
        val big = gapped.map { it.gapBefore }.filter { it > threshold && it > 0f }
        return if (big.isEmpty()) DEFAULT_PARAGRAPH_GAP else median(big)
    }
}
