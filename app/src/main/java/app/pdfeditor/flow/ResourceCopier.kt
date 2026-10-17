package app.pdfeditor.flow

import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont

/** Makes resources referenced by moved or new content available in a target page's resource dictionary. */
object ResourceCopier {

    /**
     * Ensures `name` from `source` (category = Font/XObject/ExtGState/ColorSpace/Pattern/Shading) exists in `target`;
     * returns the name to use there. When `target` binds `name` to a different object, the source object is reused
     * under any name `target` already has for it, or bound under a fresh name. A name `source` doesn't define is
     * returned unchanged.
     */
    fun ensure(target: PDResources, source: PDResources, category: COSName, name: COSName): COSName {
        val sourceDict = source.cosObject.getDictionaryObject(category) as? COSDictionary ?: return name
        val item = sourceDict.getItem(name) ?: return name // keep indirect references shared
        return bind(target, category, name, item)
    }

    /** Returns the name under which `target` holds `font`, adding it under a fresh name when absent. */
    fun ensureFont(target: PDResources, font: PDFont): COSName = bind(target, COSName.FONT, COSName.getPDFName("F1"), font.cosObject)

    private fun deref(o: COSBase?): COSBase? = if (o is COSObject) o.getObject() else o

    private fun bind(target: PDResources, category: COSName, preferred: COSName, item: COSBase): COSName {
        val want = deref(item)
        val dict = target.cosObject.getDictionaryObject(category) as? COSDictionary
            ?: COSDictionary().also { target.cosObject.setItem(category, it) }
        val existing = dict.getItem(preferred)
        if (existing != null && deref(existing) === want) return preferred
        // Already present under another name?
        for (k in dict.keySet()) if (deref(dict.getItem(k)) === want) return k
        if (existing == null) {
            dict.setItem(preferred, item)
            return preferred
        }
        var n = 1
        var fresh: COSName
        do { fresh = COSName.getPDFName("${preferred.name}_${n++}") } while (dict.containsKey(fresh))
        dict.setItem(fresh, item)
        return fresh
    }
}
