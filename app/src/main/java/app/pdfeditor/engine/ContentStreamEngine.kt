package app.pdfeditor.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColorSpace
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayInputStream

object ContentStreamEngine {

    fun parse(page: PDPage): ParsedPage {
        val tokens = readTokens(page)
        return Builder(tokens, page.resources).build()
    }

    private fun readTokens(page: PDPage): List<Any> {
        val parser = PDFStreamParser(page)
        parser.parse()
        return ArrayList<Any>(parser.tokens)
    }

    private class Builder(val tokens: List<Any>, val resources: PDResources?) {

        private var nextId = 1
        private val rootChildren = ArrayList<DrawNode>()
        private val leaves = ArrayList<DrawNode>()

        private val groupStack = ArrayDeque<GroupFrame>()
        private val ctmStack = ArrayDeque<Affine>().apply { addLast(Affine.IDENTITY) }

        private val operands = ArrayList<COSBase>()
        private var runStart = -1

        private var pathBounds: Bounds? = null
        private var pathStart = -1

        private var inText = false
        private var textStart = -1
        private var textBounds = Bounds.empty()
        private var textMatrix = Affine.IDENTITY
        private var textLineMatrix = Affine.IDENTITY
        private var fontSize = 0f
        private var fontName = ""
        private var currentFont: PDFont? = null
        private val fontCache = HashMap<String, PDFont?>()
        private var leading = 0f
        private var charSpacing = 0f
        private var wordSpacing = 0f
        private var hScale = 100f
        private var rise = 0f
        private var renderMode = 0
        private var fillTokens: List<Any> = emptyList()
        private var fillCsTokens: List<Any> = emptyList()
        private var hasTextInForms = false
        private val formTextCache = HashMap<String, Boolean>()
        private var runStartMatrix = Affine.IDENTITY
        private var runCtm = Affine.IDENTITY
        private val textPreview = StringBuilder()
        private val textFull = StringBuilder()
        private var textRuns = ArrayList<DrawNode>()
        private var runBounds = Bounds.empty()
        private val runText = StringBuilder()

        private var fillColor: Int? = null
        private var strokeColor: Int? = null
        private var fillModel: String? = null
        private var strokeModel: String? = null
        private var fillCs: PDColorSpace? = null
        private var strokeCs: PDColorSpace? = null
        private var fillCsLabel: String? = null
        private var strokeCsLabel: String? = null
        private val csCache = HashMap<String, PDColorSpace?>()

        // Graphics-state parts (besides the CTM) that q/Q save and restore.
        private class SavedState(
            val charSpacing: Float, val wordSpacing: Float, val hScale: Float, val leading: Float,
            val rise: Float, val renderMode: Int, val fontName: String, val fontSize: Float,
            val font: PDFont?, val fillTokens: List<Any>, val fillCsTokens: List<Any>,
        )

        private class GroupFrame(
            val openIndex: Int,
            val saved: SavedState,
            val children: MutableList<DrawNode> = ArrayList(),
        )

        private fun saveState() = SavedState(
            charSpacing, wordSpacing, hScale, leading, rise, renderMode,
            fontName, fontSize, currentFont, fillTokens, fillCsTokens,
        )

        private fun restoreState(s: SavedState) {
            charSpacing = s.charSpacing; wordSpacing = s.wordSpacing; hScale = s.hScale
            leading = s.leading; rise = s.rise; renderMode = s.renderMode
            fontName = s.fontName; fontSize = s.fontSize; currentFont = s.font
            fillTokens = s.fillTokens; fillCsTokens = s.fillCsTokens
        }

        fun build(): ParsedPage {
            for (i in tokens.indices) {
                when (val tok = tokens[i]) {
                    is Operator -> {
                        val start = if (operands.isEmpty()) i else runStart
                        handle(tok.name, i, start)
                        operands.clear()
                        runStart = -1
                    }
                    is COSBase -> {
                        if (operands.isEmpty()) runStart = i
                        operands.add(tok)
                    }
                }
            }
            while (groupStack.isNotEmpty()) closeGroup(tokens.lastIndex)

            val rootBounds = Bounds.empty()
            for (c in rootChildren) c.bounds?.let { rootBounds.includeBounds(it) }
            val root = DrawNode(
                id = 0,
                kind = NodeKind.GROUP,
                label = "Page",
                detail = "${rootChildren.size} top-level items",
                startIndex = 0,
                endIndex = tokens.lastIndex,
                bounds = if (rootBounds.isValid) rootBounds else null,
                colorArgb = null,
                raw = "",
                children = rootChildren,
            )
            return ParsedPage(tokens, root, leaves, hasTextInForms)
        }

