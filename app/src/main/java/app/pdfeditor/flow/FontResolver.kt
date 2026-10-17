package app.pdfeditor.flow

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont

interface FontProvider {
    fun matchId(original: PDFont?): String?
    fun load(doc: PDDocument, id: String): PDFont?
    fun displayName(id: String): String
}

sealed class FontPlanResult {
    class Ok(val plan: FontPlan) : FontPlanResult()
    class Unsupported(val chars: String) : FontPlanResult()
}

object FontResolver {
    private const val DEFAULT_ID = "bundled:LiberationSerif-Regular"

    fun plan(
        doc: PDDocument,
        spans: List<Span>,
        provider: FontProvider,
        observed: ObservedEncodings = ObservedEncodings.build(doc),
    ): FontPlanResult {
        // A tab is drawn as a space; a newline is a line break, never a glyph.
        val texts = spans.map { it.text.replace('\t', ' ').replace("\n", "") }

        // Reuse the embedded fonts when they can draw everything.
        val own = HashMap<SpanStyle, PDFont>()
        var allOwn = true
        val missing = LinkedHashSet<String>()
        for ((i, span) in spans.withIndex()) {
            val f = span.style.font
            if (f == null) { allOwn = false; continue }
            val m = missingChars(f, texts[i], observed)
            if (m.isEmpty()) own.putIfAbsent(span.style, f) else { allOwn = false; missing.addAll(m) }
        }
        if (allOwn) return FontPlanResult.Ok(FontPlan(own, null, observed))

        // Substitute every span; load each substitute id once.
        val loaded = HashMap<String, PDFont?>()
        val subs = LinkedHashMap<SpanStyle, PDFont>()
        val names = LinkedHashSet<String>()
        val unsupported = LinkedHashSet<String>()
        for ((i, span) in spans.withIndex()) {
            val id = provider.matchId(span.style.font) ?: DEFAULT_ID
            val font = loaded.getOrPut(id) { provider.load(doc, id) }
            if (font == null) { unsupported.addAll(codePoints(texts[i])); continue }
            unsupported.addAll(missingChars(font, texts[i], observed))
            subs.putIfAbsent(span.style, font)
            names.add(provider.displayName(id))
        }
        if (unsupported.isNotEmpty()) return FontPlanResult.Unsupported(unsupported.joinToString(""))
        return FontPlanResult.Ok(FontPlan(subs, FontWarning(missing.joinToString(""), names.toList()), observed))
    }

    private fun codePoints(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        return out
    }

    /** Distinct code points (as strings) the font cannot draw, in order of first appearance. */
    private fun missingChars(font: PDFont, text: String, observed: ObservedEncodings): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        for (c in codePoints(text)) {
            if (!FontPlan.canDraw(font, c, observed)) out.add(c)
        }
        return out
    }
}
