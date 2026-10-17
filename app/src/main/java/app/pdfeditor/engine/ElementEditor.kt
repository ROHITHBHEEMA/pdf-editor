package app.pdfeditor.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.io.IOException

// Page-space edit for one element: translate by dx,dy, scale by scaleX,scaleY
// about the element's lower-left, recolor, and/or replace text.
class EditRequest(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val fillArgb: Int? = null,
    val strokeArgb: Int? = null,
    val newText: String? = null,
    val fontEntryId: String? = null,
)

// What the edit form may offer for a given node.
class EditCaps(
    val canGeom: Boolean,
    val canFill: Boolean,
    val canStroke: Boolean,
    val canText: Boolean,
)

sealed class EditResult {
    class Applied(val tokens: List<Any>) : EditResult()
    object NoChange : EditResult()
    object Degenerate : EditResult()
    class TextEncodeFailed(val chars: String? = null) : EditResult()
}

object ElementEditor {

    // Drops tokens [start, end] and rewrites the page content stream. Everything
    // outside the range is preserved byte-equivalent through ContentStreamWriter.
    fun deleteRange(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        start: Int,
        end: Int,
    ): List<Any> {
        val kept = ArrayList<Any>(tokens.size)
        for (i in tokens.indices) {
            if (i < start || i > end) kept.add(tokens[i])
        }
        writeStream(document, page, kept)
        return kept
    }

    private fun writeStream(document: PDDocument, page: PDPage, tokens: List<Any>) {
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { out ->
            ContentStreamWriter(out).writeTokens(tokens)
        }
        commit(document, page, stream)
    }

    // Captures the page's current content stream so an edit can be reverted.
    fun snapshot(page: PDPage): ByteArray? = page.contents?.use { it.readBytes() }

    fun restore(document: PDDocument, page: PDPage, content: ByteArray) {
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { out -> out.write(content) }
        commit(document, page, stream)
    }

    // Geometry needs a q/cm/Q wrapper, illegal inside a text object, so it is
    // offered on paths, images and whole text objects (BT..ET) but never runs.
    fun capabilities(tokens: List<Any>, node: DrawNode): EditCaps {
        val textObject = node.kind == NodeKind.TEXT && isOp(tokens.getOrNull(node.startIndex), "BT")
        val textRun = node.kind == NodeKind.TEXT && !textObject
        val path = node.kind == NodeKind.PATH
        val image = node.kind == NodeKind.IMAGE
        val wrappable = path || image || textObject
        val canGeom = wrappable && node.bounds != null && node.ctm?.inverse() != null
        val paint = (tokens.getOrNull(node.endIndex) as? Operator)?.name ?: ""
        val pathFill = path && (paint == "f" || paint == "F" || paint == "f*" ||
            paint.startsWith("B") || paint.startsWith("b"))
        val pathStroke = path && (paint == "S" || paint == "s" ||
            paint.startsWith("B") || paint.startsWith("b"))
        // A filled/stroked path always has a paint color (sampled from the page
        // when its color space is unresolved). Text only exposes a color when one
        // was set, so gate text color on that.
        val hasColor = node.colorArgb != null
        return EditCaps(
            canGeom = canGeom,
            canFill = pathFill || ((textObject || textRun) && hasColor),
            canStroke = pathStroke,
            canText = textRun,
        )
    }

    fun editElement(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
        substituteFont: PDFont? = null,
        subScale: Float = 1f,
    ): EditResult {
        val result = rebuild(tokens, node, request, page, substituteFont, subScale)
        if (result is EditResult.Applied) writeStream(document, page, result.tokens)
        return result
    }

    private fun rebuild(
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
        page: PDPage,
        substituteFont: PDFont?,
        subScale: Float,
    ): EditResult {
        val textObject = node.kind == NodeKind.TEXT && isOp(tokens.getOrNull(node.startIndex), "BT")
        val textRun = node.kind == NodeKind.TEXT && !textObject
        if (textRun) return rebuildTextRun(tokens, node, request, page, substituteFont, subScale)

        val wrappable = node.kind == NodeKind.PATH || node.kind == NodeKind.IMAGE || textObject
        val wantsGeom = request.dx != 0f || request.dy != 0f ||
            request.scaleX != 1f || request.scaleY != 1f
        val wantsColor = request.fillArgb != null || request.strokeArgb != null
        if (!wrappable || (!wantsGeom && !wantsColor)) return EditResult.NoChange

        val prefix = ArrayList<Any>()
        prefix.add(Operator.getOperator("q"))
        if (wantsGeom) {
            val m = node.ctm ?: return EditResult.Degenerate
            val b = node.bounds ?: return EditResult.Degenerate
            val inv = m.inverse() ?: return EditResult.Degenerate
            val t = Affine.scaleAbout(request.scaleX, request.scaleY, b.minX, b.minY)
                .then(Affine.translate(request.dx, request.dy))
            val c = m.then(t).then(inv)
            prefix.add(COSFloat(c.a)); prefix.add(COSFloat(c.b)); prefix.add(COSFloat(c.c))
            prefix.add(COSFloat(c.d)); prefix.add(COSFloat(c.e)); prefix.add(COSFloat(c.f))
            prefix.add(Operator.getOperator("cm"))
        }
        // Paths/images carry no color operator inside the wrapped range, so a new
        // color sits in the prefix. A text object DOES set colors inside BT/ET,
        // so its fill must be restated before each run to win; done in the body.
        if (!textObject) {
            request.fillArgb?.let { appendColor(prefix, it, false) }
            request.strokeArgb?.let { appendColor(prefix, it, true) }
        }

        val out = ArrayList<Any>(tokens.size + prefix.size + 8)
        out.addAll(tokens.subList(0, node.startIndex))
        out.addAll(prefix)
        val blockFill = if (textObject) request.fillArgb else null
        if (blockFill != null) {
            val runStarts = node.children.mapTo(HashSet()) { it.startIndex }
            for (i in node.startIndex..node.endIndex) {
                if (i in runStarts) appendColor(out, blockFill, false)
                out.add(tokens[i])
            }
        } else {
            out.addAll(tokens.subList(node.startIndex, node.endIndex + 1))
        }
        out.add(Operator.getOperator("Q"))
        out.addAll(tokens.subList(node.endIndex + 1, tokens.size))
        return EditResult.Applied(out)
    }

