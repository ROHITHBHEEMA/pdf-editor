package app.pdfeditor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.pdfeditor.flow.FontProvider
import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor
import com.tom_roush.pdfbox.pdmodel.font.PDPanoseClassification
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.io.FileInputStream
import java.io.IOException

const val AUTO_FONT_ID = "auto"

enum class FontSource { BUNDLED, SYSTEM, CUSTOM }

class FontOption(
    val id: String,
    val displayName: String,
    val source: FontSource,
)

// Supplies replacement fonts for text edits from three places: Liberation
// fonts bundled in assets, the device's /system/fonts, and user imports kept
// in filesDir. resolve embeds a chosen font into the open document; small
// fonts are embedded whole and cached so reuse dedups, large ones (CJK) are
// subset fresh per edit so pdfbox can finalize each subset on the next save.
class FontCatalog(private val appContext: Context) : FontProvider {

    private val customDir: File = File(appContext.filesDir, "fonts").apply { mkdirs() }

    private var cacheDoc: PDDocument? = null
    private val fullEmbedCache = HashMap<String, PDFont>()

    private val bundled: List<Entry> = buildBundled()
    private val system: List<Entry> by lazy { scanSystem() }

    private class Entry(
        val id: String,
        val displayName: String,
        val source: FontSource,
        val embedSubset: Boolean,
        val load: (PDDocument) -> PDFont,
    )

    fun options(): List<FontOption> {
        val all = ArrayList<Entry>(bundled.size + system.size + 8)
        all.addAll(bundled)
        all.addAll(system)
        all.addAll(scanCustom())
        return all.map { FontOption(it.id, it.displayName, it.source) }
    }

    override fun load(doc: PDDocument, id: String): PDFont? = resolve(doc, id)

    override fun matchId(original: PDFont?): String? = autoMatchId(original)

    override fun displayName(id: String): String = findEntry(id)?.displayName ?: id

    fun resolve(doc: PDDocument, id: String): PDFont? {
        if (doc !== cacheDoc) {
            cacheDoc = doc
            fullEmbedCache.clear()
        }
        val entry = findEntry(id) ?: return null
        return try {
            if (entry.embedSubset) entry.load(doc)
            else fullEmbedCache.getOrPut(entry.id) { entry.load(doc) }
        } catch (_: Exception) {
            null
        }
    }

    // Best catalog face for the original font. Exact metric-compatible name
    // aliases (Arial, Times, Courier, Calibri, Cambria) and an exact system
    // font by family are confident; Panose and flag bucketing are not.
    fun autoMatchId(original: PDFont?): String? = matchInternal(original)?.id

    // Only the high-confidence matches, for the prefer-confident edit policy.
    fun confidentMatchId(original: PDFont?): String? =
        matchInternal(original)?.takeIf { it.confident }?.id

    // Which matcher step decided, for the debug overlay.
    fun explainMatch(original: PDFont?): MatchExplain {
        val m = matchInternal(original)
        val name = (try {
            original?.fontDescriptor?.fontName
        } catch (_: Exception) {
            null
        }) ?: "(none)"
        return MatchExplain(
            name.substringAfterLast('+'), m?.step ?: "none",
            m?.id?.removePrefix("bundled:"), m?.confident ?: false, m?.detail,
        )
    }

    // Scale for the substitute's point size so its advance widths match the
    // original's. The matcher keeps the width profile but not absolute scale, so
    // a nearest clone can run a few percent wide and the text grows after an
    // edit; this pins it back. 1f for non-bundled faces or too little data.
    fun widthScale(original: PDFont?, id: String): Float {
        original ?: return 1f
        if (!id.startsWith("bundled:")) return 1f
        val cand = WIDTH_TABLE[id.removePrefix("bundled:").substringBeforeLast('-')] ?: return 1f
        var oSum = 0f
        var cSum = 0f
        var n = 0
        for (i in WIDTH_REF.indices) {
            val w = try {
                original.getStringWidth(WIDTH_REF[i].toString())
            } catch (_: Exception) {
                0f
            }
            if (w > 0f) { oSum += w; cSum += cand[i]; n++ }
        }
        if (n < 4 || cSum <= 0f) return 1f
        return (oSum / cSum).coerceIn(0.5f, 2f)
    }

    // A loadable typeface for the auto match so the inline editor can render the
    // fallback while typing, matching what the commit will embed.
    fun autoMatchFace(original: PDFont?): FaceSource? = autoMatchId(original)?.let { faceFor(it) }