        private fun currentChildren(): MutableList<DrawNode> =
            if (groupStack.isEmpty()) rootChildren else groupStack.last().children

        private fun ctm(): Affine = ctmStack.last()

        private fun handle(op: String, opIndex: Int, opStart: Int) {
            when (op) {
                "q" -> {
                    groupStack.addLast(GroupFrame(opIndex, saveState()))
                    ctmStack.addLast(ctm())
                }
                "Q" -> closeGroup(opIndex)
                "cm" -> if (operands.size >= 6) {
                    val old = ctmStack.removeLast()
                    ctmStack.addLast(affine(0).then(old))
                }
                "BT" -> {
                    inText = true
                    textStart = opIndex
                    textMatrix = Affine.IDENTITY
                    textLineMatrix = Affine.IDENTITY
                    textBounds = Bounds.empty()
                    textPreview.setLength(0)
                    textFull.setLength(0)
                    textRuns = ArrayList()
                }
                "ET" -> {
                    if (inText) emitText(opIndex)
                    inText = false
                }
                "Tf" -> if (operands.size >= 2) {
                    val fontCos = operands[0] as? COSName
                    fontName = fontCos?.name ?: fontName
                    fontSize = num(1)
                    currentFont = if (fontCos == null) null else resolveFont(fontCos)
                }
                "TL" -> if (operands.isNotEmpty()) leading = num(0)
                "Tc" -> if (operands.isNotEmpty()) charSpacing = num(0)
                "Tw" -> if (operands.isNotEmpty()) wordSpacing = num(0)
                "Tz" -> if (operands.isNotEmpty()) hScale = num(0)
                "Ts" -> if (operands.isNotEmpty()) rise = num(0)
                "Tr" -> if (operands.isNotEmpty()) renderMode = num(0).toInt()
                "Td" -> if (operands.size >= 2) moveTextLine(num(0), num(1))
                "TD" -> if (operands.size >= 2) {
                    leading = -num(1)
                    moveTextLine(num(0), num(1))
                }
                "Tm" -> if (operands.size >= 6) {
                    textLineMatrix = affine(0)
                    textMatrix = textLineMatrix
                }
                "T*" -> moveTextLine(0f, -leading)
                "Tj" -> {
                    beginRun()
                    showText(operands.lastOrNull() as? COSString)
                    emitRun(op, opStart, opIndex)
                }
                "TJ" -> {
                    beginRun()
                    showArray(operands.lastOrNull() as? COSArray)
                    emitRun(op, opStart, opIndex)
                }
                "'" -> {
                    moveTextLine(0f, -leading)
                    beginRun()
                    showText(operands.lastOrNull() as? COSString)
                    emitRun(op, opStart, opIndex)
                }
                "\"" -> if (operands.size >= 3) {
                    wordSpacing = num(0)
                    charSpacing = num(1)
                    moveTextLine(0f, -leading)
                    beginRun()
                    showText(operands[2] as? COSString)
                    emitRun(op, opStart, opIndex)
                }
                "rg" -> { fillColor = rgb(num(0), num(1), num(2)); fillModel = "RGB"; setFillTokens(opStart, opIndex) }
                "RG" -> { strokeColor = rgb(num(0), num(1), num(2)); strokeModel = "RGB" }
                "g" -> { fillColor = rgb(num(0), num(0), num(0)); fillModel = "Gray"; setFillTokens(opStart, opIndex) }
                "G" -> { strokeColor = rgb(num(0), num(0), num(0)); strokeModel = "Gray" }
                "k" -> { fillColor = cmyk(num(0), num(1), num(2), num(3)); fillModel = "CMYK"; setFillTokens(opStart, opIndex) }
                "K" -> { strokeColor = cmyk(num(0), num(1), num(2), num(3)); strokeModel = "CMYK" }
                "cs" -> {
                    val n = operands.lastOrNull() as? COSName
                    fillCs = colorSpace(n); fillCsLabel = describeColorSpace(n)
                    fillCsTokens = slice(opStart, opIndex)
                    fillTokens = fillCsTokens
                }
                "CS" -> {
                    val n = operands.lastOrNull() as? COSName
                    strokeCs = colorSpace(n); strokeCsLabel = describeColorSpace(n)
                }
                "sc", "scn" -> {
                    resolveCsColor(fillCs)?.let { fillColor = it }
                    fillModel = fillCsLabel
                    fillTokens = fillCsTokens + slice(opStart, opIndex)
                }
                "SC", "SCN" -> {
                    resolveCsColor(strokeCs)?.let { strokeColor = it }
                    strokeModel = strokeCsLabel
                }
                "m", "l" -> if (operands.size >= 2) {
                    beginPath(opStart)
                    addPoint(num(0), num(1))
                }
                "c" -> if (operands.size >= 6) {
                    beginPath(opStart)
                    addPoint(num(0), num(1)); addPoint(num(2), num(3)); addPoint(num(4), num(5))
                }
                "v", "y" -> if (operands.size >= 4) {
                    beginPath(opStart)
                    addPoint(num(0), num(1)); addPoint(num(2), num(3))
                }
                "re" -> if (operands.size >= 4) {
                    beginPath(opStart)
                    val x = num(0); val y = num(1); val w = num(2); val h = num(3)
                    addPoint(x, y); addPoint(x + w, y); addPoint(x + w, y + h); addPoint(x, y + h)
                }
                "f", "F", "f*", "S", "s", "B", "B*", "b", "b*" -> endPath(op, opIndex)
                "n" -> {
                    pathBounds = null
                    pathStart = -1
                }
                "Do" -> doXObject(operands.lastOrNull() as? COSName, opStart, opIndex)
            }
        }

