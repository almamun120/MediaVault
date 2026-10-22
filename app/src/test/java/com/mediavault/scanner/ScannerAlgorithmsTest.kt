package com.mediavault.scanner

import com.mediavault.feature.scanner.DocDetect
import com.mediavault.feature.scanner.DocFilters
import com.mediavault.feature.scanner.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScannerAlgorithmsTest {
    private fun rgb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private val W = 120
    private val H = 90

    /** Paper with a horizontal brightness gradient (a "shadow") and a dark text bar. */
    private fun paper(withText: Boolean): IntArray = IntArray(W * H) { i ->
        val x = i % W; val y = i / W
        if (withText && y in 40..44 && x in 20..100) rgb(20) else rgb(120 + x)
    }

    @Test fun originalIsIdentity() {
        val p = paper(true)
        assertTrue(p.contentEquals(DocFilters.apply(ScanFilter.ORIGINAL, p, W, H)))
    }

    @Test fun blackAndWhiteOutputsOnlyBlackAndWhite() {
        val out = DocFilters.apply(ScanFilter.BW, paper(true), W, H)
        assertTrue(out.all { it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() })
        assertEquals(0xFF000000.toInt(), out[42 * W + 60]) // the text bar is ink
        assertEquals(0xFFFFFFFF.toInt(), out[10 * W + 60]) // plain paper is white
    }

    @Test fun noShadowFlattensGradient() {
        val out = DocFilters.apply(ScanFilter.NO_SHADOW, paper(false), W, H)
        val lum = out.map { it and 255 }
        assertTrue("paper should be bright", lum.min() > 225)
        assertTrue("gradient should be gone", lum.max() - lum.min() <= 25)
    }

    @Test fun grayscaleHasEqualChannels() {
        val colored = IntArray(W * H) { (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50 }
        val out = DocFilters.apply(ScanFilter.GRAY, colored, W, H)
        assertTrue(out.all { ((it shr 16) and 255) == ((it shr 8) and 255) && ((it shr 8) and 255) == (it and 255) })
    }

    @Test fun everyFilterKeepsSizeAndOpaqueAlpha() {
        for (f in ScanFilter.entries) {
            val out = DocFilters.apply(f, paper(true), W, H)
            assertEquals(f.name, W * H, out.size)
            assertTrue(f.name, out.all { (it ushr 24) == 255 })
        }
    }

    @Test fun adjustBrightensAndIsNoopAtDefaults() {
        val p = paper(false)
        assertTrue(p === DocFilters.adjust(p, 0, 1f))
        assertTrue((DocFilters.adjust(p, 40, 1f)[0] and 255) > (p[0] and 255))
    }

    @Test fun detectsBrightPageOnDarkBackground() {
        val img = IntArray(200 * 300) { i ->
            val x = i % 200; val y = i / 200
            if (x in 40..159 && y in 60..239) rgb(235) else rgb(30)
        }
        val q = DocDetect.findCorners(img, 200, 300)
        val expect = listOf(0.2f to 0.2f, 0.8f to 0.2f, 0.8f to 0.8f, 0.2f to 0.8f)
        q.zip(expect).forEach { (p, e) ->
            assertTrue("corner $p vs $e", abs(p.x - e.first) < 0.04f && abs(p.y - e.second) < 0.04f)
        }
    }

    @Test fun fallsBackWhenImageIsUniform() {
        val img = IntArray(100 * 100) { rgb(128) }
        assertEquals(DocDetect.fallback(), DocDetect.findCorners(img, 100, 100))
    }
}
