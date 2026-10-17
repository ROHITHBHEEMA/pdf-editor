package app.pdfeditor.flow

import app.pdfeditor.testing.PdfFixtures
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObservedEncodingsTest {
    @Test fun sampleLetterGeorgiaCodes() {
        PDDocument.load(PdfFixtures.sampleLetter()).use { doc ->
            val font = DocumentAnalyzer.analyze(doc).pages.flatMap { ParagraphDetector.detect(it) }
                .single { it.text() == "Date: 17 Octobr 2026" }.spans.first().style.font!!
            val obs = ObservedEncodings.build(doc)
            assertNotNull(obs.codesFor(font, 'D'.code))
            assertNull(obs.codesFor(font, 'Z'.code))
        }
    }
}
