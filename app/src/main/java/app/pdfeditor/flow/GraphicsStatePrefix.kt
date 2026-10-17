package app.pdfeditor.flow

import com.tom_roush.pdfbox.contentstream.operator.Operator

/** Reconstructs the graphics-state-setting operators in effect at a token index, so moved content keeps its look. */
object GraphicsStatePrefix {

    private val SIMPLE = setOf("w", "J", "j", "M", "d", "ri", "i")

    private class State(
        val simple: LinkedHashMap<String, List<Any>> = LinkedHashMap(),
        val gs: MutableList<List<Any>> = ArrayList(),
        var fillCs: List<Any> = emptyList(),
        var fill: List<Any> = emptyList(),
        var strokeCs: List<Any> = emptyList(),
        var stroke: List<Any> = emptyList(),
    ) {
        fun copy() = State(LinkedHashMap(simple), ArrayList(gs), fillCs, fill, strokeCs, stroke)
    }

    /**
     * Operator groups (operands + operator) for w J j M d ri i gs and fill/stroke colour in effect at [index]:
     * groups inside closed q…Q pairs and inside other text objects are dropped; a later op of a kind replaces an
     * earlier one (each distinct `gs` is kept, in order, since each may set different parameters).
     */
    fun at(tokens: List<Any>, index: Int): List<Any> {
        var st = State()
        val stack = ArrayDeque<State>()
        var operandStart = 0
        var i = 0
        val end = minOf(index, tokens.size)
        while (i < end) {
            val t = tokens[i]
            if (t !is Operator) { i++; continue }
            val group = tokens.subList(operandStart, i + 1).toList()
            when (val name = t.name) {
                "q" -> stack.addLast(st.copy())
                "Q" -> if (stack.isNotEmpty()) st = stack.removeLast()
                "BT" -> {
                    // Skip a text object that doesn't contain index; inside the containing one, keep going.
                    var e = i + 1
                    while (e < tokens.size && !(tokens[e] is Operator && (tokens[e] as Operator).name == "ET")) e++
                    if (index > e) { i = e + 1; operandStart = i; continue }
                }
                in SIMPLE -> { st.simple.remove(name); st.simple[name] = group }
                "gs" -> { st.gs.removeAll { it.toString() == group.toString() }; st.gs += group }
                "cs" -> { st.fillCs = group; st.fill = emptyList() }
                "CS" -> { st.strokeCs = group; st.stroke = emptyList() }
                "sc", "scn" -> st.fill = group
                "SC", "SCN" -> st.stroke = group
                "g", "rg", "k" -> { st.fillCs = emptyList(); st.fill = group }
                "G", "RG", "K" -> { st.strokeCs = emptyList(); st.stroke = group }
            }
            i++
            operandStart = i
        }
        val out = ArrayList<Any>()
        st.simple.values.forEach { out.addAll(it) }
        st.gs.forEach { out.addAll(it) }
        out.addAll(st.strokeCs); out.addAll(st.stroke)
        out.addAll(st.fillCs); out.addAll(st.fill)
        return out
    }
}
