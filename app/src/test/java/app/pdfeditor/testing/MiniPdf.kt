package app.pdfeditor.testing

/** Hand-built one-page PDF (Helvetica as /F1) whose content stream is [content]. */
fun miniPdf(content: String, forms: Map<String, String> = emptyMap()): ByteArray {
    val length = content.toByteArray(Charsets.ISO_8859_1).size
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 5 0 R >>" +
            (if (forms.isEmpty()) "" else " /XObject << " +
                forms.keys.withIndex().joinToString(" ") { (i, k) -> "/$k ${6 + i} 0 R" } + " >>") +
            " >> /Contents 4 0 R >>",
        "<< /Length $length >>\nstream\n${content}endstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ) + forms.values.map { c ->
        "<< /Type /XObject /Subtype /Form /BBox [0 0 100 100] " +
            "/Resources << /Font << /F1 5 0 R >> >> /Length ${c.length} >>\nstream\n${c}\nendstream"
    }
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
