package app.pdfeditor.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleMapperTest {
    private fun style(name: String) = SpanStyle(name, 11f, 1f, emptyList(), 0f, 0f)
    private val R = style("F1")
    private val B = style("F2")

    @Test fun typoFixKeepsStyle() {
        assertEquals(listOf(Span("October", R)), StyleMapper.apply(listOf(Span("Octobr", R)), "October"))
    }

    @Test fun boldWordSurvivesRewrite() {
        val out = StyleMapper.apply(listOf(Span("see ", R), Span("this", B), Span(" now", R)), "please see this right now")
        assertEquals(listOf("please see ", "this", " right now"), out.map { it.text })
        assertEquals(B, out[1].style)
    }

    @Test fun insertAtStartTakesFirstStyle() {
        assertEquals(B, StyleMapper.apply(listOf(Span("x", B)), "yx")[0].style)
    }

    @Test fun deleteAllGivesEmpty() {
        assertTrue(StyleMapper.apply(listOf(Span("abc", R)), "").isEmpty())
    }

    @Test fun hugeInputUsesFallback() {
        val t = System.currentTimeMillis()
        val out = StyleMapper.apply(listOf(Span("a".repeat(3000), R)), "b".repeat(3000))
        assertEquals("b".repeat(3000), out.joinToString("") { it.text })
        assertTrue(System.currentTimeMillis() - t < 2000)
    }

    @Test fun insertedRunTakesStyleOfLastKeptBefore() {
        val out = StyleMapper.apply(listOf(Span("a", R), Span("b", B)), "aXYb")
        assertEquals(listOf("aXY", "b"), out.map { it.text })
        assertEquals(listOf(R, B), out.map { it.style })
    }

    @Test fun surrogatePairsNotSplitAndStylesShared() {
        val out = StyleMapper.apply(listOf(Span("a😀", B)), "a😀b")
        assertEquals(1, out.size)
        assertEquals("a😀b", out[0].text)
        assertSame(B, out[0].style)
    }
}
