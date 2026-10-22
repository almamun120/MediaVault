package com.mediavault.core.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

object PdfRender {
    /** Renders one page (0-based) scaled so its long edge is about [maxPx]. Returns the bitmap and the page count. */
    fun page(file: File, index: Int, maxPx: Int): Pair<Bitmap?, Int> = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                val count = r.pageCount
                if (count == 0) return null to 0
                r.openPage(index.coerceIn(0, count - 1)).use { p ->
                    val scale = maxPx.toFloat() / maxOf(p.width, p.height)
                    val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp to count
                }
            }
        }
    } catch (e: Exception) {
        null to 0 // corrupt or unsupported PDF
    }
}
