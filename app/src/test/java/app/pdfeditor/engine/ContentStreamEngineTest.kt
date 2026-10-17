package app.pdfeditor.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentStreamEngineTest {

    @Test
    fun parsesTextImageAndPath() {
        val doc = PDDocument.load(samplePdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)

        val kinds = parsed.leaves.map { it.kind }
        assertTrue("expected a text node", kinds.contains(NodeKind.TEXT))
        assertTrue("expected a path node", kinds.contains(NodeKind.PATH))

        val text = parsed.leaves.first { it.kind == NodeKind.TEXT }
        assertTrue("text label should carry preview", text.label.contains("Hello World"))
        assertTrue("token range should be ordered", text.startIndex <= text.endIndex)
        assertTrue("text should have bounds", text.bounds != null)

        doc.close()
    }

    @Test
    fun deletingTextRewritesTheStream() {
        val doc = PDDocument.load(samplePdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val text = parsed.leaves.first { it.kind == NodeKind.TEXT }

        val kept = ElementEditor.deleteRange(
            doc, page, parsed.tokens, text.startIndex, text.endIndex,
        )
        assertTrue("tokens should shrink", kept.size < parsed.tokens.size)

        val reparsed = ContentStreamEngine.parse(page)
        val stillThere = reparsed.leaves.any {
            it.kind == NodeKind.TEXT && it.label.contains("Hello World")
        }
        assertTrue("deleted text should be gone after re-parse", !stillThere)
        assertTrue("the path should survive", reparsed.leaves.any { it.kind == NodeKind.PATH })

        doc.close()
    }

    @Test
    fun textRunCarriesFontResourceAndSize() {
        val doc = PDDocument.load(samplePdf())
        val parsed = ContentStreamEngine.parse(doc.getPage(0))
        val textObj = parsed.leaves.first { it.kind == NodeKind.TEXT }
        val run = textObj.children.first { it.kind == NodeKind.TEXT }
        assertEquals("F1", run.fontResourceName)
        assertEquals(24f, run.fontSize, 1e-4f)
        doc.close()
    }

    private fun samplePdf(): ByteArray {
        val content =
            "BT /F1 24 Tf 100 700 Td (Hello World) Tj ET\n1 0 0 rg\n50 50 200 100 re f\n"
        val length = content.toByteArray(Charsets.ISO_8859_1).size
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length $length >>\nstream\n${content}endstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        val offsets = IntArray(objects.size + 1)
        for ((i, obj) in objects.withIndex()) {
            offsets[i + 1] = sb.toString().toByteArray(Charsets.ISO_8859_1).size
            sb.append("${i + 1} 0 obj\n").append(obj).append("\nendobj\n")
        }
        val xrefOffset = sb.toString().toByteArray(Charsets.ISO_8859_1).size
        sb.append("xref\n0 ${objects.size + 1}\n")
        sb.append("0000000000 65535 f \n")
        for (i in 1..objects.size) sb.append(String.format("%010d 00000 n \n", offsets[i]))
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
        sb.append("startxref\n$xrefOffset\n%%EOF")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
