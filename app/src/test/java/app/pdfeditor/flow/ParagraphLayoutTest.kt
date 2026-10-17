package app.pdfeditor.flow

import app.pdfeditor.testing.PdfFixtures
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphLayoutTest {
    private val doc = PDDocument()
    private val font: PDFont = PdfFixtures.font(doc)
    private val bold: PDFont = PdfFixtures.font(doc, "LiberationSerif-Bold")
    private val plain = SpanStyle("F1", 11f, 1f, listOf("0", "g"), 0f, 0f)
    private val boldStyle = plain.copy(fontResourceName = "F2")
    private val plan = FontPlan(mapOf(plain to font, boldStyle to bold), null)
    private val left = 50f
    private val right = 250f

    @After fun close() = doc.close()

    private fun tw(t: String) = ParagraphLayout.textWidth(t, plain, font)

    private fun lay(
        text: String, width: Float = right - left, advance: Float = 16f, align: Alignment = Alignment.LEFT, indent: Float = 0f,
    ) = ParagraphLayout.layout(listOf(Span(text, plain)), plan, left, left + width, indent, 700f, advance, align)

    private fun LaidLine.text() = fragments.joinToString("") { it.text }

    @Test fun wrapsGreedily() {
        val lines = lay("aaa bbb ccc", width = tw("aaa bbb") + 1f)
        assertEquals(listOf("aaa bbb", "ccc"), lines.map { it.text() })
    }

    @Test fun baselinesStepByAdvance() { assertEquals(listOf(700f, 684f), lay("x\ny", advance = 16f).map { it.baseline }) }
    @Test fun hardBreakKeepsEmptyLine() { assertEquals(3, lay("a\n\nb").size) }
    @Test fun longWordBreaks() {
        val ls = lay("x".repeat(500))
        assertTrue(ls.size > 1)
        assertEquals("x".repeat(500), ls.joinToString("") { it.text() })
        ls.forEach { assertTrue(it.fragments.sumOf { f -> f.width.toDouble() } <= 200.01) }
    }

    @Test fun justifyFillsWidthExceptLast() {
        val ls = lay("word ".repeat(40).trim(), align = Alignment.JUSTIFIED)
        assertTrue(ls.size > 1)
        ls.dropLast(1).forEach { l ->
            val f = l.fragments.last()
            assertEquals(right, f.x + f.width + f.spaceAdjust * f.text.count { it == ' ' }, 0.5f)
        }
        assertEquals(0f, ls.last().fragments.sumOf { it.spaceAdjust.toDouble() }.toFloat(), 0f)
    }

    @Test fun centerAndRight() {
        val t = tw("hello")
        assertEquals(left + (200f - t) / 2f, lay("hello", align = Alignment.CENTER).single().fragments.single().x, 0.01f)
        assertEquals(right - t, lay("hello", align = Alignment.RIGHT).single().fragments.single().x, 0.01f)
    }

    @Test fun stylesSplitIntoFragments() {
        val spans = listOf(Span("a ", plain), Span("b", boldStyle), Span(" c", plain))
        val l = ParagraphLayout.layout(spans, plan, left, right, 0f, 700f, 16f, Alignment.LEFT).single()
        assertEquals(listOf("a ", "b", " c"), l.fragments.map { it.text })
        assertEquals(listOf(plain, boldStyle, plain), l.fragments.map { it.style })
        assertEquals(left + tw("a ") , l.fragments[1].x, 0.01f)
    }

    @Test fun firstLineIndent() {
        val ls = lay("aaa bbb ccc", width = tw("aaa bbb") + 1f + 10f, indent = 10f)
        assertEquals(2, ls.size)
        assertEquals(left + 10f, ls[0].fragments.first().x, 0.01f)
        assertEquals(left, ls[1].fragments.first().x, 0.01f)
    }

    @Test fun trailingAndLeadingSpacesDropped() {
        val ls = lay("aaa bbb ccc", width = tw("aaa bbb") + 1f)
        assertEquals("aaa bbb", ls[0].text()); assertEquals("ccc", ls[1].text())
    }

    @Test fun emptyTextGivesNoLines() { assertTrue(lay("").isEmpty()) }

    @Test fun fontPlanThrowsForUnknownStyle() {
        assertThrows(IllegalArgumentException::class.java) { plan.fontFor(plain.copy(fontResourceName = "Zz")) }
    }
}
