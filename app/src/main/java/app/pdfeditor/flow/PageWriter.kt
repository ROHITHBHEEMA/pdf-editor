package app.pdfeditor.flow

import app.pdfeditor.engine.Affine
import app.pdfeditor.engine.DrawNode
import app.pdfeditor.engine.TextRunInfo
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream

sealed class Draw {
    /** Redraws the block's original tokens shifted by [dy], skipping the run nodes in [exclude]. */
    class MoveBlock(val block: Block, val sourcePage: PDPage, val dy: Float, val exclude: Set<DrawNode> = emptySet()) : Draw()

    /** Draws a line laid out by [ParagraphLayout]; bytes come only from [plan]. */
    class NewLine(val line: LaidLine, val plan: FontPlan, val sourcePage: PDPage) : Draw()
}

object PageWriter {

    /**
     * Rewrites `page`: `q` + original tokens with `removedNodes`' drawing tokens taken out + `Q`, then each draw
     * wrapped in `q … Q`. `originalTokens == null` (blank or auto page) writes only the draws. Unbalanced q/Q in the
     * original is balanced inside the wrapper so draws never inherit its state.
     *
     * Node indices (removed nodes here, and [Draw.MoveBlock] nodes that name this page as their source later) refer
     * to the page's original tokens. So `write` remembers `originalTokens` per page dictionary (weakly, by identity,
     * replaced on each call); a later move out of an already rewritten page reads those instead of re-parsing the new
     * stream. A source page never written is parsed as is.
     */
    fun write(doc: PDDocument, page: PDPage, originalTokens: List<Any>?, removedNodes: List<DrawNode>, draws: List<Draw>) {
        val out = ArrayList<Any>()
        if (originalTokens != null) originals[page.cosObject] = originalTokens
        if (originalTokens != null) {
            out.emit(op("q"))
            out.emit(balanced(strip(originalTokens, removedNodes)))
            out.emit(op("Q"))
        }
        val target = page.resources ?: PDResources().also { page.resources = it }
        // Graphics-state prefixes for every moved node, one pass per source page.
        val sources = HashMap<COSDictionary, Source>()
        draws.filterIsInstance<Draw.MoveBlock>().groupBy { it.sourcePage.cosObject }.forEach { (key, moves) ->
            val tokens = tokensOf(moves.first().sourcePage)
            val starts = moves.flatMap { m -> (m.block.runNodes + m.block.leafNodes).filter { it !in m.exclude }.map { it.startIndex } }
            sources[key] = Source(tokens, GraphicsStatePrefix.atAll(tokens, starts))
        }
        for (d in draws) when (d) {
            is Draw.MoveBlock -> moveBlock(d, target, out, sources.getValue(d.sourcePage.cosObject))
            is Draw.NewLine -> newLine(d, target, out)
        }
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
        page.setContents(stream)
    }

    /**
     * Replacement for a removed show op ([show] = the run's operand+operator tokens) that keeps every position after
     * it unchanged: `[-n] TJ` advancing the text matrix by the run's advance for Tj/TJ, `T* [-n] TJ` for ',
     * `aw Tw ac Tc T* [-n] TJ` for ". The advance is computed exactly as the parser does (glyph widths, Tc, Tw, Tz).
     */
    fun removalTokens(run: TextRunInfo, show: List<Any>): List<Any> {
        val skip = advanceTokens(run, show)
        return when (run.showOperator) {
            "'" -> listOf<Any>(op("T*")) + skip
            "\"" -> listOf(num(run.state.wordSpacing), op("Tw"), num(run.state.charSpacing), op("Tc"), op("T*")) + skip
            else -> skip
        }
    }

    private fun advanceTokens(run: TextRunInfo, show: List<Any>): List<Any> {
        val th = run.state.horizontalScaling / 100f
        val unit = run.fontSize * th
        if (unit == 0f) return emptyList()
        var adv = 0f
        for (t in show) when (t) {
            is COSString -> adv += stringAdvance(run, t.bytes)
            is COSArray -> for (j in 0 until t.size()) when (val el = t.getObject(j)) {
                is COSString -> adv += stringAdvance(run, el.bytes)
                is COSNumber -> adv += -el.floatValue() / 1000f * unit
            }
        }
        if (adv == 0f) return emptyList()
        return listOf(COSArray().apply { add(num(-adv * 1000f / unit)) }, op("TJ"))
    }

    /** Text-space advance of one string, mirroring ContentStreamEngine (0.5 em per byte when decoding fails). */
    private fun stringAdvance(run: TextRunInfo, bytes: ByteArray): Float {
        val s = run.state
        val th = s.horizontalScaling / 100f
        fun glyph(w: Float, space: Boolean) = (w + s.charSpacing + (if (space) s.wordSpacing else 0f)) * th
        val font = run.font
        if (font != null) {
            try {
                var width = 0f
                val input = java.io.ByteArrayInputStream(bytes)
                while (input.available() > 0) {
                    val before = input.available()
                    val code = font.readCode(input)
                    val w = try { font.getDisplacement(code).x * run.fontSize } catch (_: Exception) { run.fontSize * 0.5f }
                    width += glyph(w, code == 32 && before - input.available() == 1)
                }
                return width
            } catch (_: Exception) {
            }
        }
        return bytes.fold(0f) { acc, b -> acc + glyph(run.fontSize * 0.5f, (b.toInt() and 0xFF) == 32) }
    }

