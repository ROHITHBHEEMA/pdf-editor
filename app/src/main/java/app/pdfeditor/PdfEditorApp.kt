package app.pdfeditor

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfEditorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