        private fun closeGroup(closeIndex: Int) {
            if (groupStack.isEmpty()) return
            val frame = groupStack.removeLast()
            restoreState(frame.saved)
            if (ctmStack.size > 1) ctmStack.removeLast()
            val bounds = Bounds.empty()
            for (c in frame.children) c.bounds?.let { bounds.includeBounds(it) }
            val node = DrawNode(
                id = nextId++,
                kind = NodeKind.GROUP,
                label = "Group",
                detail = "${frame.children.size} items",
                startIndex = frame.openIndex,
                endIndex = closeIndex,
                bounds = if (bounds.isValid) bounds else null,
                colorArgb = null,
                raw = rawSlice(frame.openIndex, minOf(frame.openIndex + 1, closeIndex)),
                children = frame.children,
            )
            currentChildren().add(node)
        }

        private fun moveTextLine(tx: Float, ty: Float) {
            textLineMatrix = Affine.translate(tx, ty).then(textLineMatrix)
            textMatrix = textLineMatrix
        }

        private fun resolveFont(name: COSName): PDFont? =
            fontCache.getOrPut(name.name) {
                try {
                    resources?.getFont(name)
                } catch (_: Exception) {
                    null
                }
            }

        private fun colorSpace(name: COSName?): PDColorSpace? {
            if (name == null) return null
            return csCache.getOrPut(name.name) {
                try {
                    resources?.getColorSpace(name)
                } catch (_: Exception) {
                    null
                }
            }
        }

        // A human label for the color space the fill/stroke was set in, read from
        // the raw definition so spot colors keep their colorant name even though
        // pdfbox-android can't build them.
        private fun describeColorSpace(name: COSName?): String? {
            if (name == null) return null
            when (name.name) {
                "DeviceRGB" -> return "RGB"
                "DeviceGray" -> return "Gray"
                "DeviceCMYK" -> return "CMYK"
                "Pattern" -> return "Pattern"
            }
            val def = try {
                (resources?.cosObject?.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary)
                    ?.getDictionaryObject(name)
            } catch (_: Exception) {
                null
            }
            return when (def) {
                is COSArray -> {
                    val family = (def.getObject(0) as? COSName)?.name ?: "Color space"
                    if (family == "Separation" && def.size() > 1) {
                        (def.getObject(1) as? COSName)?.name?.let { "Separation ($it)" } ?: "Separation"
                    } else {
                        family
                    }
                }
                is COSName -> describeColorSpace(def) ?: def.name
                else -> name.name
            }
        }

