package app.pdfeditor.engine

import app.pdfeditor.testing.PdfFixtures
import app.pdfeditor.testing.miniPdf
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRunInfoTest {

    private fun runs(pdf: ByteArray): List<TextRunInfo> =
        PDDocument.load(pdf).use { doc ->
            val parsed = ContentStreamEngine.parse(doc.getPage(0))
            parsed.leaves.filter { it.kind == NodeKind.TEXT }
                .flatMap { it.children }.mapNotNull { it.run }
        }

    private fun tokenString(t: Any): String = when (t) {
        is COSNumber -> {
            val v = t.floatValue()
            if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
        }
        is Operator -> t.name
        is COSName -> "/" + t.name
        else -> t.toString()
    }

    @Test fun runCarriesBaselineAndState() {
        val run = runs(miniPdf("BT /F1 10 Tf 2 Tc 50 Tz 1 0 0 1 100 700 Tm (AB) Tj ET")).single()
        assertEquals(100f, run.originX, 0.01f); assertEquals(700f, run.originY, 0.01f)
        assertEquals(2f, run.state.charSpacing, 0f); assertEquals(50f, run.state.horizontalScaling, 0f)
        assertEquals(100f + 2 * ((0.667f * 10f + 2f) * 0.5f), run.endX, 0.05f)
    }

    @Test fun ctmScaleGivesEffectiveSize() {
        val run = runs(miniPdf("2 0 0 2 0 0 cm BT /F1 10 Tf 10 10 Td (A) Tj ET")).single()
        assertEquals(20f, run.effectiveFontSize, 0.01f); assertEquals(20f, run.originX, 0.01f)
    }

    @Test fun flippedCtmIsNotUpright() {
        val run = runs(miniPdf("1 0 0 -1 0 792 cm BT /F1 10 Tf 10 10 Td (A) Tj ET")).single()
        assertFalse(run.isUpright)
    }

    @Test fun fillColourTokensRecorded() {
        val run = runs(miniPdf("0.2 0.4 0.6 rg BT /F1 10 Tf 10 10 Td (A) Tj ET")).single()
        assertEquals(listOf("0.2", "0.4", "0.6", "rg"), run.fillColorTokens.map(::tokenString))
    }

    @Test fun fillColourRestoredByQ() {
        val r = runs(miniPdf("0 g q 1 0 0 rg Q BT /F1 10 Tf 10 10 Td (A) Tj ET"))
        assertEquals(listOf("0", "g"), r.single().fillColorTokens.map(::tokenString))
    }

    @Test fun sampleLetterBaselines() {
        val runs = runs(PdfFixtures.sampleLetter())
        assertEquals("Date: 17 Octobr 2026", runs[1].text)
        assertTrue(runs.all { it.isUpright && it.effectiveFontSize in 10.9f..16.1f })
    }

    @Test fun wordSpacingAppliesOnlyToSingleByteSpace() {
        val plain = runs(miniPdf("BT /F1 10 Tf 0 0 Td (A B) Tj ET")).single()
        val spaced = runs(miniPdf("BT /F1 10 Tf 5 Tw 0 0 Td (A B) Tj ET")).single()
        assertEquals(5f, spaced.endX - plain.endX, 0.01f)
        assertEquals("Tj", spaced.showOperator)
    }

    @Test fun quoteOperatorsMoveLineAndSetSpacing() {
        val r = runs(miniPdf("BT /F1 10 Tf 12 TL 1 0 0 1 72 700 Tm (a) Tj (b) ' 3 1 (c) \" ET"))
        assertEquals(688f, r[1].originY, 0.01f); assertEquals(688f, r[1].textMatrix.f, 0.01f)
        assertEquals(676f, r[2].originY, 0.01f)
        assertEquals(3f, r[2].state.wordSpacing, 0f); assertEquals(1f, r[2].state.charSpacing, 0f)
    }

    @Test fun tjAdjustmentScaledByHorizontalScaling() {
        val run = runs(miniPdf("BT /F1 10 Tf 50 Tz 1 0 0 1 100 700 Tm [(A) -1000 (B)] TJ ET")).single()
        assertEquals(100f + 0.5f * (6.67f + 10f + 6.67f), run.endX, 0.05f)
    }

    @Test fun riseShiftsBaseline() {
        val run = runs(miniPdf("BT /F1 10 Tf 5 Ts 1 0 0 1 100 700 Tm (A) Tj ET")).single()
        assertEquals(705f, run.originY, 0.01f); assertEquals(5f, run.state.rise, 0f)
    }

    @Test fun csAndScnComposeFillTokens() {
        val run = runs(miniPdf("/DeviceRGB cs 0.1 0.2 0.3 scn BT /F1 10 Tf 10 10 Td (A) Tj ET")).single()
        assertEquals(
            listOf("/DeviceRGB", "cs", "0.1", "0.2", "0.3", "scn"),
            run.fillColorTokens.map(::tokenString),
        )
    }

    @Test fun qRestoresFontAndTextState() {
        val r = runs(miniPdf("q BT /F1 20 Tf 3 Tc 10 10 Td (A) Tj ET Q BT 10 20 Td (B) Tj ET"))
        assertEquals(20f, r[0].fontSize, 0f); assertEquals(3f, r[0].state.charSpacing, 0f)
        assertEquals(0f, r[1].state.charSpacing, 0f)
    }

    @Test fun qRestoresEarlierFont() {
        val r = runs(miniPdf("BT /F1 12 Tf q /F1 20 Tf 3 Tc Q 10 10 Td (A) Tj ET"))
        assertEquals(12f, r.single().fontSize, 0f); assertEquals(0f, r.single().state.charSpacing, 0f)
    }

    private fun hasTextInForms(pdf: ByteArray) =
        PDDocument.load(pdf).use { ContentStreamEngine.parse(it.getPage(0)).hasTextInForms }

    @Test fun hasTextInFormsDetectsShowOperators() {
        assertTrue(hasTextInForms(miniPdf("/Fm1 Do", mapOf("Fm1" to "BT /F1 10 Tf (x) Tj ET"))))
        assertFalse(hasTextInForms(miniPdf("/Fm1 Do", mapOf("Fm1" to "0 0 10 10 re f"))))
    }
}
