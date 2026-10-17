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
}
