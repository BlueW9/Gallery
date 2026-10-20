package com.openframe.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Edits are stored as a list of operations with coordinates as fractions (0..1) of the picture
 * as it looked at that moment. That way the same list can be shown on a small preview and then
 * replayed on the full-size photo when saving.
 */
sealed class Op
data class CropOp(val l: Float, val t: Float, val r: Float, val b: Float) : Op()
data class BlurOp(val l: Float, val t: Float, val r: Float, val b: Float, val strength: Int) : Op()

object ImageOps {

    /** Decodes the photo upright (EXIF rotation applied), limited to [maxPixels] pixels in total. */
    fun decode(resolver: ContentResolver, uri: Uri, maxPixels: Long): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            val pixels = w.toLong() * h
            if (pixels > maxPixels) {
                val s = sqrt(maxPixels.toDouble() / pixels.toDouble())
                decoder.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
            }
        }
    }

    /** Applies the operations. The [src] bitmap itself is never modified. */
    fun render(src: Bitmap, ops: List<Op>): Bitmap {
        var bmp = src
        for (op in ops) {
            when (op) {
                is CropOp -> {
                    val r = rect(op.l, op.t, op.r, op.b, bmp.width, bmp.height)
                    bmp = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
                }
                is BlurOp -> {
                    if (bmp === src || !bmp.isMutable) {
                        bmp = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: bmp
                    }
                    blur(bmp, rect(op.l, op.t, op.r, op.b, bmp.width, bmp.height), op.strength)
                }
            }
        }
        return bmp
    }

    /** Compresses to bytes first, so the original file is never touched if encoding fails. */
    fun encodeBytes(bmp: Bitmap, mime: String): ByteArray? {
        val out = ByteArrayOutputStream()
        val ok = when (mime) {
            "image/png" -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            "image/webp" -> bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            else -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        return if (ok) out.toByteArray() else null
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float, w: Int, h: Int): Rect {
        val x1 = (l * w).roundToInt().coerceIn(0, w - 1)
        val y1 = (t * h).roundToInt().coerceIn(0, h - 1)
        val x2 = (r * w).roundToInt().coerceIn(x1 + 1, w)
        val y2 = (b * h).roundToInt().coerceIn(y1 + 1, h)
        return Rect(x1, y1, x2, y2)
    }

    /**
     * Strong blur of one rectangle: the area is shrunk to a handful of cells, smoothed, and
     * stretched back. Fine detail (letters, faces) cannot be recovered from the result.
     * strength 0 = light, 100 = very strong.
     */
    private fun blur(bmp: Bitmap, r: Rect, strength: Int) {
        val w = r.width()
        val h = r.height()
        if (w < 2 || h < 2) return
        val region = Bitmap.createBitmap(bmp, r.left, r.top, w, h)
        val cells = 24f - 21f * (strength.coerceIn(0, 100) / 100f)
        val k = max(1f, min(w, h) / cells)
        val sw = max(2, (w / k).roundToInt())
        val sh = max(2, (h / k).roundToInt())
        var small = Bitmap.createScaledBitmap(region, sw, sh, true)
        small = boxBlur(small, 2)
        val big = smoothUp(small, w, h)
        Canvas(bmp).drawBitmap(big, r.left.toFloat(), r.top.toFloat(), null)
    }

    private fun smoothUp(small: Bitmap, w: Int, h: Int): Bitmap {
        var cur = small
        while (cur.width < w || cur.height < h) {
            cur = Bitmap.createScaledBitmap(cur, min(w, cur.width * 2), min(h, cur.height * 2), true)
        }
        if (cur.width != w || cur.height != h) cur = Bitmap.createScaledBitmap(cur, w, h, true)
        return cur
    }

    private fun boxBlur(bmp: Bitmap, passes: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        var src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        var dst = IntArray(w * h)
        repeat(passes) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var a = 0
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy < 0 || yy >= h) continue
                        for (dx in -1..1) {
                            val xx = x + dx
                            if (xx < 0 || xx >= w) continue
                            val p = src[yy * w + xx]
                            a += p ushr 24
                            r += (p shr 16) and 255
                            g += (p shr 8) and 255
                            b += p and 255
                            n++
                        }
                    }
                    dst[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            val t = src
            src = dst
            dst = t
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(src, 0, w, 0, 0, w, h)
        return out
    }
}
