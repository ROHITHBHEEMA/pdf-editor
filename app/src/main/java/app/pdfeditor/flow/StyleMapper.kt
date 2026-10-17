package app.pdfeditor.flow

/**
 * Maps the styles of the old spans onto edited plain text, using a code-point level diff.
 * Callers must pass the paragraph's original spans when the edited spans are empty: non-empty
 * [newText] with no styled old characters throws IllegalArgumentException.
 */
object StyleMapper {
    private const val DP_LIMIT = 4_000_000L
    private const val STRIP_THRESHOLD = 65_536L

    fun apply(old: List<Span>, newText: String): List<Span> {
        if (newText.isEmpty()) return emptyList()
        val oldCps = ArrayList<Int>()
        val oldStyles = ArrayList<SpanStyle>()
        for (span in old) {
            var i = 0
            while (i < span.text.length) {
                val cp = span.text.codePointAt(i)
                oldCps.add(cp)
                oldStyles.add(span.style)
                i += Character.charCount(cp)
            }
        }
        require(oldCps.isNotEmpty()) { "StyleMapper needs at least one styled character to style new text" }
        val newCps = newText.codePoints().toArray()
        val n = oldCps.size
        val m = newCps.size

        // keptFrom[j] = index in old of the character kept for new[j], or -1 if inserted.
        val keptFrom = IntArray(m) { -1 }
        // Greedy prefix/suffix stripping can pick a worse alignment than the full LCS (it may claim a
        // space that belongs elsewhere), so only strip when the full table would be large.
        val strip = n.toLong() * m.toLong() > STRIP_THRESHOLD
        var p = 0
        while (strip && p < n && p < m && oldCps[p] == newCps[p]) { keptFrom[p] = p; p++ }
        var s = 0
        while (strip && s < n - p && s < m - p && oldCps[n - 1 - s] == newCps[m - 1 - s]) {
            keptFrom[m - 1 - s] = n - 1 - s; s++
        }
        val on = n - p - s // middle lengths
        val mn = m - p - s
        if (on > 0 && mn > 0 && on.toLong() * mn.toLong() <= DP_LIMIT) {
            val w = mn + 1
            val dp = IntArray((on + 1) * w) // dp[i*w+j] = LCS of mid-old[i..], mid-new[j..]
            for (i in on - 1 downTo 0) for (j in mn - 1 downTo 0) {
                dp[i * w + j] = if (oldCps[p + i] == newCps[p + j]) dp[(i + 1) * w + j + 1] + 1
                else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
            }
            var i = 0
            var j = 0
            while (i < on && j < mn) {
                if (oldCps[p + i] == newCps[p + j]) { keptFrom[p + j] = p + i; i++; j++ }
                else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) i++
                else j++
            }
        }

        val result = ArrayList<Span>()
        val sb = StringBuilder()
        var current: SpanStyle? = null
        var last = oldStyles[0]
        for (j in 0 until m) {
            val style = if (keptFrom[j] >= 0) oldStyles[keptFrom[j]] else last
            last = style
            if (current != null && style != current) {
                result.add(Span(sb.toString(), current)); sb.setLength(0)
            }
            if (current == null || style != current) current = style
            sb.appendCodePoint(newCps[j])
        }
        result.add(Span(sb.toString(), current!!))
        return result
    }
}
