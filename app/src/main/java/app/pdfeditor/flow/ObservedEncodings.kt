package app.pdfeditor.flow

import app.pdfeditor.engine.ContentStreamEngine
import app.pdfeditor.engine.NodeKind
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.io.ByteArrayInputStream
import java.util.IdentityHashMap

/**
 * Per font, the exact byte sequence the document itself uses to show each character. Embedded
 * subset fonts with a built-in encoding reject PDFont.encode even for glyphs they carry, so
 * this is how new text gets bytes for them.
 */
class ObservedEncodings {
    private val byFont = IdentityHashMap<Any, MutableMap<Int, ByteArray>>()

    fun codesFor(font: PDFont, codePoint: Int): ByteArray? = byFont[font.cosObject]?.get(codePoint)

    private fun record(font: PDFont, codePoint: Int, bytes: ByteArray) {
        byFont.getOrPut(font.cosObject) { HashMap() }.putIfAbsent(codePoint, bytes)
    }

    private fun scan(font: PDFont, s: COSString) {
        val input = ByteArrayInputStream(s.bytes)
        while (input.available() > 0) {
            val before = input.available()
            val code = try { font.readCode(input) } catch (_: Exception) { return }
            val consumed = before - input.available()
            if (consumed <= 0) return
            val u = try { font.toUnicode(code) } catch (_: Exception) { null } ?: continue
            if (u.isEmpty() || u.codePointCount(0, u.length) != 1) continue
            // Re-derive the code's bytes from the original string.
            val start = s.bytes.size - before
            record(font, u.codePointAt(0), s.bytes.copyOfRange(start, start + consumed))
        }
    }

    private fun scanToken(font: PDFont, t: Any?) {
        when (t) {
            is COSString -> scan(font, t)
            is COSArray -> for (i in 0 until t.size()) scanToken(font, t.get(i) as COSBase?)
        }
    }

    companion object {
        fun build(doc: PDDocument): ObservedEncodings {
            val out = ObservedEncodings()
            for (page in doc.pages) {
                val parsed = try { ContentStreamEngine.parse(page) } catch (_: Exception) { continue }
                fun walk(n: app.pdfeditor.engine.DrawNode) {
                    val f = n.font
                    if (n.kind == NodeKind.TEXT && f != null) {
                        for (i in n.startIndex..minOf(n.endIndex, parsed.tokens.size - 1)) {
                            out.scanToken(f, parsed.tokens[i])
                        }
                    }
                    n.children.forEach(::walk)
                }
                walk(parsed.root)
            }
            return out
        }
    }
}