        // Resolves an sc/scn color through its color space to RGB. The component
        // count must match: pdfbox-android falls back to DeviceRGB for color
        // spaces it cannot build (e.g. Separation), and feeding it the wrong
        // operand count yields garbage, so skip those and let bitmap sampling
        // supply the real color instead.
        private fun resolveCsColor(cs: PDColorSpace?): Int? {
            if (cs == null) return null
            val comps = ArrayList<Float>(operands.size)
            for (o in operands) if (o is COSNumber) comps.add(o.floatValue())
            if (comps.isEmpty() || comps.size != cs.numberOfComponents) return null
            return try {
                val c = cs.toRGB(comps.toFloatArray())
                rgb(c[0], c[1], c[2])
            } catch (_: Exception) {
                null
            }
        }

        private fun showText(s: COSString?) {
            if (s == null) return
            val bytes = s.bytes
            val font = currentFont
            var advance: Float
            val decoded = if (font != null) decodeWithFont(font, bytes) else null
            if (decoded != null) {
                appendText(decoded.text)
                advance = decoded.width
            } else {
                appendText(asciiFallback(bytes))
                advance = 0f
                for (byte in bytes) {
                    advance += glyphAdvance(fontSize * 0.5f, (byte.toInt() and 0xFF) == 32)
                }
            }
            val ascent = fontSize * 0.75f
            val descent = -fontSize * 0.25f
            val m = textMatrix.then(ctm())
            includeQuad(textBounds, m, advance, ascent, descent)
            includeQuad(runBounds, m, advance, ascent, descent)
            textMatrix = Affine.translate(advance, 0f).then(textMatrix)
        }

        private class Decoded(val text: String, val width: Float)

        private fun decodeWithFont(font: PDFont, bytes: ByteArray): Decoded? =
            try {
                val sb = StringBuilder()
                var width = 0f
                val input = ByteArrayInputStream(bytes)
                while (input.available() > 0) {
                    val before = input.available()
                    val code = font.readCode(input)
                    val u = try {
                        font.toUnicode(code)
                    } catch (_: Exception) {
                        null
                    }
                    if (u != null) sb.append(u)
                    else if (code in 32..126) sb.append(code.toChar())
                    else sb.append('\uFFFD') // undecodable glyph: keep a marker so the text isn't edited blind
                    val glyph = try {
                        font.getDisplacement(code).x * fontSize
                    } catch (_: Exception) {
                        fontSize * 0.5f
                    }
                    val singleByte = before - input.available() == 1
                    width += glyphAdvance(glyph, code == 32 && singleByte)
                }
                Decoded(sb.toString(), width)
            } catch (_: Exception) {
                null
            }

        // Text-space advance of one glyph: ((w * Tfs) + Tc + Tw if code 32) * Th.
        private fun glyphAdvance(glyphWidth: Float, isSpace: Boolean): Float =
            (glyphWidth + charSpacing + (if (isSpace) wordSpacing else 0f)) * (hScale / 100f)

        private fun slice(start: Int, end: Int): List<Any> =
            ArrayList<Any>(tokens.subList(start, minOf(end + 1, tokens.size)))

        private fun setFillTokens(opStart: Int, opIndex: Int) {
            fillCsTokens = emptyList()
            fillTokens = slice(opStart, opIndex)
        }

        private fun includeQuad(b: Bounds, m: Affine, w: Float, ascent: Float, descent: Float) {
            b.include(m.mapX(0f, descent), m.mapY(0f, descent))
            b.include(m.mapX(w, descent), m.mapY(w, descent))
            b.include(m.mapX(w, ascent), m.mapY(w, ascent))
            b.include(m.mapX(0f, ascent), m.mapY(0f, ascent))
        }

        private fun showArray(a: COSArray?) {
            if (a == null) return
            for (i in 0 until a.size()) {
                when (val el = a.getObject(i)) {
                    is COSString -> showText(el)
                    is COSNumber -> {
                        val tx = -el.floatValue() / 1000f * fontSize * (hScale / 100f)
                        if (tx > 0.2f * fontSize) appendText(" ")
                        textMatrix = Affine.translate(tx, 0f).then(textMatrix)
                    }
                }
            }
        }

        private fun beginRun() {
            runStartMatrix = textMatrix
            runCtm = ctm()
            runBounds = Bounds.empty()
            runText.setLength(0)
        }

