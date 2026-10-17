package app.pdfeditor.flow

import app.pdfeditor.testing.PdfFixtures
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphDetectorTest {

    private fun detect(bytes: ByteArray): List<Paragraph> =
        PDDocument.load(bytes).use { doc -> DocumentAnalyzer.analyze(doc).pages.flatMap { ParagraphDetector.detect(it) } }

    @Test fun sampleLetterLongParagraph() {
        val ps = detect(PdfFixtures.sampleLetter())
        val p = ps.single { it.text().startsWith("The construction team") }
        assertEquals(6, p.lineBlocks.size); assertEquals(Alignment.LEFT, p.alignment)
        assertTrue(p.text().endsWith("before the official opening."))
        assertEquals(16f, p.lineAdvance, 0.1f); assertTrue(p.canGrow); assertFalse(p.nonLatin)
    }

    @Test fun typoLineIsOneLineParagraph() {
        assertEquals(1, detect(PdfFixtures.sampleLetter()).single { it.text() == "Date: 17 Octobr 2026" }.lineBlocks.size)
    }

    @Test fun justifiedDetected() {
        assertEquals(Alignment.JUSTIFIED, detect(PdfFixtures.letter(listOf("word ".repeat(80)), align = "justify")).first().alignment)
    }

    @Test fun boldWordStaysInParagraphAsSeparateSpan() {
        val ps = detect(PdfFixtures.boldWordLine())
        assertEquals(1, ps.size)
        assertEquals(3, ps[0].spans.size)
        assertEquals("This is important for everyone.", ps[0].text())
    }

    @Test fun unitParagraphsCannotGrow() {
        val ps = detect(PdfFixtures.twoColumn(listOf("a"), listOf("b")))
        assertTrue(ps.isNotEmpty()); assertTrue(ps.none { it.canGrow })
        assertEquals(ps.size, ps.map { it.id }.distinct().size)
    }

    @Test fun latinCheck() {
        assertFalse(isLatinText("नमस्ते")); assertTrue(isLatinText("Café – “ok”"))
    }

    private val top = 700f
    private fun rows(n: Int, x: (Int) -> Float = { 72f }, text: (Int) -> String = { "line number $it of text" }) =
        List(n) { Triple(text(it), x(it), top - 16f * it) }

    @Test fun idsUniqueOnSampleLetterAndTwoColumn() {
        val a = detect(PdfFixtures.sampleLetter())
        assertEquals(a.size, a.map { it.id }.distinct().size)
        assertEquals(1, a.single { it.text() == "Date: 17 Octobr 2026" }.id.firstBlock)
        val b = detect(PdfFixtures.twoColumn(listOf("a", "b"), listOf("c", "d")))
        assertEquals(b.size, b.map { it.id }.distinct().size)
    }

    @Test fun runNodesComposeParagraph() {
        val p = detect(PdfFixtures.sampleLetter()).single { it.text().startsWith("The construction team") }
        assertEquals(6, p.runNodes.size)
        assertEquals(p.text(), p.runNodes.joinToString(" ") { it.run!!.text })
    }

    @Test fun tightLineFarToTheRightDoesNotJoin() {
        val ps = detect(PdfFixtures.positioned(listOf(Triple("first line here", 72f, top), Triple("second line here", 172f, top - 16f))))
        assertEquals(2, ps.size)
    }

    @Test fun centerAndRightDetectedWithZeroIndent() {
        val items = listOf("alpha beta gamma", "alpha beta gamma delta", "alpha beta").mapIndexed { i, t -> Triple(t, 0f, top - 16f * i) }
        val c = detect(PdfFixtures.positioned(items, "center", 300f)).single()
        assertEquals(Alignment.CENTER, c.alignment); assertEquals(0f, c.firstLineIndent, 0f)
        val r = detect(PdfFixtures.positioned(items, "right", 500f)).single()
        assertEquals(Alignment.RIGHT, r.alignment); assertEquals(0f, r.firstLineIndent, 0f)
    }

    @Test fun firstLineIndentMeasured() {
        val p = detect(PdfFixtures.positioned(rows(4, { if (it == 0) 92f else 72f }))).single()
        assertEquals(Alignment.LEFT, p.alignment); assertEquals(20f, p.firstLineIndent, 0.5f)
    }

    @Test fun noDoubleSpaceAcrossRuns() {
        assertEquals("a b", detect(PdfFixtures.doubleSpaceLine()).single().text())
    }

    @Test fun pinnedPageCannotGrow() {
        val ps = detect(PdfFixtures.rotated())
        assertTrue(ps.isNotEmpty()); assertTrue(ps.none { it.canGrow })
    }
}
