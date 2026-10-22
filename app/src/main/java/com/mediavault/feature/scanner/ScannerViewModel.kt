package com.mediavault.feature.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediavault.core.model.MediaKind
import com.mediavault.core.settings.SettingsRepository
import com.mediavault.core.storage.MediaDirs
import com.mediavault.core.util.FilenameTemplate
import com.mediavault.transfer.MediaIngestor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import kotlin.math.min

@HiltViewModel
class ScannerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ingestor: MediaIngestor,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val sessionDir = File(context.cacheDir, "scan/${UUID.randomUUID()}").apply { mkdirs() }
    private val _pages = MutableStateFlow<List<ScanPage>>(emptyList())
    val pages: StateFlow<List<ScanPage>> = _pages.asStateFlow()
    private var nextId = 1L

    fun newPageFile() = File(sessionDir, "page_${System.nanoTime()}.jpg")

    fun addPage(file: File) {
        viewModelScope.launch(Dispatchers.Default) { appendPage(file) }
    }

    private fun appendPage(file: File) {
        val corners = PageRenderer.detect(file)
        _pages.update { it + ScanPage(nextId++, file, corners) }
    }

    fun importImages(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (u in uris) {
                val f = newPageFile()
                val ok = runCatching {
                    context.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } != null
                }.getOrDefault(false)
                if (ok && f.length() > 0) appendPage(f) else f.delete()
            }
        }
    }

    fun update(page: ScanPage) = _pages.update { l -> l.map { if (it.id == page.id) page else it } }
    fun remove(id: Long) = _pages.update { l -> l.filter { it.id != id } }
    fun applyFilterToAll(filter: ScanFilter) = _pages.update { l -> l.map { it.copy(filter = filter) } }

    fun move(id: Long, delta: Int) = _pages.update { l ->
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in l.indices) l else l.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }

    fun redetect(page: ScanPage) {
        viewModelScope.launch(Dispatchers.Default) { update(page.copy(corners = PageRenderer.detect(page.file, page.rotation))) }
    }

    suspend fun defaultName(): String = FilenameTemplate.render(settings.settings.first().documentTemplate, ZonedDateTime.now())

    /** Writes a PDF (and optionally one JPEG per page) into app storage, then indexes it so upload rules apply. */
    suspend fun export(name: String, a4: Boolean, alsoJpeg: Boolean): Result<File> = withContext(Dispatchers.Default) {
        val pages = _pages.value
        if (pages.isEmpty()) return@withContext Result.failure(IllegalStateException("No pages to export"))
        val dir = MediaDirs.dirFor(context, MediaKind.DOCUMENT)
        val tmp = File(dir, ".scan_${System.currentTimeMillis()}.tmp")
        val base = FilenameTemplate.sanitize(name.removeSuffix(".pdf").ifBlank { "scan" })
        val jpegs = mutableListOf<File>()
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { i, p ->
                val bmp = PageRenderer.render(p, 2000) ?: throw IOException("Page ${i + 1} could not be read")
                val (pw, ph) = if (a4) 595 to 842 else (bmp.width * 72 / 150).coerceAtLeast(72) to (bmp.height * 72 / 150).coerceAtLeast(72)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val dst = if (a4) fit(bmp, pw, ph) else RectF(0f, 0f, pw.toFloat(), ph.toFloat())
                page.canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                if (alsoJpeg) {
                    val jf = FilenameTemplate.uniqueFile(MediaDirs.dirFor(context, MediaKind.PHOTO), "${base}_p${i + 1}", "jpg")
                    jf.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    jpegs += jf
                }
                bmp.recycle()
            }
            tmp.outputStream().use { pdf.writeTo(it) }
            val final = FilenameTemplate.uniqueFile(dir, base, MediaKind.DOCUMENT.extension)
            if (!tmp.renameTo(final)) throw IOException("Could not save ${final.name}")
            ingestor.ingest(final, MediaKind.DOCUMENT)
            jpegs.forEach { ingestor.ingest(it, MediaKind.PHOTO) }
            Result.success(final)
        } catch (e: Exception) {
            tmp.delete()
            jpegs.forEach { it.delete() }
            Result.failure(e)
        } finally {
            pdf.close()
        }
    }

    private fun fit(bmp: Bitmap, pw: Int, ph: Int): RectF {
        val s = min(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
        val w = bmp.width * s; val h = bmp.height * s
        return RectF((pw - w) / 2, (ph - h) / 2, (pw + w) / 2, (ph + h) / 2)
    }

    override fun onCleared() {
        sessionDir.deleteRecursively()
    }
}
