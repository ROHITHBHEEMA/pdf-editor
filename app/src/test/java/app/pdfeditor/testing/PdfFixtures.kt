package app.pdfeditor.testing

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File

/** Test-only PDF builders. Unit tests run with the `app/` module as working directory. */
object PdfFixtures {

    fun font(doc: PDDocument, file: String = "LiberationSerif-Regular"): PDFont =
        PDType0Font.load(doc, File("src/main/assets/fonts/$file.ttf"))

    private fun save(doc: PDDocument): ByteArray =
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()

    private fun width(font: PDFont, text: String, size: Float) = font.getStringWidth(text) / 1000f * size

    /** Greedy word wrap of [text] into lines no wider than [maxWidth]. */
    private fun wrap(font: PDFont, text: String, size: Float, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var cur = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isNotEmpty() && width(font, candidate, size) > maxWidth) {
                lines += cur
                cur = word
            } else cur = candidate
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    fun letter(
        paragraphs: List<String>,
        fontFile: String = "LiberationSerif-Regular",
        size: Float = 11f,
        leading: Float = 16f,
        paragraphGap: Float = 8f,
        align: String = "left",
        pageSize: PDRectangle = PDRectangle.A4,
        margin: Float = 72f,
    ): ByteArray {
        require(align == "left" || align == "justify") { "align must be left or justify" }
        PDDocument().use { doc ->
            val font = font(doc, fontFile)
            val maxWidth = pageSize.width - 2 * margin
            val bottom = margin
            var stream: PDPageContentStream? = null
            var y = 0f
            fun newPage() {
                stream?.close()
                val page = PDPage(pageSize)
                doc.addPage(page)
                stream = PDPageContentStream(doc, page)
                y = pageSize.height - margin - size
            }
            newPage()
            for (p in paragraphs) {
                val lines = wrap(font, p, size, maxWidth)
                lines.forEachIndexed { i, line ->
                    if (y < bottom) newPage()
                    val s = stream!!
                    s.beginText()
                    s.setFont(font, size)
                    val gaps = line.count { it == ' ' }
                    val isLast = i == lines.lastIndex
                    if (align == "justify" && !isLast && gaps > 0) {
                        s.setWordSpacing((maxWidth - width(font, line, size)) / gaps)
                    }
                    s.newLineAtOffset(margin, y)
                    s.showText(line)
                    s.endText()
                    y -= leading
                }
                y -= paragraphGap
            }
            stream?.close()
            return save(doc)
        }
    }

    fun perWordRuns(text: String): ByteArray {
        PDDocument().use { doc ->
            val font = font(doc)
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { s ->
                var x = 72f
                val y = page.mediaBox.height - 72f
                val space = width(font, " ", 11f)
                for (word in text.split(' ').filter { it.isNotEmpty() }) {
                    s.beginText()
                    s.setFont(font, 11f)
                    s.newLineAtOffset(x, y)
                    s.showText(word)
                    s.endText()
                    x += width(font, word, 11f) + space
                }
            }
            return save(doc)
        }
    }

    fun twoColumn(left: List<String>, right: List<String>): ByteArray {
        PDDocument().use { doc ->
            val font = font(doc)
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val margin = 72f
            val colWidth = (page.mediaBox.width - 2 * margin - 24f) / 2f
            PDPageContentStream(doc, page).use { s ->
                fun column(paras: List<String>, x: Float) {
                    var y = page.mediaBox.height - margin - 11f
                    for (p in paras) {
                        for (line in wrap(font, p, 11f, colWidth)) {
                            s.beginText()
                            s.setFont(font, 11f)
                            s.newLineAtOffset(x, y)
                            s.showText(line)
                            s.endText()
                            y -= 16f
                        }
                        y -= 8f
                    }
                }
                column(left, margin)
                column(right, margin + colWidth + 24f)
            }
            return save(doc)
        }
    }

    fun withHeaderFooter(pages: Int, bodyParagraphs: List<String>): ByteArray {
        PDDocument().use { doc ->
            val font = font(doc)
            val size = PDRectangle.A4
            val margin = 72f
            repeat(pages) { n ->
                val page = PDPage(size)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { s ->
                    fun text(t: String, x: Float, y: Float) {
                        s.beginText(); s.setFont(font, 11f); s.newLineAtOffset(x, y); s.showText(t); s.endText()
                    }
                    text("Riverside Trust", margin, size.height - 40f)
                    text("Page ${n + 1}", margin, 36f)
                    var y = size.height - margin - 11f
                    for (p in bodyParagraphs) {
                        for (line in wrap(font, p, 11f, size.width - 2 * margin)) {
                            text(line, margin, y)
                            y -= 16f
                        }
                        y -= 8f
                    }
                }
            }
            return save(doc)
        }
    }

    fun rotated(): ByteArray {
        PDDocument().use { doc ->
            val font = font(doc)
            val page = PDPage(PDRectangle.A4)
            page.rotation = 90
            doc.addPage(page)
            PDPageContentStream(doc, page).use { s ->
                s.beginText(); s.setFont(font, 11f)
                s.newLineAtOffset(72f, page.mediaBox.height - 72f)
                s.showText("Rotated page text"); s.endText()
            }
            return save(doc)
        }
    }

    fun encrypted(userPassword: String): ByteArray {
        PDDocument().use { doc ->
            val font = font(doc)
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { s ->
                s.beginText(); s.setFont(font, 11f)
                s.newLineAtOffset(72f, page.mediaBox.height - 72f)
                s.showText("Secret text"); s.endText()
            }
            val policy = StandardProtectionPolicy("owner-$userPassword", userPassword, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            return save(doc)
        }
    }

    fun sampleLetter(): ByteArray =
        PdfFixtures::class.java.getResourceAsStream("/fixtures/sample-letter.pdf")!!.use { it.readBytes() }
}

/** Extracts text with PDFTextStripper; [page] is 1-based (PDFTextStripper's convention), or null for all pages. */
fun extractText(bytes: ByteArray, page: Int? = null): String =
    PDDocument.load(bytes).use { doc ->
        val stripper = PDFTextStripper()
        if (page != null) {
            stripper.startPage = page
            stripper.endPage = page
        }
        stripper.getText(doc)
    }