    // Rewrites one show-text run. When substituteFont is given the run is
    // re-encoded with it: a Tf selecting the new resource is emitted before the
    // run and the original Tf restated after, since q/Q cannot wrap text.
    private fun rebuildTextRun(
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
        page: PDPage,
        substituteFont: PDFont?,
        subScale: Float,
    ): EditResult {
        val wantsColor = request.fillArgb != null
        val newText = request.newText
        if (!wantsColor && newText == null) return EditResult.NoChange

        val out = ArrayList<Any>(tokens.size + 8)
        out.addAll(tokens.subList(0, node.startIndex))
        request.fillArgb?.let { appendColor(out, it, false) }
        if (newText != null) {
            val font = substituteFont ?: node.font ?: return EditResult.TextEncodeFailed()
            val bytes = try {
                font.encode(newText)
            } catch (_: Exception) {
                return EditResult.TextEncodeFailed(unsupportedChars(font, newText))
            }
            val newName = if (substituteFont != null) {
                val res = page.resources ?: PDResources().also { page.resources = it }
                ensureFontResource(res, substituteFont)
            } else {
                null
            }
            if (newName != null) {
                out.add(newName)
                out.add(COSFloat(node.fontSize * subScale))
                out.add(Operator.getOperator("Tf"))
            }
            for (i in node.startIndex until node.endIndex - 1) out.add(tokens[i])
            out.add(COSString(bytes))
            val opName = (tokens[node.endIndex] as? Operator)?.name
            out.add(if (opName == "TJ") Operator.getOperator("Tj") else tokens[node.endIndex])
            if (newName != null && !node.fontResourceName.isNullOrBlank()) {
                out.add(COSName.getPDFName(node.fontResourceName))
                out.add(COSFloat(node.fontSize))
                out.add(Operator.getOperator("Tf"))
            }
        } else {
            out.addAll(tokens.subList(node.startIndex, node.endIndex + 1))
        }
        out.addAll(tokens.subList(node.endIndex + 1, tokens.size))
        return EditResult.Applied(out)
    }

    // Reuses an already-added resource when the same font is applied to several
    // runs, so repeated edits don't pile up duplicate /Font entries.
    private fun ensureFontResource(resources: PDResources, font: PDFont): COSName {
        for (name in resources.fontNames) {
            val existing = try {
                resources.getFont(name)
            } catch (_: Exception) {
                null
            }
            if (existing != null && existing.cosObject === font.cosObject) return name
        }
        return resources.add(font)
    }

    // The distinct characters the font cannot encode, for a useful message.
    private fun unsupportedChars(font: PDFont, text: String): String {
        val bad = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            try {
                font.encode(s)
            } catch (_: Exception) {
                if (s.isNotBlank()) bad.add(s)
            }
            i += Character.charCount(cp)
        }
        return bad.joinToString(" ")
    }

    private fun appendColor(out: MutableList<Any>, argb: Int, stroke: Boolean) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        out.add(COSFloat(r)); out.add(COSFloat(g)); out.add(COSFloat(b))
        out.add(Operator.getOperator(if (stroke) "RG" else "rg"))
    }

    // Swap the page's content stream and flag what changed so saveIncremental
    // appends just those objects instead of rewriting the whole file.
    //
    // setNeedToBeUpdated is a TRAVERSAL GATE, not only a write filter: the
    // incremental writer walks the object graph from the trailer and refuses to
    // descend into an unflagged object. So flagging only the page + new stream
    // leaves them unreachable and the increment comes out empty. The entire
    // chain catalog -> pages -> page -> stream must be flagged.
    //
    // Flags are never cleared by the writer and saveIncremental always re-reads
    // the original bytes, so flags left on across edits make changes accumulate
    // correctly. clearContentsFlag drops the superseded stream so orphaned
    // streams don't pile up in the cache.
    private fun commit(document: PDDocument, page: PDPage, stream: PDStream) {
        clearContentsFlag(page.cosObject.getDictionaryObject(COSName.CONTENTS))
        page.setContents(stream)
        stream.cosObject.setNeedToBeUpdated(true)
        var dict: COSDictionary? = page.cosObject
        while (dict != null) {
            dict.setNeedToBeUpdated(true)
            dict = dict.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        document.documentCatalog.cosObject.setNeedToBeUpdated(true)
    }

    private fun clearContentsFlag(contents: COSBase?) {
        when (contents) {
            is COSStream -> contents.setNeedToBeUpdated(false)
            is COSArray -> for (i in 0 until contents.size()) {
                (contents.getObject(i) as? COSStream)?.setNeedToBeUpdated(false)
            }
            else -> {}
        }
    }

    private fun isOp(token: Any?, name: String): Boolean =
        token is Operator && token.name == name
}