    /** Drops a Q that would pop below the start and appends Q's for unclosed q's. */
    private fun balanced(tokens: List<Any>): List<Any> {
        val out = ArrayList<Any>(tokens.size + 2)
        var depth = 0
        for (t in tokens) {
            if (t is Operator && t.name == "Q") { if (depth == 0) continue; depth-- }
            if (t is Operator && t.name == "q") depth++
            out += t
        }
        repeat(depth) { out += op("Q") }
        return out
    }

    // ---- removal ----

    private fun strip(tokens: List<Any>, removed: List<DrawNode>): List<Any> {
        // Outermost ranges only: a range nested inside another removed range is already gone.
        val ranges = removed.distinct().sortedWith(compareBy<DrawNode> { it.startIndex }.thenByDescending { it.endIndex })
        val kept = ArrayList<DrawNode>()
        for (n in ranges) {
            if (n.startIndex < 0 || n.endIndex < n.startIndex) continue
            val last = kept.lastOrNull()
            if (last != null && n.startIndex <= last.endIndex) {
                require(n.endIndex <= last.endIndex) { "Removed nodes overlap partially" }
                continue
            }
            kept += n
        }
        val result = ArrayList<Any>(tokens.size)
        var i = 0
        var k = 0
        while (i < tokens.size) {
            val n = kept.getOrNull(k)
            if (n != null && i == n.startIndex) {
                n.run?.let { result.emit(removalTokens(it, tokens.subList(n.startIndex, n.endIndex + 1))) }
                i = n.endIndex + 1
                k++
            } else {
                result.emit(tokens[i])
                i++
            }
        }
        return result
    }

    // ---- moved content ----

    /** A source page's original tokens and the graphics-state prefixes of its moved nodes. */
    private class Source(val tokens: List<Any>, val prefixes: Map<Int, List<Any>>)

    private fun moveBlock(d: Draw.MoveBlock, target: PDResources, out: MutableList<Any>, src: Source) {
        val source = d.sourcePage.resources ?: PDResources()
        val renamer = Renamer(target, source)
        val sourceTokens = src.tokens
        val prefixes = src.prefixes
        for (node in d.block.runNodes) {
            if (node in d.exclude) continue
            val run = node.run ?: continue
            out.emit(op("q"))
            out.emit(matrix(Affine.translate(0f, d.dy)) + op("cm"))
            out.emit(matrix(run.ctm) + op("cm"))
            // Graphics state in effect at the run (incl. fill set inside its own BT) replaces fillColorTokens.
            out.emit(renamer.rename(prefixes.getValue(node.startIndex)))
            out.emit(op("BT"))
            out.emit(renamer.font(COSName.getPDFName(run.fontResourceName))); out.emit(num(run.fontSize)); out.emit(op("Tf"))
            val s = run.state
            out.emit(listOf(num(s.charSpacing), op("Tc"), num(s.wordSpacing), op("Tw"), num(s.horizontalScaling), op("Tz")))
            out.emit(listOf(num(s.leading), op("TL"), num(s.rise), op("Ts"), COSInteger.get(s.renderMode.toLong()), op("Tr")))
            out.emit(matrix(run.textMatrix) + op("Tm"))
            val slice = sourceTokens.subList(node.startIndex, node.endIndex + 1)
            check((slice.last() as? Operator)?.name == run.showOperator) { "Run node does not match the source page's tokens" }
            out.emit(showTokens(run, slice))
            out.emit(op("ET"))
            out.emit(op("Q"))
        }
        for (leaf in d.block.leafNodes) {
            if (leaf in d.exclude) continue
            out.emit(op("q"))
            out.emit(matrix(Affine.translate(0f, d.dy)) + op("cm"))
            out.emit(matrix(leaf.ctm ?: Affine.IDENTITY) + op("cm"))
            out.emit(renamer.rename(prefixes.getValue(leaf.startIndex)))
            out.emit(renamer.rename(sourceTokens.subList(leaf.startIndex, leaf.endIndex + 1)))
            out.emit(op("Q"))
        }
    }

    /** Original tokens of pages already rewritten, keyed by page dictionary (identity). */
    private val originals = java.util.Collections.synchronizedMap(java.util.WeakHashMap<COSDictionary, List<Any>>())

    /** Original tokens of the source page; node indices refer to this list. */
    private fun tokensOf(page: PDPage): List<Any> {
        originals[page.cosObject]?.let { return it }
        if (!page.hasContents()) return emptyList()
        val parser = PDFStreamParser(page)
        parser.parse()
        return parser.tokens
    }

