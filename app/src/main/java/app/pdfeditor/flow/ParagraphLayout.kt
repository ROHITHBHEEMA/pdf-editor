package app.pdfeditor.flow

import com.tom_roush.pdfbox.pdmodel.font.PDFont

class FontWarning(val missingChars: String, val substituteNames: List<String>)

class FontPlan(
    private val fonts: Map<SpanStyle, PDFont>,
    val warning: FontWarning?,
    private val observed: ObservedEncodings = ObservedEncodings(),
) {
    fun fontFor(style: SpanStyle): PDFont =
        fonts[style] ?: throw IllegalArgumentException("No font planned for style ${style.fontResourceName}")

    /** Bytes that show [text] in the planned font for [style]; throws if a character cannot be drawn. */
    fun encode(style: SpanStyle, text: String): ByteArray {
        val font = fontFor(style)
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val s = String(Character.toChars(cp))
            val bytes = try { font.encode(s) } catch (_: Exception) { observed.codesFor(font, cp) }
                ?: throw IllegalArgumentException("Cannot encode '$s' (U+%04X) in font ${font.name}".format(cp))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    companion object {
        fun canDraw(font: PDFont, c: String, observed: ObservedEncodings): Boolean =
            try { font.encode(c); true } catch (_: Exception) { observed.codesFor(font, c.codePointAt(0)) != null }
    }
}

/** [spaceAdjust] is the extra page-space width added after each ' ' in [text] (justification). */
class PlacedFragment(val text: String, val style: SpanStyle, val x: Float, val width: Float, val spaceAdjust: Float)

class LaidLine(val fragments: List<PlacedFragment>, val baseline: Float)

object ParagraphLayout {
    private const val EPS = 0.001f

    fun textWidth(text: String, style: SpanStyle, font: PDFont): Float =
        font.getStringWidth(text) / 1000f * style.effectiveFontSize * style.horizontalScale + style.charSpacing * text.length

    private class Ch(val c: Char, val style: SpanStyle, val w: Float)

    /** One wrapped line: visible chars (trailing spaces removed) and whether justification applies to it. */
    private class Raw(val chars: List<Ch>, val hardEnd: Boolean)

    fun layout(
        spans: List<Span>, plan: FontPlan, left: Float, right: Float, firstLineIndent: Float,
        firstBaseline: Float, lineAdvance: Float, alignment: Alignment,
    ): List<LaidLine> {
        val all = ArrayList<Ch>()
        val widthCache = HashMap<Pair<SpanStyle, Char>, Float>()
        for (s in spans) for (c in s.text) {
            val w = if (c == '\n') 0f else widthCache.getOrPut(s.style to c) { textWidth(c.toString(), s.style, plan.fontFor(s.style)) }
            all += Ch(c, s.style, w)
        }
        if (all.isEmpty()) return emptyList()

        val raws = ArrayList<Raw>()
        fun avail() = right - left - (if (raws.isEmpty()) firstLineIndent else 0f)

        var line = ArrayList<Ch>()
        var lineW = 0f
        fun flush(hard: Boolean) {
            while (line.isNotEmpty() && line.last().c == ' ') line.removeAt(line.lastIndex)
            raws += Raw(line, hard)
            line = ArrayList(); lineW = 0f
        }

        var i = 0
        while (i <= all.size) {
            if (i == all.size) { flush(true); break }
            val ch = all[i]
            when {
                ch.c == '\n' -> { flush(true); i++ }
                ch.c == ' ' -> {
                    // Leading spaces on wrapped lines are dropped (a wrapped line follows a non-hard flush).
                    if (!(line.isEmpty() && raws.isNotEmpty() && !raws.last().hardEnd)) { line += ch; lineW += ch.w }
                    i++
                }
                else -> {
                    var j = i
                    var wordW = 0f
                    while (j < all.size && all[j].c != ' ' && all[j].c != '\n') { wordW += all[j].w; j++ }
                    if (lineW + wordW > avail() + EPS && line.any { it.c != ' ' }) flush(false)
                    if (line.all { it.c == ' ' } && wordW > avail() + EPS) {
                        // Break the word at the last character that fits (at least one per line).
                        var k = i
                        while (k < j) {
                            if (lineW + all[k].w > avail() + EPS && line.any { it.c != ' ' }) flush(false)
                            line += all[k]; lineW += all[k].w; k++
                        }
                    } else {
                        for (k in i until j) { line += all[k]; lineW += all[k].w }
                    }
                    i = j
                }
            }
        }

        return raws.mapIndexed { idx, raw -> place(raw, idx, idx == raws.lastIndex, plan, left, right, firstLineIndent, firstBaseline - idx * lineAdvance, alignment) }
    }

    private fun place(
        raw: Raw, idx: Int, last: Boolean, plan: FontPlan, left: Float, right: Float, indent: Float, baseline: Float, alignment: Alignment,
    ): LaidLine {
        if (raw.chars.isEmpty()) return LaidLine(emptyList(), baseline)
        // Group consecutive chars of the same style.
        val groups = ArrayList<Pair<SpanStyle, StringBuilder>>()
        for (c in raw.chars) {
            if (groups.isNotEmpty() && groups.last().first == c.style) groups.last().second.append(c.c)
            else groups += c.style to StringBuilder().append(c.c)
        }
        val widths = groups.map { (st, sb) -> textWidth(sb.toString(), st, plan.fontFor(st)) }
        val visible = widths.sum()
        val start = left + if (idx == 0) indent else 0f
        val avail = right - start
        val spaces = raw.chars.count { it.c == ' ' }
        var adjust = 0f
        if (alignment == Alignment.JUSTIFIED && !last && !raw.hardEnd && spaces > 0) adjust = maxOf(0f, (avail - visible) / spaces)
        var x = when (alignment) {
            Alignment.RIGHT -> right - visible
            Alignment.CENTER -> start + (avail - visible) / 2f
            else -> start
        }
        val frags = groups.mapIndexed { n, (st, sb) ->
            val text = sb.toString()
            PlacedFragment(text, st, x, widths[n], adjust).also { x += widths[n] + adjust * text.count { it == ' ' } }
        }
        return LaidLine(frags, baseline)
    }
}
