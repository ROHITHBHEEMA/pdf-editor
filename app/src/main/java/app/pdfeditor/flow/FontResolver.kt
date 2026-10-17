package app.pdfeditor.flow

import app.pdfeditor.engine.NodeKind
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font

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

    fun plan(doc: PDDocument, spans: List<Span>, provider: FontProvider): FontPlanResult {
        val texts = spans.map { it.text.replace('\t', ' ') }
        val used = UsedChars(doc)

        // Reuse the embedded fonts when they can draw everything.
        val own = HashMap<SpanStyle, PDFont>()
        var allOwn = true
        val missing = LinkedHashSet<String>()
        for ((i, span) in spans.withIndex()) {
            val f = span.style.font
            if (f == null) { allOwn = false; continue }
            val m = missingChars(f, texts[i], used)
            if (m.isEmpty()) own.putIfAbsent(span.style, f) else { allOwn = false; missing.addAll(m) }
        }
        if (allOwn) return FontPlanResult.Ok(FontPlan(own, null))

        // Substitute every span; load each substitute id once.
        val loaded = HashMap<String, PDFont?>()
        val subs = LinkedHashMap<SpanStyle, PDFont>()
        val names = LinkedHashSet<String>()
        val unsupported = LinkedHashSet<String>()
        for ((i, span) in spans.withIndex()) {
            val id = provider.matchId(span.style.font) ?: DEFAULT_ID
            val font = loaded.getOrPut(id) { provider.load(doc, id) }
            if (font == null) { unsupported.addAll(codePoints(texts[i])); continue }
            unsupported.addAll(missingChars(font, texts[i], null))
            subs.putIfAbsent(span.style, font)
            names.add(provider.displayName(id))
        }
        if (unsupported.isNotEmpty()) return FontPlanResult.Unsupported(unsupported.joinToString(""))
        return FontPlanResult.Ok(FontPlan(subs, FontWarning(missing.joinToString(""), names.toList())))
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

    /**
     * Distinct code points (as strings) the font cannot draw, in order of first appearance.
     * Simple (non-Type0) embedded fonts often have a built-in encoding that [PDFont.encode]
     * rejects even for glyphs they carry, so for those a character also counts as drawable
     * when the document already shows it with that font (what a subset is built from).
     */
    private fun missingChars(font: PDFont, text: String, used: UsedChars?): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        for (c in codePoints(text)) {
            val ok = try { font.encode(c); true } catch (_: Exception) { false }
            if (!ok && !(used != null && font !is PDType0Font && used.has(font, c))) out.add(c)
        }
        return out
    }

    /** Characters the document already draws with each font, found lazily by analysing the pages once. */
    private class UsedChars(private val doc: PDDocument) {
        private val byFont: Map<String, Set<String>> by lazy {
            val m = HashMap<String, MutableSet<String>>()
            fun walk(n: app.pdfeditor.engine.DrawNode) {
                val f = n.font
                val t = n.text
                if (n.kind == NodeKind.TEXT && f != null && t != null) {
                    m.getOrPut(f.name ?: "") { HashSet() }.addAll(codePoints(t))
                }
                n.children.forEach(::walk)
            }
            try {
                DocumentAnalyzer.analyze(doc).pages.forEach { walk(it.parsed.root) }
            } catch (_: Exception) {
            }
            m
        }

        fun has(font: PDFont, c: String): Boolean = byFont[font.name ?: ""]?.contains(c) == true
    }
}
