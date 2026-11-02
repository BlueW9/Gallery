package com.openframe.gallery

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Offline text recognition (OCR) with Tesseract. Nothing leaves the phone, so the app
 * can keep having no INTERNET permission. The language files (ara + eng) are copied from
 * assets/tessdata/ to the app's private storage the first time they are needed.
 */
object Ocr {
    private const val LANGS = "ara+eng"

    private fun dataDir(ctx: Context): File {
        val tessdata = File(ctx.filesDir, "tessdata").apply { mkdirs() }
        for (name in listOf("ara.traineddata", "eng.traineddata")) {
            val out = File(tessdata, name)
            if (!out.exists() || out.length() == 0L) {
                ctx.assets.open("tessdata/$name").use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
        return ctx.filesDir // Tesseract expects the parent of the "tessdata" folder
    }

    /** Blocking and slow: call it from a background thread. */
    fun recognize(ctx: Context, bmp: Bitmap): String {
        val api = TessBaseAPI()
        try {
            if (!api.init(dataDir(ctx).absolutePath, LANGS)) return ""
            api.setImage(bmp)
            return (api.getUTF8Text() ?: "").trim()
        } finally {
            api.recycle()
        }
    }
}