        private fun emitRun(op: String, opStart: Int, opIndex: Int) {
            if (!runBounds.isValid) return
            val preview = runText.toString().trim().take(40)
            val label = if (preview.isEmpty()) "Text" else "Text “$preview”"
            val size = if (fontSize > 0f) "  ${fontSize.toInt()}pt" else ""
            textRuns.add(
                DrawNode(
                    id = nextId++,
                    kind = NodeKind.TEXT,
                    label = label,
                    detail = "$op  ${(fontName + size).trim()}".trim(),
                    startIndex = opStart,
                    endIndex = opIndex,
                    bounds = runBounds,
                    colorArgb = fillColor,
                    raw = rawSlice(opStart, opIndex),
                    children = emptyList(),
                    text = runText.toString(),
                    font = currentFont,
                    fontResourceName = fontName,
                    fontSize = fontSize,
                    colorSpace = fillModel,
                    run = buildRunInfo(op),
                ),
            )
        }

        private fun buildRunInfo(op: String): TextRunInfo {
            val start = runStartMatrix.then(runCtm)
            val end = textMatrix.then(runCtm)
            val vScale = Math.hypot(start.c.toDouble(), start.d.toDouble()).toFloat()
            val tol = 1e-3f * maxOf(vScale, 1e-6f)
            return TextRunInfo(
                fontResourceName = fontName,
                font = currentFont,
                fontSize = fontSize,
                textMatrix = runStartMatrix,
                ctm = runCtm,
                state = TextState(charSpacing, wordSpacing, hScale, leading, rise, renderMode),
                fillColorTokens = fillTokens,
                originX = start.mapX(0f, rise),
                originY = start.mapY(0f, rise),
                endX = end.mapX(0f, rise),
                effectiveFontSize = fontSize * vScale,
                isUpright = Math.abs(start.b) <= tol && Math.abs(start.c) <= tol && start.d > 0f,
                text = runText.toString(),
                showOperator = op,
            )
        }

        private fun emitText(endIndex: Int) {
            if (!textBounds.isValid) return
            val preview = textPreview.toString().trim()
            val label = if (preview.isEmpty()) "Text" else "Text “$preview”"
            val size = if (fontSize > 0f) "  ${fontSize.toInt()}pt" else ""
            addLeaf(
                DrawNode(
                    id = nextId++,
                    kind = NodeKind.TEXT,
                    label = label,
                    detail = (fontName + size).trim(),
                    startIndex = textStart,
                    endIndex = endIndex,
                    bounds = textBounds,
                    colorArgb = fillColor,
                    raw = rawSlice(textStart, endIndex),
                    children = textRuns,
                    text = textFull.toString(),
                    ctm = ctm(),
                    colorSpace = fillModel,
                ),
            )
        }

        private fun beginPath(start: Int) {
            if (pathBounds == null) {
                pathBounds = Bounds.empty()
                pathStart = start
            }
        }

        private fun addPoint(x: Float, y: Float) {
            pathBounds?.include(ctm().mapX(x, y), ctm().mapY(x, y))
        }

        private fun endPath(op: String, endIndex: Int) {
            val b = pathBounds
            if (b != null && b.isValid) {
                val stroked = op == "S" || op == "s"
                val label = when {
                    op == "S" || op == "s" -> "Path stroke"
                    op.startsWith("B") || op.startsWith("b") -> "Path fill+stroke"
                    else -> "Path fill"
                }
                addLeaf(
                    DrawNode(
                        id = nextId++,
                        kind = NodeKind.PATH,
                        label = label,
                        detail = "${b.width.toInt()}×${b.height.toInt()}",
                        startIndex = pathStart,
                        endIndex = endIndex,
                        bounds = b,
                        colorArgb = if (stroked) strokeColor else fillColor,
                        raw = rawSlice(pathStart, endIndex),
                        children = emptyList(),
                        ctm = ctm(),
                        colorSpace = if (stroked) strokeModel else fillModel,
                    ),
                )
            }
            pathBounds = null
            pathStart = -1
        }