    // Maps a catalog id to where its glyphs live, for previewing in Compose.
    // Null for .ttc collections, which the Compose font loader can't open.
    fun faceFor(id: String): FaceSource? = when {
        id.startsWith("bundled:") ->
            FaceSource.Asset("fonts/${id.substringAfter("bundled:")}.ttf")
        id.startsWith("system:") -> {
            val path = id.substringAfter("system:")
            if (path.endsWith(".ttc", true)) null else FaceSource.FileFace(File(path))
        }
        id.startsWith("custom:") -> {
            val name = id.substringAfter("custom:")
            if (name.endsWith(".ttc", true)) null else FaceSource.FileFace(File(customDir, name))
        }
        else -> null
    }

    sealed class FaceSource {
        class Asset(val path: String) : FaceSource()
        class FileFace(val file: File) : FaceSource()
    }

    // Why a given substitute was chosen, surfaced in the debug overlay.
    class MatchExplain(
        val original: String,
        val step: String,
        val match: String?,
        val confident: Boolean,
        val detail: String?,
    )

    private class Match(
        val id: String,
        val confident: Boolean,
        val step: String,
        val detail: String? = null,
    )

    private fun matchInternal(original: PDFont?): Match? {
        original ?: return null
        val desc = try {
            original.fontDescriptor
        } catch (_: Exception) {
            null
        }
        val lower = (desc?.fontName ?: "").substringAfterLast('+').lowercase()
        val family = normalizeFamily(desc?.fontName ?: "")
        val panose = try {
            desc?.panose?.panose
        } catch (_: Exception) {
            null
        }
        val bold = desc?.isForceBold == true || (desc?.fontWeight ?: 0f) >= 600f ||
            (panose?.weight ?: 0) >= 8 ||
            listOf("bold", "black", "heavy", "semibold").any { lower.contains(it) }
        val italic = desc?.isItalic == true || (desc?.italicAngle ?: 0f) != 0f ||
            lower.contains("italic") || lower.contains("oblique")

        texMatch(family, lower, bold, italic)?.let { return Match(it, true, "tex") }
        aliasFamily(family)?.let {
            return Match("bundled:$it-${styleSuffix(it, bold, italic)}", true, "alias")
        }
        systemMatch(family, bold, italic)?.let { return Match(it, true, "system") }
        widthMatch(original, desc, lower, panose)?.let { (fam, detail) ->
            return Match("bundled:$fam-${styleSuffix(fam, bold, italic)}", true, "width", detail)
        }
        val fam = bucketFamily(desc, lower, panose)
        return Match("bundled:$fam-${styleSuffix(fam, bold, italic)}", false, "panose")
    }

