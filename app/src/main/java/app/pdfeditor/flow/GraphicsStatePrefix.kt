package app.pdfeditor.flow

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName

/** Reconstructs the graphics-state-setting operators in effect at a token index, so moved content keeps its look. */
object GraphicsStatePrefix {

    private val SIMPLE = setOf("w", "J", "j", "M", "d", "ri", "i")
    private const val FILL_CS = "fillCs"
    private const val FILL = "fill"
    private const val STROKE_CS = "strokeCs"
    private const val STROKE = "stroke"

    /** One tracked operator group (operands + operator) and the kind it replaces. */
    private class Entry(val kind: String, val group: List<Any>)

    /**
     * Operator groups (operands + operator) for w J j M d ri i gs and fill/stroke colour in effect at [index], in
     * source order. Groups inside closed q…Q pairs are dropped (ET does not reset graphics state); a later op of a
     * kind removes the earlier entry and is appended; each `gs` resource is kept individually.
     */
    fun at(tokens: List<Any>, index: Int): List<Any> = atAll(tokens, listOf(index)).getValue(index)

    /** [at] for every index in [indices], computed in one forward pass over [tokens]. */
    fun atAll(tokens: List<Any>, indices: Collection<Int>): Map<Int, List<Any>> {
        val wanted = indices.toSortedSet().toIntArray()
        val result = HashMap<Int, List<Any>>(wanted.size * 2)
        var state = ArrayList<Entry>()
        val stack = ArrayDeque<ArrayList<Entry>>()
        var next = 0
        var operandStart = 0

        fun snapshot(): List<Any> = ArrayList<Any>().also { out -> state.forEach { out.addAll(it.group) } }
        fun replace(vararg kinds: String) { state.removeAll { it.kind in kinds } }

        for (i in tokens.indices) {
            while (next < wanted.size && wanted[next] <= i) { result[wanted[next]] = snapshot(); next++ }
            if (next >= wanted.size) break
            val t = tokens[i]
            if (t !is Operator) continue
            val name = t.name
            fun group() = tokens.subList(operandStart, i + 1).toList() // allocated only for tracked ops
            when (name) {
                "q" -> stack.addLast(ArrayList(state))
                "Q" -> if (stack.isNotEmpty()) state = stack.removeLast()
                in SIMPLE -> { replace(name); state += Entry(name, group()) }
                "gs" -> {
                    val kind = "gs:" + ((tokens.getOrNull(i - 1) as? COSName)?.name ?: "")
                    replace(kind); state += Entry(kind, group())
                }
                "cs" -> { replace(FILL_CS, FILL); state += Entry(FILL_CS, group()) }
                "CS" -> { replace(STROKE_CS, STROKE); state += Entry(STROKE_CS, group()) }
                "sc", "scn" -> { replace(FILL); state += Entry(FILL, group()) }
                "SC", "SCN" -> { replace(STROKE); state += Entry(STROKE, group()) }
                "g", "rg", "k" -> { replace(FILL_CS, FILL); state += Entry(FILL, group()) }
                "G", "RG", "K" -> { replace(STROKE_CS, STROKE); state += Entry(STROKE, group()) }
            }
            operandStart = i + 1
        }
        // Indices at or past the end see the final state.
        while (next < wanted.size) { result[wanted[next]] = snapshot(); next++ }
        return result
    }
}
