package app.pdfeditor.flow

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Makes pages removed from the page tree unrecoverable from a full save. Outlines, destinations, structure
 * elements and form fields can still reference a removed page, and the save writes everything reachable, so:
 * the page dictionary is emptied (only /Type and /MediaBox stay, so dangling references stay valid), outline items
 * and named destinations pointing at it are removed, and form widgets that lived on it are dropped.
 */
internal object DeletedPages {

    fun scrub(doc: PDDocument, removed: List<PDPage>) {
        if (removed.isEmpty()) return
        val pages: MutableSet<COSDictionary> = Collections.newSetFromMap(IdentityHashMap())
        val widgets: MutableSet<COSDictionary> = Collections.newSetFromMap(IdentityHashMap())
        for (p in removed) {
            pages += p.cosObject
            (p.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.let { a ->
                for (i in 0 until a.size()) (a.getObject(i) as? COSDictionary)?.let { widgets += it }
            }
        }
        val catalog = doc.documentCatalog.cosObject
        (catalog.getDictionaryObject(COSName.OUTLINES) as? COSDictionary)?.let { pruneOutline(it, pages, doc) }
        (catalog.getDictionaryObject(COSName.DESTS) as? COSDictionary)?.let { dests ->
            for (k in dests.keySet().toList()) if (targetsRemoved(dests.getDictionaryObject(k), pages)) dests.removeItem(k)
        }
        ((catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.getDictionaryObject(COSName.DESTS) as? COSDictionary)
            ?.let { pruneNameTree(it, pages) }
        val acro = catalog.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary
        (acro?.getDictionaryObject(COSName.FIELDS) as? COSArray)?.let { pruneFields(it, widgets, pages) }
        for (p in removed) emptyPage(p)
    }

    private fun emptyPage(p: PDPage) {
        val dict = p.cosObject
        val media = p.mediaBox
        for (k in dict.keySet().toList()) dict.removeItem(k)
        dict.setItem(COSName.TYPE, COSName.PAGE)
        dict.setItem(COSName.MEDIA_BOX, media.cosArray)
    }

    /** True when a destination (array, or dictionary with /D) or a GoTo action targets a removed page. */
    private fun targetsRemoved(dest: COSBase?, pages: Set<COSDictionary>): Boolean {
        val d = if (dest is COSObject) dest.`object` else dest
        return when (d) {
            is COSArray -> d.size() > 0 && d.getObject(0).let { it is COSDictionary && it in pages }
            is COSDictionary -> targetsRemoved(d.getDictionaryObject(COSName.D), pages)
            else -> false
        }
    }

    private fun itemTargetsRemoved(item: COSDictionary, pages: Set<COSDictionary>, doc: PDDocument): Boolean {
        val dest = item.getDictionaryObject(COSName.DEST)
            ?: (item.getDictionaryObject(COSName.A) as? COSDictionary)
                ?.takeIf { it.getNameAsString(COSName.S) == "GoTo" }?.getDictionaryObject(COSName.D)
            ?: return false
        if (targetsRemoved(dest, pages)) return true
        // A named destination: resolve it through the catalog.
        val name = when (dest) { is COSString -> dest.string; is COSName -> dest.name; else -> return false }
        val resolved = try { doc.documentCatalog.findNamedDestinationPage(
            com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination(name),
        ) } catch (_: Exception) { null } ?: return false
        return resolved.page?.cosObject?.let { it in pages } == true
    }

    /**
     * Unlinks outline leaves that target a removed page; an item with children loses only its destination so
     * its children stay reachable. Counts are recomputed afterwards.
     */
    private fun pruneOutline(root: COSDictionary, pages: Set<COSDictionary>, doc: PDDocument) {
        fun walk(parent: COSDictionary) {
            var item = parent.getDictionaryObject(COSName.FIRST) as? COSDictionary
            while (item != null) {
                val next = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
                walk(item)
                if (itemTargetsRemoved(item, pages, doc)) {
                    if (item.getDictionaryObject(COSName.FIRST) != null) {
                        item.removeItem(COSName.DEST); item.removeItem(COSName.A)
                    } else unlink(parent, item)
                }
                item = next
            }
        }
        walk(root)
        recount(root, isRoot = true)
    }

    private fun unlink(parent: COSDictionary, item: COSDictionary) {
        val prev = item.getDictionaryObject(COSName.PREV) as? COSDictionary
        val next = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
        if (prev != null) { if (next != null) prev.setItem(COSName.NEXT, next) else prev.removeItem(COSName.NEXT) }
        else if (next != null) parent.setItem(COSName.FIRST, next) else parent.removeItem(COSName.FIRST)
        if (next != null) { if (prev != null) next.setItem(COSName.PREV, prev) else next.removeItem(COSName.PREV) }
        else if (prev != null) parent.setItem(COSName.LAST, prev) else parent.removeItem(COSName.LAST)
    }

    /** Sets /Count from the remaining children; returns the item's visible-descendant contribution when open. */
    private fun recount(node: COSDictionary, isRoot: Boolean = false): Int {
        var visible = 0
        var child = node.getDictionaryObject(COSName.FIRST) as? COSDictionary
        while (child != null) {
            visible += 1 + recount(child).coerceAtLeast(0)
            child = child.getDictionaryObject(COSName.NEXT) as? COSDictionary
        }
        val wasClosed = !isRoot && ((node.getDictionaryObject(COSName.COUNT) as? COSNumber)?.intValue() ?: 0) < 0
        val count = if (wasClosed) -visible else visible
        if (visible == 0 && !isRoot) node.removeItem(COSName.COUNT) else node.setInt(COSName.COUNT, count)
        return count
    }

    private fun pruneNameTree(node: COSDictionary, pages: Set<COSDictionary>) {
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { names ->
            var i = 0
            while (i + 1 < names.size()) {
                if (targetsRemoved(names.getObject(i + 1), pages)) { names.remove(i + 1); names.remove(i) } else i += 2
            }
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids ->
            for (i in 0 until kids.size()) (kids.getObject(i) as? COSDictionary)?.let { pruneNameTree(it, pages) }
        }
    }

    /** Removes widgets on removed pages and fields left with no widget. Returns whether [fields] is now empty. */
    private fun pruneFields(fields: COSArray, widgets: Set<COSDictionary>, pages: Set<COSDictionary>): Boolean {
        var i = 0
        while (i < fields.size()) {
            val f = fields.getObject(i) as? COSDictionary
            val drop = when {
                f == null -> false
                f.getDictionaryObject(COSName.KIDS) is COSArray ->
                    pruneFields(f.getDictionaryObject(COSName.KIDS) as COSArray, widgets, pages)
                else -> f in widgets || (f.getDictionaryObject(COSName.P) as? COSDictionary)?.let { it in pages } == true
            }
            if (drop) fields.remove(i) else i++
        }
        return fields.size() == 0
    }
}
