package app.pdfeditor.flow

import app.pdfeditor.engine.ContentStreamEngine
import app.pdfeditor.engine.NodeKind
import app.pdfeditor.testing.miniPdf
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsStatePrefixTest {

    private fun str(tokens: List<Any>): String = tokens.joinToString(" ") { t ->
        when (t) {
            is COSNumber -> t.floatValue().let { if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString() }
            is COSName -> "/" + t.name
            is Operator -> t.name
            else -> t.toString()
        }
    }

    private fun prefixAt(content: String, pick: (List<app.pdfeditor.engine.DrawNode>) -> app.pdfeditor.engine.DrawNode): String =
        PDDocument.load(miniPdf(content)).use { doc ->
            val parsed = ContentStreamEngine.parse(doc.getPage(0))
            str(GraphicsStatePrefix.at(parsed.tokens, pick(parsed.leaves).startIndex))
        }

    @Test fun keepsOnlyStateInEffect() {
        val p = prefixAt("2 w 1 0 0 RG q 5 w Q 0 0 1 rg BT /F1 10 Tf 72 700 Td (x) Tj ET 10 10 m 100 10 l S\n") {
            it.single { n -> n.kind == NodeKind.PATH }
        }
        assertTrue(p, p.contains("2 w"))
        assertTrue(p, p.contains("1 0 0 RG"))
        assertTrue(p, p.contains("0 0 1 rg"))
        assertFalse(p, p.contains("5 w"))
    }

    @Test fun laterOpsReplaceEarlierAndOtherTextObjectsAreSkipped() {
        val p = prefixAt("1 w 3 w 1 g BT 0.5 g ET /DeviceRGB cs 0 1 0 scn 10 10 m 100 10 l S\n") {
            it.single { n -> n.kind == NodeKind.PATH }
        }
        assertEquals("3 w /DeviceRGB cs 0 1 0 scn", p)
    }

    @Test fun runSeesColourSetInsideItsOwnTextObject() {
        val content = "0.2 g BT /F1 10 Tf 1 0 0 rg 72 700 Td (x) Tj ET\n"
        PDDocument.load(miniPdf(content)).use { doc ->
            val parsed = ContentStreamEngine.parse(doc.getPage(0))
            val run = parsed.leaves.flatMap { it.children }.single { it.run != null }
            assertEquals("1 0 0 rg", str(GraphicsStatePrefix.at(parsed.tokens, run.startIndex)))
        }
    }
}
