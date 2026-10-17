package app.pdfeditor.flow

import app.pdfeditor.engine.ContentStreamEngine
import app.pdfeditor.engine.NodeKind
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSObjectKey
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.io.ByteArrayInputStream

/**
 * Per font, the exact byte sequence the document itself uses to show each character. Embedded
 * subset fonts with a built-in encoding reject PDFont.encode even for glyphs they carry, so
 * this is how new text gets bytes for them.
 */
class ObservedEncodings {
    /** Codes per font key: the font's COSObjectKey when it is an indirect object, else its dictionary (identity). */
    private val byFont = HashMap<Any, MutableMap<Int, ByteArray>>()

    /**
     * Font dictionary → key; fonts of another load of the same bytes are added with [bind]. COS dictionaries use
     * identity equality, and weak keys let rendered documents' fonts go once those documents are closed.
     */
    private val keys: MutableMap<Any, Any> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    private fun keyOf(font: PDFont): Any = keys[font.cosObject] ?: IdentityKey(font.cosObject)

    fun codesFor(font: PDFont, codePoint: Int): ByteArray? = byFont[keyOf(font)]?.get(codePoint)

    /**
     * Makes [font] (from another load of the same file, e.g. the document being rendered) share the codes
     * observed for the indirect font object [key].
     */
    fun bind(font: PDFont, key: COSObjectKey) {
        keys[font.cosObject] = key
    }

    private fun record(font: PDFont, codePoint: Int, bytes: ByteArray) {
        byFont.getOrPut(keyOf(font)) { HashMap() }.putIfAbsent(codePoint, bytes)
    }

    /** Identity wrapper so a direct font dictionary is never equal to another dictionary with the same entries. */
    private class IdentityKey(val o: Any) {
        override fun equals(other: Any?) = other is IdentityKey && other.o === o
        override fun hashCode() = System.identityHashCode(o)
    }

    private fun scan(font: PDFont, s: COSString) {
        val all = s.bytes
        val input = ByteArrayInputStream(all)
        while (input.available() > 0) {
            val before = input.available()
            val code = try { font.readCode(input) } catch (_: Exception) { return }
            val consumed = before - input.available()
            if (consumed <= 0) return
            val u = try { font.toUnicode(code) } catch (_: Exception) { null } ?: continue
            if (u.isEmpty() || u.codePointCount(0, u.length) != 1) continue
            // Re-derive the code's bytes from the original string.
            val start = all.size - before
            record(font, u.codePointAt(0), all.copyOfRange(start, start + consumed))
        }
    }

    private fun scanToken(font: PDFont, t: Any?) {
        when (t) {
            is COSString -> scan(font, t)
            is COSArray -> for (i in 0 until t.size()) scanToken(font, t.get(i) as COSBase?)
        }
    }

    companion object {
        /** Number of [build] calls (lets tests check the work isn't repeated per render). */
        val buildCount = java.util.concurrent.atomic.AtomicInteger()

        /** The indirect-object key of font resource [name] in [resources], or null for a direct font object. */
        fun fontKey(resources: PDResources?, name: String): COSObjectKey? {
            val fonts = resources?.cosObject?.getDictionaryObject(COSName.FONT) as? COSDictionary ?: return null
            val item = fonts.getItem(COSName.getPDFName(name)) as? COSObject ?: return null
            return COSObjectKey(item.objectNumber, item.generationNumber)
        }

        fun build(doc: PDDocument): ObservedEncodings {
            buildCount.incrementAndGet()
            val out = ObservedEncodings()
            for (page in doc.pages) {
                val parsed = try { ContentStreamEngine.parse(page) } catch (_: Exception) { continue }
                val resources = page.resources
                fun walk(n: app.pdfeditor.engine.DrawNode) {
                    val f = n.font
                    if (n.kind == NodeKind.TEXT && f != null) {
                        // Key page-level fonts by object key so another load of the file finds the same codes.
                        val name = n.fontResourceName
                        if (name != null && f.cosObject !in out.keys) {
                            val fonts = resources?.cosObject?.getDictionaryObject(COSName.FONT) as? COSDictionary
                            if (fonts?.getDictionaryObject(COSName.getPDFName(name)) === f.cosObject) {
                                fontKey(resources, name)?.let { out.keys[f.cosObject] = it }
                            }
                        }
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
