package app.pdfeditor.testing

import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFixturesTest {
    @Test fun letterWrapsAndPaginates() {
        val bytes = PdfFixtures.letter(List(40) { "Paragraph $it " + "word ".repeat(60) })
        PDDocument.load(bytes).use { assertTrue(it.numberOfPages >= 2) }
        assertTrue(extractText(bytes, page = 1).contains("Paragraph 0"))
    }

    @Test fun sampleLetterLoads() {
        assertTrue(extractText(PdfFixtures.sampleLetter()).contains("Octobr"))
    }

    @Test fun ttfLoadsOnJvm() {
        PDDocument().use { assertEquals("LiberationSerif", PdfFixtures.font(it).name.substringAfter('+')) }
    }
}
