package com.mediavault.feature.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class ScanPage(
    val id: Long,
    val file: File,
    /** TL, TR, BR, BL in 0..1 of the (EXIF- and user-rotated) source image. */
    val corners: List<Pt>,
    val rotation: Int = 0,
    val filter: ScanFilter = ScanFilter.MAGIC,
    val brightness: Int = 0,
    val contrast: Float = 1f,
)

object PageRenderer {

    /** Decodes with sub-sampling to roughly [maxEdge] px, applying EXIF orientation plus the user's [rotation]. */
    fun decode(file: File, maxEdge: Int, rotation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val exif = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val total = (exif + rotation) % 360
        if (total == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(total.toFloat()) }, true)
    }

    /** Perspective-corrects the quad to a flat rectangle (output long edge <= [maxEdge]). */
    fun warp(src: Bitmap, corners: List<Pt>, maxEdge: Int): Bitmap {
        val quad = if (corners.size == 4 && DocDetect.area(corners) >= 0.01f) corners else DocDetect.fullFrame()
        val sw = src.width.toFloat(); val sh = src.height.toFloat()
        val p = quad.map { PointF(it.x * sw, it.y * sh) }
        fun d(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)
        var outW = max(d(p[0], p[1]), d(p[3], p[2]))
        var outH = max(d(p[0], p[3]), d(p[1], p[2]))
        val s = min(1f, maxEdge / max(outW, outH))
        outW = max(1f, outW * s); outH = max(1f, outH * s)
        val srcPts = floatArrayOf(p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y)
        val dstPts = floatArrayOf(0f, 0f, outW, 0f, outW, outH, 0f, outH)
        val m = Matrix()
        if (!m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return src
        val out = Bitmap.createBitmap(outW.toInt(), outH.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Full pipeline: decode -> crop/perspective -> filter -> brightness/contrast. */
    fun render(page: ScanPage, maxEdge: Int): Bitmap? {
        val src = decode(page.file, maxEdge * 2, page.rotation) ?: return null
        val flat = warp(src, page.corners, maxEdge)
        if (flat !== src) src.recycle()
        val w = flat.width; val h = flat.height
        val px = IntArray(w * h)
        flat.getPixels(px, 0, w, 0, 0, w, h)
        flat.recycle()
        val filtered = DocFilters.adjust(DocFilters.apply(page.filter, px, w, h), page.brightness, page.contrast)
        return Bitmap.createBitmap(filtered, w, h, Bitmap.Config.ARGB_8888)
    }

    fun detect(file: File, rotation: Int = 0): List<Pt> {
        val small = decode(file, 640, rotation) ?: return DocDetect.fallback()
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val q = DocDetect.findCorners(px, small.width, small.height)
        small.recycle()
        return q
    }

    /** Rotating the source 90° clockwise: points move (x,y)->(1-y,x) and the list is cycled so index 0 stays top-left. */
    fun rotateCornersCw(c: List<Pt>): List<Pt> {
        val moved = c.map { Pt(1f - it.y, it.x) }
        return listOf(moved[3], moved[0], moved[1], moved[2])
    }
}
