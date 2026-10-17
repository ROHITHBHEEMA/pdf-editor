package app.pdfeditor.flow

/** Maps the styles of the old spans onto edited plain text, using a code-point level diff. */
object StyleMapper {
    private const val DP_LIMIT = 4_000_000L

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
        if (oldCps.isEmpty()) return emptyList()
        val newCps = newText.codePoints().toArray()
        val n = oldCps.size
        val m = newCps.size

        // keptFrom[j] = index in old of the character kept for new[j], or -1 if inserted.
        val keptFrom = IntArray(m) { -1 }
        if (n.toLong() * m.toLong() > DP_LIMIT) {
            var p = 0
            while (p < n && p < m && oldCps[p] == newCps[p]) { keptFrom[p] = p; p++ }
            var s = 0
            while (s < n - p && s < m - p && oldCps[n - 1 - s] == newCps[m - 1 - s]) {
                keptFrom[m - 1 - s] = n - 1 - s; s++
            }
        } else {
            val w = m + 1
            val dp = IntArray((n + 1) * w) // dp[i*w+j] = LCS of old[i..], new[j..]
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                dp[i * w + j] = if (oldCps[i] == newCps[j]) dp[(i + 1) * w + j + 1] + 1
                else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                if (oldCps[i] == newCps[j]) { keptFrom[j] = i; i++; j++ }
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