    /** The run's show op: Tj/TJ replayed as is; ' and " show their string with Tj (Tm already includes the line move). */
    private fun showTokens(run: TextRunInfo, slice: List<Any>): List<Any> = when (run.showOperator) {
        "'", "\"" -> listOf(slice.last { it is COSString }, op("Tj"))
        else -> slice
    }

    /** Renames resource operands in drawn tokens through [ResourceCopier]. */
    private class Renamer(val target: PDResources, val source: PDResources) {
        private val cache = HashMap<Pair<COSName, COSName>, COSName>()

        fun ensure(category: COSName, name: COSName): COSName =
            cache.getOrPut(category to name) { ResourceCopier.ensure(target, source, category, name) }

        fun font(name: COSName) = ensure(COSName.FONT, name)

        fun rename(tokens: List<Any>): List<Any> {
            val result = ArrayList<Any>(tokens)
            var operandStart = 0
            for (i in result.indices) {
                val t = result[i]
                if (t !is Operator) continue
                val category = when (t.name) {
                    "Tf" -> COSName.FONT
                    "Do" -> COSName.XOBJECT
                    "gs" -> COSName.EXT_G_STATE
                    "cs", "CS" -> COSName.COLORSPACE
                    "scn", "SCN" -> COSName.PATTERN
                    "sh" -> COSName.SHADING
                    else -> null
                }
                if (category != null) {
                    for (j in operandStart until i) {
                        val n = result[j] as? COSName ?: continue
                        if (category == COSName.COLORSPACE && n.name in DEVICE_SPACES) continue
                        result[j] = ensure(category, n)
                    }
                }
                operandStart = i + 1
            }
            return result
        }
    }

    private val DEVICE_SPACES = setOf("DeviceRGB", "DeviceGray", "DeviceCMYK", "Pattern")

    // ---- new lines ----

    private fun newLine(d: Draw.NewLine, target: PDResources, out: MutableList<Any>) {
        val source = d.sourcePage.resources ?: PDResources()
        val renamer = Renamer(target, source)
        for (f in d.line.fragments) {
            if (f.text.isEmpty()) continue
            val style = f.style
            val font = d.plan.fontFor(style)
            val srcName = COSName.getPDFName(style.fontResourceName)
            val srcFont = try { source.getFont(srcName) } catch (_: Exception) { null }
            val name = if (srcFont != null && srcFont.cosObject === font.cosObject) {
                ResourceCopier.ensure(target, source, COSName.FONT, srcName)
            } else {
                ResourceCopier.ensureFont(target, font)
            }
            val size = style.effectiveFontSize
            val hScale = style.horizontalScale
            val array = COSArray()
            for (piece in splitAfterSpaces(f.text)) {
                if (font.willBeSubset()) piece.codePoints().forEach { font.addToSubset(it) }
                array.add(COSString(d.plan.encode(style, piece)))
                if (piece.endsWith(' ') && f.spaceAdjust != 0f) array.add(num(-f.spaceAdjust * 1000f / (size * hScale)))
            }
            out.emit(op("q"))
            out.emit(op("BT"))
            out.emit(renamer.rename(style.fillColorTokens.map(::parseToken)))
            out.emit(listOf(name, num(size), op("Tf"), num(style.charSpacing), op("Tc"), num(hScale * 100f), op("Tz"), num(style.rise), op("Ts")))
            out.emit(matrix(Affine.translate(f.x, d.line.baseline)) + op("Tm"))
            out.emit(listOf(array, op("TJ")))
            out.emit(op("ET"))
            out.emit(op("Q"))
        }
    }

    /** "a b  c" → ["a ", "b ", " ", "c"]: each piece ends with at most one space. */
    private fun splitAfterSpaces(text: String): List<String> {
        val pieces = ArrayList<String>()
        var start = 0
        for (i in text.indices) if (text[i] == ' ') { pieces += text.substring(start, i + 1); start = i + 1 }
        if (start < text.length) pieces += text.substring(start)
        return pieces
    }

    // ---- tokens ----

    /** Appends a token, or every token of a list. */
    private fun MutableList<Any>.emit(t: Any) {
        if (t is List<*>) t.forEach { add(it!!) } else add(t)
    }

    private fun op(name: String): Operator = Operator.getOperator(name)

    private fun num(v: Float): COSBase =
        if (v == Math.rint(v.toDouble()).toFloat() && kotlin.math.abs(v) < 1e9f) COSInteger.get(v.toLong()) else COSFloat(v)

    private fun matrix(m: Affine): List<Any> = listOf(num(m.a), num(m.b), num(m.c), num(m.d), num(m.e), num(m.f))

    private fun parseToken(s: String): Any = when {
        s.startsWith("/") -> COSName.getPDFName(s.substring(1))
        s.toLongOrNull() != null -> COSInteger.get(s.toLong())
        s.toFloatOrNull() != null && s.any { it.isDigit() } -> COSFloat(s.toFloat())
        else -> op(s)
    }
}
