package app.pdfeditor.flow

import app.pdfeditor.testing.PdfFixtures
import app.pdfeditor.testing.TestFontProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontResolverTest {

    private fun <T> withSample(block: (PDDocument, SpanStyle) -> T): T =
        PDDocument.load(PdfFixtures.sampleLetter()).use { doc ->
            val p = DocumentAnalyzer.analyze(doc).pages.flatMap { ParagraphDetector.detect(it) }
                .single { it.text() == "Date: 17 Octobr 2026" }
            block(doc, p.spans.first().style)
        }

    private fun plan(text: String): FontPlanResult =
        withSample { doc, style -> FontResolver.plan(doc, listOf(Span(text, style)), TestFontProvider) }

    @Test fun typoFixReusesEmbeddedFont() {
        val r = plan("Date: 17 October 2026") as FontPlanResult.Ok
        assertNull(r.plan.warning)
        withSample { doc, style ->
            val text = "Date: 17 October 2026"
            val ok = FontResolver.plan(doc, listOf(Span(text, style)), TestFontProvider) as FontPlanResult.Ok
            val bytes = ok.plan.encode(style, text)
            val font = ok.plan.fontFor(style)
            val input = java.io.ByteArrayInputStream(bytes)
            val sb = StringBuilder()
            while (input.available() > 0) sb.append(font.toUnicode(font.readCode(input)))
            assertEquals(text, sb.toString())
        }
    }

    @Test fun missingGlyphSubstitutesWholeParagraph() {
        val r = plan("Zebra Quiz") as FontPlanResult.Ok
        assertEquals("Z Q z", r.plan.warning!!.missingChars.toList().joinToString(" "))
        assertEquals(listOf("Gelasio"), r.plan.warning!!.substituteNames)
    }

    @Test fun emojiUnsupported() {
        assertEquals("😀", (plan("hi 😀") as FontPlanResult.Unsupported).chars)
    }

    @Test fun devanagariUnsupported() {
        assertTrue(plan("नमस्ते") is FontPlanResult.Unsupported)
    }

    @Test fun encodeMapsTabToSpace() {
        withSample { doc, style ->
            val ok = FontResolver.plan(doc, listOf(Span("a b", style)), TestFontProvider) as FontPlanResult.Ok
            org.junit.Assert.assertArrayEquals(ok.plan.encode(style, "a b"), ok.plan.encode(style, "a\tb"))
        }
    }

    @Test fun tabBecomesSpace() {
        assertTrue(plan("a\tb") is FontPlanResult.Ok)
    }

    @Test fun newlineIsALineBreakNotAGlyph() {
        val r = plan("Date: 17\nOctober 2026") as FontPlanResult.Ok
        assertNull(r.plan.warning)
    }
}
