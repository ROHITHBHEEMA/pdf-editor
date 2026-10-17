package app.pdfeditor.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

// These stay path-only / font-free so they run on a plain JVM. The text
// re-encode success path needs the font AFMs that pdfbox-android ships as
// Android assets, so it is exercised on-device, not here.
class ElementEditorTest {

    @Test
    fun translatingPathShiftsBoundsAndWrapsTokens() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }
        val before = path.bounds!!.minX

        val result = ElementEditor.editElement(
            doc, page, parsed.tokens, path, EditRequest(dx = 10f),
        )
        assertTrue("edit should apply", result is EditResult.Applied)
        val ops = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue("q present", ops.contains("q"))
        assertTrue("cm present", ops.contains("cm"))
        assertTrue("Q present", ops.contains("Q"))

        val moved = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(before + 10f, moved.bounds!!.minX, 0.5f)
        doc.close()
    }

    @Test
    fun recoloringPathSetsNewFill() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }

        val blue = (0xFF shl 24) or 0x0000FF
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(fillArgb = blue))

        val recolored = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(blue, recolored.colorArgb)
        doc.close()
    }

    @Test
    fun colorOnlyEditWrapsWithoutCm() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }

        val green = (0xFF shl 24) or 0x00FF00
        val result = ElementEditor.editElement(
            doc, page, parsed.tokens, path, EditRequest(fillArgb = green),
        )
        assertTrue(result is EditResult.Applied)
        val ops = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue("q present", ops.contains("q"))
        assertTrue("Q present", ops.contains("Q"))
        assertTrue("no cm for color-only", !ops.contains("cm"))
        doc.close()
    }

    @Test
    fun incrementalSaveReflectsEditOnReload() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(dx = 10f))

        val out = ByteArrayOutputStream()
        doc.saveIncremental(out)
        doc.close()

        val reloaded = PDDocument.load(out.toByteArray())
        val moved = ContentStreamEngine.parse(reloaded.getPage(0))
            .leaves.first { it.kind == NodeKind.PATH }
        assertEquals(60f, moved.bounds!!.minX, 0.5f)
        reloaded.close()
    }

    @Test
    fun twoIncrementalEditsBothSurvive() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        var parsed = ContentStreamEngine.parse(page)
        var path = parsed.leaves.first { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(dx = 10f))
        parsed = ContentStreamEngine.parse(page)
        path = parsed.leaves.first { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(dy = 5f))

        val out = ByteArrayOutputStream()
        doc.saveIncremental(out)
        doc.close()

        val reloaded = PDDocument.load(out.toByteArray())
        val p = ContentStreamEngine.parse(reloaded.getPage(0)).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(60f, p.bounds!!.minX, 0.5f)
        assertEquals(55f, p.bounds!!.minY, 0.5f)
        reloaded.close()
    }

    @Test
    fun blockColorRestatesFillBeforeEachRun() {
        // BT /F1 12 Tf 0 0 0 rg (Hi) Tj ET   (black text)
        val tokens = listOf<Any>(
            Operator.getOperator("BT"),
            COSName.getPDFName("F1"), COSFloat(12f), Operator.getOperator("Tf"),
            COSFloat(0f), COSFloat(0f), COSFloat(0f), Operator.getOperator("rg"),
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)), Operator.getOperator("Tj"),
            Operator.getOperator("ET"),
        )
        val run = DrawNode(
            id = 2, kind = NodeKind.TEXT, label = "run", detail = "",
            startIndex = 8, endIndex = 9, bounds = null, colorArgb = (0xFF shl 24),
            raw = "", children = emptyList(),
        )
        val block = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "block", detail = "",
            startIndex = 0, endIndex = 10, bounds = null, colorArgb = (0xFF shl 24),
            raw = "", children = listOf(run),
        )
        val doc = PDDocument()
        doc.addPage(PDPage())
        val red = (0xFF shl 24) or 0xFF0000
        val result = ElementEditor.editElement(
            doc, doc.getPage(0), tokens, block, EditRequest(fillArgb = red),
        )
        doc.close()

        assertTrue(result is EditResult.Applied)
        val out = (result as EditResult.Applied).tokens
        val si = out.indexOfFirst { it is COSString }            // the (Hi) string
        assertEquals("rg", (out[si - 1] as Operator).name)        // restated fill
        assertEquals(1f, (out[si - 4] as COSFloat).floatValue(), 1e-4f)  // red
        assertEquals(0f, (out[si - 3] as COSFloat).floatValue(), 1e-4f)
        assertEquals(0f, (out[si - 2] as COSFloat).floatValue(), 1e-4f)
        assertEquals("q", (out.first { it is Operator } as Operator).name)
        assertEquals("Q", (out.last { it is Operator } as Operator).name)
    }

    @Test
    fun textEditWithoutFontIsRejected() {
        val tokens = listOf<Any>(
            COSString("hi".toByteArray(Charsets.ISO_8859_1)),
            Operator.getOperator("Tj"),
        )
        val node = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "hi", font = null,
        )
        val doc = PDDocument()
        val result = ElementEditor.editElement(doc, PDPage(), tokens, node, EditRequest(newText = "x"))
        assertTrue("missing font should be rejected", result is EditResult.TextEncodeFailed)
        doc.close()
    }

    @Test
    fun textEditSubstitutesFontAndRestoresOriginal() {
        val tokens = listOf<Any>(
            COSName.getPDFName("F1"), COSFloat(12f), Operator.getOperator("Tf"),
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)), Operator.getOperator("Tj"),
        )
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 3, endIndex = 4, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "Hi",
            fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(newText = "Hello"), PDType1Font.HELVETICA,
        )
        assertTrue("substitution should apply", result is EditResult.Applied)
        val out = (result as EditResult.Applied).tokens
        val tfAt = out.indices.filter { (out[it] as? Operator)?.name == "Tf" }
        assertEquals("original Tf plus switch and restore", 3, tfAt.size)
        assertEquals("original font restored after run", "F1", (out[tfAt[2] - 2] as COSName).name)
        val added = out[tfAt[1] - 2] as COSName
        assertTrue("substitute font added to resources", page.resources.getFont(added) != null)
        assertTrue("run still shows text", out.any { (it as? Operator)?.name == "Tj" })
        doc.close()
    }

    private fun pathPdf(): ByteArray {
        val content = "1 0 0 rg\n50 50 200 100 re f\n"
        val length = content.toByteArray(Charsets.ISO_8859_1).size
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << >> /Contents 4 0 R >>",
            "<< /Length $length >>\nstream\n${content}endstream",
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
