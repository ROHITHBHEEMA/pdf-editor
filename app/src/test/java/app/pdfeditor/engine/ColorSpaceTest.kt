package app.pdfeditor.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSpaceTest {

    // A path filled via "/DeviceRGB cs 1 0 0 scn" must resolve to red through the
    // color-space path (rg/g/k are not the only way colors are set). Separation
    // and other spaces pdfbox-android can't build are handled by bitmap sampling
    // at runtime, which a JVM test can't exercise.
    @Test
    fun deviceRgbViaScnResolvesToRed() {
        val doc = PDDocument.load(scnPdf("/DeviceRGB cs 1 0 0 scn\n50 50 200 100 re\nf\n"))
        val path = ContentStreamEngine.parse(doc.getPage(0))
            .leaves.first { it.kind == NodeKind.PATH }
        assertEquals((0xFF shl 24) or 0xFF0000, path.colorArgb)
        assertEquals("RGB", path.colorSpace)
        doc.close()
    }

    @Test
    fun cmykFillReportsCmykModel() {
        val doc = PDDocument.load(scnPdf("0 1 1 0 k\n50 50 200 100 re\nf\n"))
        val path = ContentStreamEngine.parse(doc.getPage(0))
            .leaves.first { it.kind == NodeKind.PATH }
        assertEquals("CMYK", path.colorSpace)
        doc.close()
    }

    private fun scnPdf(content: String): ByteArray {
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
