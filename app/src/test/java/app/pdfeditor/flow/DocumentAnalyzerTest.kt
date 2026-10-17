package app.pdfeditor.flow

import app.pdfeditor.engine.NodeKind
import app.pdfeditor.testing.PdfFixtures
import app.pdfeditor.testing.miniPdf
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAnalyzerTest {

    private fun analyze(bytes: ByteArray): DocumentAnalysis =
        PDDocument.load(bytes).use { DocumentAnalyzer.analyze(it) }

    @Test fun sampleLetterHasOneLinePerTextLine() {
        val p = analyze(PdfFixtures.sampleLetter()).pages[0]
        assertEquals(17, p.blocks.count { it.kind == BlockKind.LINE }); assertNull(p.pinned)
    }

    @Test fun perWordRunsMergeIntoOneLine() {
        assertEquals(1, analyze(PdfFixtures.perWordRuns("The quick brown fox")).pages[0].blocks.size)
    }

    @Test fun twoColumnsBecomeOneUnit() {
        val p = analyze(PdfFixtures.twoColumn(List(5) { "left $it" }, List(5) { "right $it" })).pages[0]
        assertEquals(listOf(BlockKind.UNIT), p.blocks.map { it.kind })
    }

    @Test fun repeatedHeaderFooterAreFixed() {
        val a = analyze(PdfFixtures.withHeaderFooter(3, List(10) { "Body $it" }))
        a.pages.forEach { pg -> assertTrue(pg.fixedNodes.any { it.run?.text == "Riverside Trust" }) }
        a.pages.forEach { pg -> assertTrue(pg.blocks.none { b -> b.runNodes.any { it.run!!.text.startsWith("Page ") } }) }
        a.pages.forEachIndexed { i, pg ->
            assertEquals(10, pg.blocks.count { it.kind == BlockKind.LINE })
            assertTrue(pg.fixedNodes.any { it.run?.text == "Page ${i + 1}" })
            assertTrue(pg.fixedNodes.none { it.run?.text == "Body 0" })
        }
    }

    @Test fun rotatedPageIsPinned() { assertEquals(PinReason.ROTATED, analyze(PdfFixtures.rotated()).pages[0].pinned) }

    @Test fun gapsReproduceOriginalTops() { // first block at bodyTop, each next top == prev.bottom − gapBefore
        val p = analyze(PdfFixtures.sampleLetter()).pages[0]
        assertEquals(p.blocks.first().top, p.bodyTop, 0.01f)
        assertEquals(0f, p.blocks.first().gapBefore, 0f)
        p.blocks.zipWithNext { a, b -> assertEquals(b.top, a.bottom - b.gapBefore, 0.01f) }
    }

    // --- extra coverage beyond the brief's required tests ---

    @Test fun sampleLetterParagraphGap() {
        assertEquals(13f, analyze(PdfFixtures.sampleLetter()).paragraphGap, 0.01f)
    }

    @Test fun everyRunAndLeafIsPlacedExactlyOnce() {
        val sideText = miniPdf("BT /F1 10 Tf 72 700 Td (Upright) Tj ET\nBT /F1 10 Tf 0 1 -1 0 300 400 Tm (side) Tj ET\n")
        val docs = listOf(PdfFixtures.sampleLetter(), PdfFixtures.withHeaderFooter(3, List(10) { "Body $it" }),
            PdfFixtures.twoColumn(List(5) { "left $it" }, List(5) { "right $it" }), sideText)
        for (bytes in docs) for (p in analyze(bytes).pages) {
            val expected = p.parsed.leaves.flatMap { if (it.kind == NodeKind.TEXT) it.children else listOf(it) }
            val placed = p.blocks.flatMap { it.runNodes + it.leafNodes } + p.fixedNodes
            assertEquals(expected.size, placed.size)
            assertEquals(expected.map { System.identityHashCode(it) }.toSet(), placed.map { System.identityHashCode(it) }.toSet())
        }
        assertEquals(PinReason.ROTATED, analyze(sideText).pages[0].pinned)
    }

    @Test fun paragraphGapIsTheBetweenParagraphGap() {
        // 11pt lines: 16 advance → 5pt gap within paragraphs, 24 advance → 13pt gap between them.
        val pdf = PdfFixtures.letter(List(4) { "Paragraph $it " + "word ".repeat(60) })
        assertEquals(13f, analyze(pdf).paragraphGap, 0.01f)
    }

    @Test fun invisibleTextPinsAsScanned() {
        val pdf = miniPdf("BT /F1 10 Tf 3 Tr 72 700 Td (ocr) Tj ET\n")
        assertEquals(PinReason.SCANNED_TEXT, analyze(pdf).pages[0].pinned)
    }

    @Test fun textInFormPins() {
        val pdf = miniPdf("q 1 0 0 1 72 600 cm /Fm1 Do Q\n", mapOf("Fm1" to "BT /F1 10 Tf 0 0 Td (x) Tj ET"))
        assertEquals(PinReason.TEXT_IN_FORM, analyze(pdf).pages[0].pinned)
    }

    @Test fun fullPageBackgroundIsFixedAndUnderlineJoinsItsLine() {
        val pdf = miniPdf(
            "0 0 612 792 re f\n" +
                "BT /F1 10 Tf 72 700 Td (Hello) Tj ET\n" +
                "72 699 30 3 re f\n" +           // underline inside the line's vertical range
                "72 500 200 50 re f\n",          // free-standing box
        )
        val p = analyze(pdf).pages[0]
        assertTrue(p.fixedNodes.any { it.kind == NodeKind.PATH && it.bounds!!.width == 612f })
        assertEquals(listOf(BlockKind.LINE, BlockKind.GRAPHIC), p.blocks.map { it.kind })
        assertEquals(1, p.blocks[0].leafNodes.size)
        assertEquals(550f, p.blocks[1].top, 0.01f); assertEquals(500f, p.blocks[1].bottom, 0.01f)
        assertEquals(lineTop(700f, 10f), p.blocks[0].top, 0.01f)
    }

    @Test fun singlePageHeaderNeedsSeparationFromBody() {
        // Body: lines every 14pt from 650 down; header at 770 (top 12% band = above 696.96) is far from the body.
        val body = (0 until 10).joinToString("") { "BT /F1 10 Tf 72 ${650 - 14 * it} Td (Body line $it) Tj ET\n" }
        val p = analyze(miniPdf("BT /F1 10 Tf 72 770 Td (Letterhead) Tj ET\n$body")).pages[0]
        assertTrue(p.fixedNodes.any { it.run?.text == "Letterhead" })
        assertEquals(10, p.blocks.size)
        // Same header 20pt above the body (< 3 × 14) stays in the body.
        val q = analyze(miniPdf("BT /F1 10 Tf 72 704 Td (Heading) Tj ET\n" +
            (0 until 10).joinToString("") { "BT /F1 10 Tf 72 ${684 - 14 * it} Td (Body line $it) Tj ET\n" })).pages[0]
        assertEquals(11, q.blocks.size)
    }

    @Test fun bodyLimitRaisedAboveFooter() {
        val a = analyze(PdfFixtures.withHeaderFooter(2, List(3) { "Body $it" }))
        a.pages.forEach { pg ->
            // footer "Page n" baseline 36 → top 36 + 8.25
            assertTrue(pg.bodyLimit >= 36f + 0.75f * 11f + a.paragraphGap - 0.01f)
            assertTrue(pg.bodyLimit <= pg.blocks.minOf { it.bottom } + 0.01f)
        }
    }
}