        private fun doXObject(name: COSName?, start: Int, endIndex: Int) {
            if (name == null) return
            val n = name.name
            var kind = NodeKind.IMAGE
            var label = "Image"
            var detail = n
            var bounds = unitSquare()
            try {
                when (val x = resources?.getXObject(name)) {
                    is PDImageXObject -> detail = "$n  ${x.width}×${x.height}"
                    is PDFormXObject -> {
                        label = "Form"
                        formBounds(x)?.let { bounds = it }
                        if (!hasTextInForms && formTextCache.getOrPut(n) { formHasText(x) }) hasTextInForms = true
                    }
                    else -> {}
                }
            } catch (_: Exception) {
            }
            addLeaf(
                DrawNode(
                    id = nextId++,
                    kind = kind,
                    label = label,
                    detail = detail,
                    startIndex = start,
                    endIndex = endIndex,
                    bounds = bounds,
                    colorArgb = null,
                    raw = rawSlice(start, endIndex),
                    children = emptyList(),
                    ctm = ctm(),
                ),
            )
        }

        private fun formHasText(form: PDFormXObject): Boolean = try {
            val parser = PDFStreamParser(form)
            parser.parse()
            parser.tokens.any { it is Operator && (it.name == "Tj" || it.name == "TJ" || it.name == "'" || it.name == "\"") }
        } catch (_: Exception) {
            false
        }

        private fun unitSquare(): Bounds {
            val b = Bounds.empty()
            val m = ctm()
            b.include(m.mapX(0f, 0f), m.mapY(0f, 0f))
            b.include(m.mapX(1f, 0f), m.mapY(1f, 0f))
            b.include(m.mapX(1f, 1f), m.mapY(1f, 1f))
            b.include(m.mapX(0f, 1f), m.mapY(0f, 1f))
            return b
        }

        private fun formBounds(form: PDFormXObject): Bounds? {
            val r = form.bBox ?: return null
            val m = ctm()
            val b = Bounds.empty()
            val xs = floatArrayOf(r.lowerLeftX, r.upperRightX)
            val ys = floatArrayOf(r.lowerLeftY, r.upperRightY)
            for (x in xs) for (y in ys) b.include(m.mapX(x, y), m.mapY(x, y))
            return if (b.isValid) b else null
        }

        private fun addLeaf(node: DrawNode) {
            currentChildren().add(node)
            leaves.add(node)
        }

        private fun appendText(text: String) {
            if (text.isEmpty()) return
            runText.append(text)
            textFull.append(text)
            if (textPreview.length < 40) {
                val room = 40 - textPreview.length
                textPreview.append(if (text.length > room) text.substring(0, room) else text)
            }
        }

        private fun asciiFallback(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (byte in bytes) {
                val v = byte.toInt() and 0xFF
                if (v in 32..126) sb.append(v.toChar())
            }
            return sb.toString()
        }

        private fun num(i: Int): Float = (operands.getOrNull(i) as? COSNumber)?.floatValue() ?: 0f

        private fun affine(start: Int): Affine =
            Affine(num(start), num(start + 1), num(start + 2), num(start + 3), num(start + 4), num(start + 5))

        private fun rawSlice(start: Int, end: Int): String {
            val sb = StringBuilder()
            var i = start
            while (i <= end && i < tokens.size) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(cosToString(tokens[i]))
                if (sb.length > 220) {
                    sb.append(" …")
                    break
                }
                i++
            }
            return sb.toString()
        }
    }

    private fun cosToString(o: Any?): String = when (o) {
        is COSName -> "/" + o.name
        is COSString -> "(" + asciiOf(o.bytes) + ")"
        is COSArray -> "[" + (0 until o.size()).joinToString(" ") { cosToString(o.getObject(it)) } + "]"
        is COSNumber -> formatNumber(o.floatValue())
        is COSBoolean -> o.value.toString()
        is Operator -> o.name
        else -> o?.toString() ?: ""
    }

    private fun asciiOf(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            sb.append(if (v in 32..126) v.toChar() else '·')
            if (sb.length > 32) {
                sb.append('…')
                break
            }
        }
        return sb.toString()
    }

    private fun formatNumber(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.2f", v)

    private fun rgb(r: Float, g: Float, b: Float): Int {
        val ri = (r * 255f).toInt().coerceIn(0, 255)
        val gi = (g * 255f).toInt().coerceIn(0, 255)
        val bi = (b * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun cmyk(c: Float, m: Float, y: Float, k: Float): Int =
        rgb((1f - c) * (1f - k), (1f - m) * (1f - k), (1f - y) * (1f - k))
}
