package app.pdfeditor.flow

import app.pdfeditor.engine.ContentStreamEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlin.math.max

/** Analyses every page of a document into flow blocks, deciding the cross-page numbers. */
object DocumentAnalyzer {

    private const val DEFAULT_PARAGRAPH_GAP = 8f
    private const val DEFAULT_TOP_MARGIN = 72f

    fun analyze(doc: PDDocument): DocumentAnalysis {
        val contents = doc.pages.mapIndexed { i, page ->
            PageAnalyzer.extract(i, page.mediaBox, page.rotation, ContentStreamEngine.parse(page))
        }
        // Candidate headers → draft blocks → gaps → confirm headers (separated from the body) → final blocks.
        val keys = HeaderFooter.repeatedKeys(contents)
        val candidates = contents.map { HeaderFooter.candidates(it, keys, singlePage = contents.size == 1) }
        val firstDrafts = contents.zip(candidates) { c, h -> PageAnalyzer.draft(c, h) }
        val threshold = separationThreshold(gaps(firstDrafts))
        val drafts = contents.indices.map { i ->
            val confirmed = HeaderFooter.confirm(contents[i], candidates[i], firstDrafts[i].blocks, threshold)
            if (confirmed.lines.size == candidates[i].lines.size && confirmed.graphics.size == candidates[i].graphics.size) firstDrafts[i]
            else PageAnalyzer.draft(contents[i], confirmed)
        }

        val tops = drafts.mapNotNull { it.blocks.firstOrNull()?.top }
        val medianBodyTop = if (tops.isNotEmpty()) median(tops)
        else (contents.firstOrNull()?.mediaBox?.upperRightY ?: 792f) - DEFAULT_TOP_MARGIN
        val paragraphGap = gaps(drafts).paragraphGap
        val lowestBottom = drafts.flatMap { it.blocks }.minOfOrNull { it.bottom }

        val pages = drafts.map { PageAnalyzer.finish(it, medianBodyTop, paragraphGap, lowestBottom) }
        return DocumentAnalysis(pages, paragraphGap, medianBodyTop)
    }

    private class Gaps(val lineGap: Float?, val paragraphGap: Float)

    /**
     * lineGap = median of positive gaps ≤ 1.3 × the smallest positive gapBefore;
     * paragraphGap = median of gaps > 1.3 × lineGap (default 8).
     */
    private fun gaps(drafts: List<PageDraft>): Gaps {
        val all = drafts.flatMap { it.blocks.drop(1) }.map { it.gapBefore }.filter { it > 0f }
        val smallest = all.minOrNull() ?: return Gaps(null, DEFAULT_PARAGRAPH_GAP)
        val lineGap = median(all.filter { it <= 1.3f * smallest })
        val big = all.filter { it > 1.3f * lineGap }
        return Gaps(lineGap, if (big.isEmpty()) DEFAULT_PARAGRAPH_GAP else median(big))
    }

    /**
     * Gap a header/footer must keep from the body. The ruling is "> paragraphGap"; it is raised to
     * 1.3 × lineGap so a body line spaced like its neighbours (one-line paragraphs, where
     * paragraphGap falls back to its default) is never taken for a header.
     */
    private fun separationThreshold(g: Gaps) = max(g.paragraphGap, 1.3f * (g.lineGap ?: 0f))
}
