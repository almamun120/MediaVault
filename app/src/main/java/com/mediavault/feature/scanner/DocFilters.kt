package com.mediavault.feature.scanner

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Scan filters of the kind document-scanner apps offer. These are our own implementations, not any vendor's algorithms. */
enum class ScanFilter(val label: String) {
    ORIGINAL("Original"),
    MAGIC("Magic Color"),
    LIGHTEN("Lighten"),
    GRAY("Grayscale"),
    BW("B&W"),
    BW_SOFT("B&W Soft"),
    NO_SHADOW("No Shadow"),
    ECO("Eco"),
    SHARPEN("Sharpen"),
    WHITEBOARD("Whiteboard"),
}

/** Pure pixel math on ARGB IntArrays so it runs (and is unit-tested) without Android. */
object DocFilters {

    private fun r(c: Int) = (c shr 16) and 255
    private fun g(c: Int) = (c shr 8) and 255
    private fun b(c: Int) = c and 255
    private fun clamp(v: Float) = if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()
    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
    private fun pack(r: Int, g: Int, b: Int) = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
    private fun luma(c: Int) = (299 * r(c) + 587 * g(c) + 114 * b(c)) / 1000

    fun apply(filter: ScanFilter, px: IntArray, w: Int, h: Int): IntArray = when (filter) {
        ScanFilter.ORIGINAL -> px.copyOf()
        ScanFilter.GRAY -> IntArray(px.size) { val l = luma(px[it]); pack(l, l, l) }
        ScanFilter.LIGHTEN -> lighten(px)
        ScanFilter.NO_SHADOW -> noShadow(px, w, h)
        ScanFilter.BW -> blackWhite(px, w, h)
        ScanFilter.BW_SOFT -> softGray(px, w, h)
        ScanFilter.ECO -> softGray(px, w, h).let { s -> IntArray(s.size) { val gv = 255 - ((255 - r(s[it])) * 0.6f).toInt(); pack(gv, gv, gv) } }
        ScanFilter.SHARPEN -> sharpen(px, w, h)
        ScanFilter.MAGIC -> noShadow(px, w, h).let { s -> IntArray(s.size) { levelsSat(s[it], 20, 235, 1.25f) } }
        ScanFilter.WHITEBOARD -> noShadow(px, w, h).let { s -> IntArray(s.size) { levelsSat(s[it], 60, 215, 1.5f) } }
    }

    /** Manual brightness (-100..100) and contrast (0.5..2.0) applied after the filter. */
    fun adjust(px: IntArray, brightness: Int, contrast: Float): IntArray {
        if (brightness == 0 && contrast == 1f) return px
        val lut = IntArray(256) { clamp((it - 128) * contrast + 128f + brightness * 1.28f) }
        return IntArray(px.size) { val c = px[it]; pack(lut[r(c)], lut[g(c)], lut[b(c)]) }
    }

    // ---- building blocks -------------------------------------------------

    /** Local mean of luma in a (2*radius+1) window, via an integral image (windows are clipped at the borders). */
    internal fun localMean(px: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val stride = w + 1
        val integral = LongArray(stride * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += luma(px[y * w + x])
                integral[(y + 1) * stride + (x + 1)] = integral[y * stride + (x + 1)] + row
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - radius); val y1 = min(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - radius); val x1 = min(w, x + radius + 1)
                val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] - integral[y1 * stride + x0] + integral[y0 * stride + x0]
                out[y * w + x] = (sum / ((x1 - x0).toLong() * (y1 - y0))).toInt()
            }
        }
        return out
    }

    private fun bgRadius(w: Int, h: Int) = max(8, max(w, h) / 12)

    /** Divides out the uneven paper background so shadows and gradients disappear while colours stay. */
    fun noShadow(px: IntArray, w: Int, h: Int): IntArray {
        val bg = localMean(px, w, h, bgRadius(w, h))
        return IntArray(px.size) {
            val f = 252f / max(bg[it], 24)
            val c = px[it]
            pack(clamp(r(c) * f), clamp(g(c) * f), clamp(b(c) * f))
        }
    }

    /** Adaptive threshold: a pixel is ink when clearly darker than its neighbourhood. Output is pure black/white. */
    fun blackWhite(px: IntArray, w: Int, h: Int): IntArray {
        val mean = localMean(px, w, h, max(8, max(w, h) / 20))
        return IntArray(px.size) { if (luma(px[it]) < mean[it] * 0.90f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    }

    private fun softGray(px: IntArray, w: Int, h: Int): IntArray {
        val bg = localMean(px, w, h, bgRadius(w, h))
        return IntArray(px.size) {
            val n = luma(px[it]) * 252f / max(bg[it], 24)
            val v = clamp((n - 70f) * 255f / (200f - 70f))
            pack(v, v, v)
        }
    }

    private fun lighten(px: IntArray): IntArray {
        val lut = IntArray(256) { clamp(255f * (it / 255f).pow(0.65f) + 8f) }
        return IntArray(px.size) { val c = px[it]; pack(lut[r(c)], lut[g(c)], lut[b(c)]) }
    }

    private fun levelsSat(c: Int, black: Int, white: Int, sat: Float): Int {
        val l = luma(c).toFloat()
        val scale = 255f / (white - black)
        fun ch(v: Int) = clamp(((l + (v - l) * sat) - black) * scale)
        return pack(ch(r(c)), ch(g(c)), ch(b(c)))
    }

    private fun sharpen(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(px.size)
        fun at(x: Int, y: Int) = px[min(h - 1, max(0, y)) * w + min(w - 1, max(0, x))]
        for (y in 0 until h) for (x in 0 until w) {
            val c = at(x, y); val n = at(x, y - 1); val s = at(x, y + 1); val e = at(x + 1, y); val wv = at(x - 1, y)
            out[y * w + x] = pack(
                5 * r(c) - r(n) - r(s) - r(e) - r(wv),
                5 * g(c) - g(n) - g(s) - g(e) - g(wv),
                5 * b(c) - b(n) - b(s) - b(e) - b(wv),
            )
        }
        return out
    }
}