    // Strips the subset tag, splits camelCase, and drops style and foundry
    // tokens so "ABCDEF+TimesNewRomanPSMT" and "Times" both reduce to a family
    // key the alias table and system scan can compare. Width words are kept so
    // a condensed face never aliases onto a normal-width clone.
    private fun normalizeFamily(raw: String): String {
        val head = raw.substringAfterLast('+').substringBefore(',')
        val spaced = head.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        return spaced.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it !in FAMILY_NOISE }
            .joinToString("")
    }

    // Computer Modern and its descendants (Latin Modern, New Computer Modern,
    // Computer Modern Unicode) are the LaTeX and Typst defaults. Latin Modern is
    // the faithful continuation of Computer Modern, so it stands in for all of
    // them. Subset names are terse (CMR10, CMBX12, CMTI10), so match on the
    // telltale stems, by family substring or by raw-name prefix.
    private fun texMatch(family: String, lower: String, bold: Boolean, italic: Boolean): String? {
        fun has(stem: String) = family.contains(stem) || lower.startsWith(stem)
        val isTex = listOf(
            "computermodern", "latinmodern", "newcomputermodern", "newcm",
            "lmroman", "lmsans", "lmmono", "cmun",
            "cmr", "cmbx", "cmss", "cmtt", "cmti", "cmsl", "cmcsc", "cmmi",
            "cmbright", "cmbr",
        ).any { has(it) }
        if (!isTex) return null
        val fam = when {
            listOf("lmmono", "cmtt", "cmsltt", "cmitt", "cmtex", "newcmmono", "cmuntt", "cmuntb")
                .any { has(it) } -> "LatinModernMono"
            listOf("lmsans", "cmss", "newcmsans", "cmbright", "cmbr", "cmunss")
                .any { has(it) } -> "LatinModernSans"
            else -> "LatinModernRoman"
        }
        val texBold = bold || lower.contains("bold") ||
            listOf("cmbx", "cmssbx", "cmunbx").any { lower.startsWith(it) }
        val texItalic = italic ||
            listOf("italic", "oblique", "slant").any { lower.contains(it) } ||
            listOf("cmti", "cmsl", "cmmi", "cmunti", "cmunsl", "cmunit").any { lower.startsWith(it) }
        return "bundled:$fam-${styleSuffix(fam, texBold, texItalic)}"
    }

    private fun aliasFamily(family: String): String? {
        if (WIDTH_WORDS.any { family.contains(it) }) return null
        return when {
            family.contains("calibri") -> "Carlito"
            family.contains("cambria") -> "Caladea"
            family.contains("arial") || family.contains("helvetica") -> "LiberationSans"
            family.contains("times") -> "LiberationSerif"
            family.contains("courier") -> "LiberationMono"
            family.contains("georgia") -> "Gelasio"
            family.contains("segoe") -> "Selawik"
            family.contains("palatino") || family.contains("palladio") ||
                family.contains("antiqua") -> "P052"
            family.contains("bookman") -> "URWBookman"
            family.contains("avantgarde") || family.contains("centurygothic") -> "URWGothic"
            family.contains("schoolbook") -> "C059"
            family.contains("chancery") -> "Z003"
            // Symbol decodes to Unicode Greek and math, which a text serif covers.
            family == "symbol" -> "LiberationSerif"
            else -> null
        }
    }

    // An exact full system font beats a metric clone when the device has it.
    private fun systemMatch(family: String, bold: Boolean, italic: Boolean): String? {
        if (family.length < 3) return null
        var fallback: String? = null
        for (e in system) {
            val stem = e.id.substringAfterLast('/').substringBeforeLast('.')
            if (normalizeFamily(stem) != family) continue
            val low = stem.lowercase()
            val eb = low.contains("bold")
            val ei = low.contains("italic") || low.contains("oblique")
            if (eb == bold && ei == italic) return e.id
            if (fallback == null) fallback = e.id
        }
        return fallback
    }

    // Low-confidence fallback: Panose family/serif/proportion when present, else
    // descriptor flags and name hints, mapped onto the Liberation generics.
    private fun bucketFamily(
        desc: PDFontDescriptor?,
        lower: String,
        panose: PDPanoseClassification?,
    ): String {
        if (panose != null && panose.familyKind == 2) {
            if (panose.proportion == 9) return "LiberationMono"
            if (panose.serifStyle in 11..15) return "LiberationSans"
            if (panose.serifStyle in 2..10) return "LiberationSerif"
        }
        val mono = desc?.isFixedPitch == true ||
            listOf("mono", "courier", "consol").any { lower.contains(it) }
        if (mono) return "LiberationMono"
        val serif = desc?.isSerif == true ||
            listOf("times", "serif", "roman", "georgia", "minion", "garamond")
                .any { lower.contains(it) }
        return if (serif) "LiberationSerif" else "LiberationSans"
    }

    // Pre-Panose refinement: a subset embeds real advance widths even when its
    // name is gone, and the bundled clones are metric-compatible, so the closest
    // width profile is the best substitute. Per-char widths are mean-normalized
    // so weight and scale drop out. Confident only on a tight match that no
    // serif/mono signal contradicts; otherwise the Panose bucket decides.
    private fun widthMatch(
        original: PDFont,
        desc: PDFontDescriptor?,
        lower: String,
        panose: PDPanoseClassification?,
    ): Pair<String, String>? {
        val ow = FloatArray(WIDTH_REF.length)
        val has = BooleanArray(WIDTH_REF.length)
        var n = 0
        for (i in WIDTH_REF.indices) {
            val w = try {
                original.getStringWidth(WIDTH_REF[i].toString())
            } catch (_: Exception) {
                0f
            }
            if (w > 0f) { ow[i] = w; has[i] = true; n++ }
        }
        if (n < WIDTH_MIN_SHARED) return null
        var oMean = 0f
        for (i in WIDTH_REF.indices) if (has[i]) oMean += ow[i]
        oMean /= n
        if (oMean <= 0f) return null
        var best = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        var bestFam: String? = null
        for ((fam, cw) in WIDTH_TABLE) {
            var cMean = 0f
            for (i in WIDTH_REF.indices) if (has[i]) cMean += cw[i]
            cMean /= n
            if (cMean <= 0f) continue
            var d = 0f
            for (i in WIDTH_REF.indices) if (has[i]) d += kotlin.math.abs(ow[i] / oMean - cw[i] / cMean)
            d /= n
            if (d < best) {
                second = best; best = d; bestFam = fam
            } else if (d < second) {
                second = d
            }
        }
        val fam = bestFam ?: return null
        val hint = categoryHint(desc, lower, panose)
        if (best > WIDTH_THRESH || (hint != null && famCategory(fam) != hint)) return null
        return fam to "d=%.3f next %.3f".format(best, second)
    }

    // serif/sans/mono only when a flag, Panose, or name positively says so; null
    // means no firm signal, so it must not veto a width pick.
    private fun categoryHint(
        desc: PDFontDescriptor?,
        lower: String,
        panose: PDPanoseClassification?,
    ): String? {
        if (panose != null && panose.familyKind == 2) {
            if (panose.proportion == 9) return "mono"
            if (panose.serifStyle in 11..15) return "sans"
            if (panose.serifStyle in 2..10) return "serif"
        }
        if (desc?.isFixedPitch == true || listOf("mono", "courier", "consol").any { lower.contains(it) }) {
            return "mono"
        }
        if (desc?.isSerif == true ||
            listOf("times", "serif", "roman", "georgia", "minion", "garamond").any { lower.contains(it) }
        ) {
            return "serif"
        }
        return null
    }

    private fun famCategory(fam: String): String = when (fam) {
        "LiberationMono", "LatinModernMono" -> "mono"
        "LiberationSans", "Carlito", "URWGothic", "LatinModernSans" -> "sans"
        else -> "serif"
    }

    private fun styleSuffix(family: String, bold: Boolean, italic: Boolean): String =
        when (family) {
            "LatinModernMono" -> if (italic) "Italic" else "Regular"
            "Selawik" -> if (bold) "Bold" else "Regular"
            "Z003" -> "Regular"
            else -> when {
                bold && italic -> "BoldItalic"
                bold -> "Bold"
                italic -> "Italic"
                else -> "Regular"
            }
        }

    fun importFont(uri: Uri): Boolean {
        return try {
            val target = uniqueFile(sanitize(queryName(uri) ?: "font.ttf"))
            appContext.contentResolver.openInputStream(uri).use { input ->
                input ?: return false
                target.outputStream().use { input.copyTo(it) }
            }
            if (target.length() > 0) {
                true
            } else {
                target.delete()
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun findEntry(id: String): Entry? {
        bundled.firstOrNull { it.id == id }?.let { return it }
        system.firstOrNull { it.id == id }?.let { return it }
        return scanCustom().firstOrNull { it.id == id }
    }

    private fun buildBundled(): List<Entry> {
        val defs = listOf(
            "LiberationSans-Regular" to "Liberation Sans",
            "LiberationSans-Bold" to "Liberation Sans Bold",
            "LiberationSans-Italic" to "Liberation Sans Italic",
            "LiberationSans-BoldItalic" to "Liberation Sans Bold Italic",
            "LiberationSerif-Regular" to "Liberation Serif",
            "LiberationSerif-Bold" to "Liberation Serif Bold",
            "LiberationSerif-Italic" to "Liberation Serif Italic",
            "LiberationSerif-BoldItalic" to "Liberation Serif Bold Italic",
            "LiberationMono-Regular" to "Liberation Mono",
            "LiberationMono-Bold" to "Liberation Mono Bold",
            "LiberationMono-Italic" to "Liberation Mono Italic",
            "LiberationMono-BoldItalic" to "Liberation Mono Bold Italic",
            "Carlito-Regular" to "Carlito",
            "Carlito-Bold" to "Carlito Bold",
            "Carlito-Italic" to "Carlito Italic",
            "Carlito-BoldItalic" to "Carlito Bold Italic",
            "Caladea-Regular" to "Caladea",
            "Caladea-Bold" to "Caladea Bold",
            "Caladea-Italic" to "Caladea Italic",
            "Caladea-BoldItalic" to "Caladea Bold Italic",
            "LatinModernRoman-Regular" to "Latin Modern Roman",
            "LatinModernRoman-Bold" to "Latin Modern Roman Bold",
            "LatinModernRoman-Italic" to "Latin Modern Roman Italic",
            "LatinModernRoman-BoldItalic" to "Latin Modern Roman Bold Italic",
            "LatinModernSans-Regular" to "Latin Modern Sans",
            "LatinModernSans-Bold" to "Latin Modern Sans Bold",
            "LatinModernSans-Italic" to "Latin Modern Sans Italic",
            "LatinModernSans-BoldItalic" to "Latin Modern Sans Bold Italic",
            "LatinModernMono-Regular" to "Latin Modern Mono",
            "LatinModernMono-Italic" to "Latin Modern Mono Italic",
            "Gelasio-Regular" to "Gelasio",
            "Gelasio-Bold" to "Gelasio Bold",
            "Gelasio-Italic" to "Gelasio Italic",
            "Gelasio-BoldItalic" to "Gelasio Bold Italic",
            "Selawik-Regular" to "Selawik",
            "Selawik-Bold" to "Selawik Bold",
            "P052-Regular" to "URW Palladio",
            "P052-Bold" to "URW Palladio Bold",
            "P052-Italic" to "URW Palladio Italic",
            "P052-BoldItalic" to "URW Palladio Bold Italic",
            "URWBookman-Regular" to "URW Bookman",
            "URWBookman-Bold" to "URW Bookman Bold",
            "URWBookman-Italic" to "URW Bookman Italic",
            "URWBookman-BoldItalic" to "URW Bookman Bold Italic",
            "URWGothic-Regular" to "URW Gothic",
            "URWGothic-Bold" to "URW Gothic Bold",
            "URWGothic-Italic" to "URW Gothic Italic",
            "URWGothic-BoldItalic" to "URW Gothic Bold Italic",
            "C059-Regular" to "Century Schoolbook",
            "C059-Bold" to "Century Schoolbook Bold",
            "C059-Italic" to "Century Schoolbook Italic",
            "C059-BoldItalic" to "Century Schoolbook Bold Italic",
            "Z003-Regular" to "URW Chancery",
        )
        return defs.map { (file, label) ->
            Entry("bundled:$file", label, FontSource.BUNDLED, false) { doc ->
                appContext.assets.open("fonts/$file.ttf").use { PDType0Font.load(doc, it, false) }
            }
        }
    }

    private fun scanSystem(): List<Entry> {
        val files = File("/system/fonts").listFiles() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (f in files.sortedBy { it.name }) {
            val lower = f.name.lowercase()
            if (!lower.endsWith(".ttf") && !lower.endsWith(".ttc")) continue
            if (!systemAllowed(f.name)) continue
            val ttc = lower.endsWith(".ttc")
            val subset = ttc || f.length() > SUBSET_THRESHOLD
            out.add(
                Entry("system:${f.absolutePath}", prettyName(f.name), FontSource.SYSTEM, subset) { doc ->
                    if (ttc) loadFromCollection(doc, f) else loadFile(doc, f, subset)
                },
            )
        }
        return out
    }

    private fun scanCustom(): List<Entry> {
        val files = customDir.listFiles() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (f in files.sortedBy { it.name }) {
            val lower = f.name.lowercase()
            val ttc = lower.endsWith(".ttc")
            if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !ttc) continue
            val subset = ttc || f.length() > SUBSET_THRESHOLD
            out.add(
                Entry("custom:${f.name}", prettyName(f.name), FontSource.CUSTOM, subset) { doc ->
                    if (ttc) loadFromCollection(doc, f) else loadFile(doc, f, subset)
                },
            )
        }
        return out
    }

    private fun loadFile(doc: PDDocument, file: File, embedSubset: Boolean): PDFont =
        FileInputStream(file).use { PDType0Font.load(doc, it, embedSubset) }

    private fun loadFromCollection(doc: PDDocument, file: File): PDFont =
        TrueTypeCollection(file).use { coll ->
            var first: TrueTypeFont? = null
            coll.processAllFonts { ttf -> if (first == null) first = ttf }
            PDType0Font.load(doc, first ?: throw IOException("empty font collection"), true)
        }

    private fun systemAllowed(name: String): Boolean = SYSTEM_PREFIXES.any { name.startsWith(it) }

    private fun prettyName(fileName: String): String {
        val stem = fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .trim()
        return stem.ifEmpty { fileName }
    }

    private fun queryName(uri: Uri): String? = try {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (base.contains('.')) base else "$base.ttf"
    }

    private fun uniqueFile(name: String): File {
        var f = File(customDir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "ttf")
        var i = 1
        while (f.exists()) {
            f = File(customDir, "$stem-$i.$ext")
            i++
        }
        return f
    }

    companion object {
        private const val SUBSET_THRESHOLD = 4L * 1024 * 1024
        private const val WIDTH_THRESH = 0.06f
        private const val WIDTH_MIN_SHARED = 8
        private const val WIDTH_REF = "iltfraeonscmwuHINOMWg"
        private val WIDTH_TABLE: Map<String, FloatArray> = mapOf(
            "LiberationSans" to floatArrayOf(222f, 222f, 278f, 278f, 333f, 556f, 556f, 556f, 556f, 500f, 500f, 833f, 722f, 556f, 722f, 278f, 722f, 778f, 833f, 944f, 556f),
            "LiberationSerif" to floatArrayOf(278f, 278f, 278f, 333f, 333f, 444f, 444f, 500f, 500f, 389f, 444f, 778f, 722f, 500f, 722f, 333f, 722f, 722f, 889f, 944f, 500f),
            "LiberationMono" to floatArrayOf(600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f, 600f),
            "Carlito" to floatArrayOf(229f, 229f, 335f, 305f, 349f, 479f, 498f, 527f, 525f, 391f, 423f, 799f, 715f, 525f, 623f, 252f, 646f, 662f, 855f, 890f, 471f),
            "Caladea" to floatArrayOf(277f, 271f, 325f, 313f, 396f, 470f, 441f, 480f, 558f, 392f, 421f, 813f, 720f, 546f, 696f, 343f, 693f, 599f, 888f, 889f, 459f),
            "Gelasio" to floatArrayOf(293f, 286f, 345f, 325f, 410f, 504f, 483f, 539f, 591f, 432f, 454f, 881f, 737f, 575f, 815f, 390f, 767f, 744f, 927f, 976f, 509f),
            "P052" to floatArrayOf(291f, 291f, 326f, 333f, 395f, 500f, 479f, 546f, 582f, 424f, 444f, 883f, 834f, 603f, 832f, 337f, 831f, 786f, 946f, 1000f, 556f),
            "URWBookman" to floatArrayOf(300f, 300f, 380f, 320f, 440f, 580f, 520f, 560f, 660f, 520f, 520f, 940f, 780f, 680f, 800f, 340f, 740f, 800f, 920f, 960f, 540f),
            "URWGothic" to floatArrayOf(200f, 200f, 339f, 314f, 301f, 683f, 650f, 655f, 610f, 388f, 647f, 938f, 831f, 608f, 683f, 226f, 740f, 869f, 919f, 960f, 673f),
            "C059" to floatArrayOf(315f, 315f, 389f, 333f, 444f, 556f, 500f, 500f, 611f, 463f, 444f, 889f, 778f, 611f, 833f, 407f, 815f, 778f, 944f, 981f, 537f),
            "LatinModernRoman" to floatArrayOf(278f, 278f, 389f, 306f, 392f, 500f, 444f, 500f, 556f, 394f, 444f, 833f, 722f, 556f, 750f, 361f, 750f, 778f, 917f, 1028f, 500f),
            "LatinModernSans" to floatArrayOf(239f, 239f, 361f, 306f, 342f, 481f, 444f, 500f, 517f, 383f, 444f, 794f, 683f, 517f, 708f, 278f, 708f, 736f, 875f, 944f, 500f),
            "LatinModernMono" to floatArrayOf(525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f, 525f),
        )
        private val FAMILY_NOISE = setOf(
            "regular", "bold", "italic", "oblique", "light", "medium",
            "semibold", "demibold", "demi", "semi", "black", "heavy", "thin",
            "book", "roman", "mt", "ps", "psmt", "ms",
        )
        private val WIDTH_WORDS = listOf("narrow", "condensed", "cond", "expanded", "extended")
        private val SYSTEM_PREFIXES = listOf(
            "Roboto-", "RobotoFlex",
            "NotoSans-", "NotoSerif-", "NotoSansMono",
            "NotoSansCJK", "NotoSerifCJK", "NotoSansKR", "NotoSansJP",
            "NotoSansSC", "NotoSansTC", "NotoSansHK",
            "NotoNaskhArabic", "NotoSansArabic", "NotoSansHebrew",
            "NotoSansDevanagari", "NotoSansThai", "NotoSansBengali",
            "NotoSansTamil", "NotoSansKannada", "NotoSansTelugu",
            "DroidSans",
        )
    }
}
