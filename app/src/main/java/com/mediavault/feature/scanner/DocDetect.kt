package com.mediavault.feature.scanner

import kotlin.math.abs
import kotlin.math.max

data class Pt(val x: Float, val y: Float)

/**
 * Heuristic page detection without OpenCV: threshold the image (Otsu), take the largest bright (or dark)
 * blob that is a plausible page size, and use its four extreme points as corners. It works well for a page on
 * a contrasting surface; cluttered scenes fall back to a near-full-frame box and the user drags the corners.
 */
object DocDetect {
    private const val WORK = 320

    /** Returns corners in order TL, TR, BR, BL, normalised to 0..1. Never throws; falls back to an inset rectangle. */
    fun findCorners(px: IntArray, w: Int, h: Int): List<Pt> {
        if (w < 16 || h < 16) return fallback()
        val scale = WORK.toFloat() / max(w, h)
        val sw = max(8, (w * scale).toInt()); val sh = max(8, (h * scale).toInt())
        val gray = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val c = px[minOf(h - 1, (y / scale).toInt()) * w + minOf(w - 1, (x / scale).toInt())]
            gray[y * sw + x] = (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000
        }
        val blur = boxBlur(gray, sw, sh)
        val t = otsu(blur)
        for (bright in booleanArrayOf(true, false)) {
            val mask = BooleanArray(blur.size) { if (bright) blur[it] > t else blur[it] <= t }
            val comp = largestComponent(mask, sw, sh) ?: continue
            val frac = comp.count.toFloat() / (sw * sh)
            if (frac < 0.12f || frac > 0.97f) continue
            val q = listOf(
                Pt((comp.tl.first + 0.5f) / sw, (comp.tl.second + 0.5f) / sh),
                Pt((comp.tr.first + 0.5f) / sw, (comp.tr.second + 0.5f) / sh),
                Pt((comp.br.first + 0.5f) / sw, (comp.br.second + 0.5f) / sh),
                Pt((comp.bl.first + 0.5f) / sw, (comp.bl.second + 0.5f) / sh),
            )
            if (area(q) >= 0.10f) return q
        }
        return fallback()
    }

    fun fallback(): List<Pt> = listOf(Pt(0.04f, 0.04f), Pt(0.96f, 0.04f), Pt(0.96f, 0.96f), Pt(0.04f, 0.96f))
    fun fullFrame(): List<Pt> = listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))

    internal fun area(q: List<Pt>): Float {
        var s = 0f
        for (i in q.indices) { val a = q[i]; val b = q[(i + 1) % q.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2f
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { sum += src[yy * w + xx]; n++ }
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    internal fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        gray.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = gray.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var thr = 127
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += i * hist[i]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = i }
        }
        return thr
    }

    private class Component(val count: Int, val tl: Pair<Int, Int>, val tr: Pair<Int, Int>, val br: Pair<Int, Int>, val bl: Pair<Int, Int>)

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): Component? {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        var best: Component? = null
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            var count = 0
            var tl = start; var tr = start; var br = start; var bl = start
            var minS = Int.MAX_VALUE; var maxS = Int.MIN_VALUE; var minD = Int.MAX_VALUE; var maxD = Int.MIN_VALUE
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                count++
                val s = x + y; val d = x - y
                if (s < minS) { minS = s; tl = p }
                if (s > maxS) { maxS = s; br = p }
                if (d > maxD) { maxD = d; tr = p }
                if (d < minD) { minD = d; bl = p }
                if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && mask[p - w] && !seen[p - w]) { seen[p - w] = true; stack[sp++] = p - w }
                if (y < h - 1 && mask[p + w] && !seen[p + w]) { seen[p + w] = true; stack[sp++] = p + w }
            }
            if (best == null || count > best.count) {
                best = Component(count, tl % w to tl / w, tr % w to tr / w, br % w to br / w, bl % w to bl / w)
            }
        }
        return best
    }
}
