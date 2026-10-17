package app.pdfeditor.testing

import app.pdfeditor.flow.FontProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File

/** Maps base-font families to bundled assets, loading from src/main/assets/fonts (cwd = app/). */
object TestFontProvider : FontProvider {
    override fun matchId(original: PDFont?): String? {
        val raw = try { original?.fontDescriptor?.fontName ?: original?.name } catch (_: Exception) { null } ?: return null
        val n = raw.substringAfterLast('+').lowercase()
        val family = when {
            n.contains("georgia") -> "Gelasio"
            n.contains("times") -> "LiberationSerif"
            n.contains("helvetica") || n.contains("arial") -> "LiberationSans"
            n.contains("courier") -> "LiberationMono"
            else -> return null
        }
        val bold = n.contains("bold")
        val italic = n.contains("italic") || n.contains("oblique")
        val style = when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Regular" }
        return "bundled:$family-$style"
    }

    override fun load(doc: PDDocument, id: String): PDFont? {
        val f = File("src/main/assets/fonts/${id.removePrefix("bundled:")}.ttf")
        if (!f.exists()) return null
        return f.inputStream().use { PDType0Font.load(doc, it, false) }
    }

    override fun displayName(id: String): String {
        val file = id.removePrefix("bundled:")
        val fam = file.substringBeforeLast('-')
        val style = file.substringAfterLast('-', "Regular")
        val famName = when (fam) {
            "Gelasio" -> "Gelasio"
            "LiberationSerif" -> "Liberation Serif"
            "LiberationSans" -> "Liberation Sans"
            "LiberationMono" -> "Liberation Mono"
            else -> fam
        }
        val suffix = when (style) { "Bold" -> " Bold"; "Italic" -> " Italic"; "BoldItalic" -> " Bold Italic"; else -> "" }
        return famName + suffix
    }
}
