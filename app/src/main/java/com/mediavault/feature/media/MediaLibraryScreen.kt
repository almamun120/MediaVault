package com.mediavault.feature.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.text.format.DateFormat
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediavault.core.database.JobStatus
import com.mediavault.core.database.ManagedFileEntity
import com.mediavault.core.util.PdfRender
import com.mediavault.core.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

private fun loadThumb(file: File, kind: MediaKind, maxPx: Int): Bitmap? = try {
    when (kind) {
        MediaKind.PHOTO -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (bmp != null && rotation != 0f) {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
            } else bmp
        }
        MediaKind.VIDEO -> {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(file.path); r.getFrameAtTime(0) } finally { r.release() }
        }
        MediaKind.DOCUMENT -> PdfRender.page(file, 0, maxPx).first
        MediaKind.AUDIO -> null
    }
} catch (e: Exception) {
    null // corrupt or incomplete media: show the placeholder icon
}

@Composable
private fun Thumb(item: ManagedFileEntity, modifier: Modifier) {
    val kind = MediaKind.valueOf(item.kind)
    val bmp by produceState<Bitmap?>(null, item.path) {
        value = withContext(Dispatchers.IO) { loadThumb(File(item.path), kind, 320) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), item.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.AudioFile.takeIf { kind == MediaKind.AUDIO } ?: Icons.Filled.GridView, null, Modifier.size(40.dp))
    }
}

@Composable
fun MediaLibraryScreen(vm: MediaLibraryViewModel = hiltViewModel()) {
    val files by vm.items.collectAsStateWithLifecycle()
    val jobsByPath by vm.jobsByPath.collectAsStateWithLifecycle()
    val dests by vm.destinations.collectAsStateWithLifecycle()
    var uploadFor by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val grid by vm.grid.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var toDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery, singleLine = true,
                label = { Text("Search") }, modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort: ${sort.label}") }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    LibrarySort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { vm.setSort(s); sortMenu = false })
                    }
                }
            }
            IconButton(onClick = vm::toggleGrid) {
                Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, if (grid) "List view" else "Grid view")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            LibraryFilter.entries.forEach {
                FilterChip(selected = filter == it, onClick = { vm.setFilter(it) }, label = { Text(it.label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        if (files.isEmpty()) {
            Text("No media found.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        } else if (grid) {
            LazyVerticalGrid(GridCells.Adaptive(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(files, key = { it.id }) { it ->
                    Thumb(it, Modifier.aspectRatio(1f).clickable { preview = it })
                }
            }
        } else {
            LazyColumn {
                items(files, key = { it.id }) { it ->
                    Row(Modifier.fillMaxWidth().clickable { preview = it }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(it, Modifier.size(64.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(it.displayName, style = MaterialTheme.typography.bodyLarge)
                            val date = DateFormat.format("yyyy-MM-dd HH:mm", Date(it.createdAt))
                            Text("${formatSize(it.sizeBytes)} • $date", style = MaterialTheme.typography.bodySmall)
                            Text(
                                (if (it.interrupted) "Possibly incomplete • " else "") + (if (it.localDeleted) "Remote only • " else "On device • ") + uploadSummary(jobsByPath[it.path]),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { toDelete = it }) { Icon(Icons.Filled.Delete, "Delete ${it.displayName}") }
                    }
                }
            }
        }
    }

    preview?.let { item ->
        PreviewDialog(item, onDelete = { toDelete = item; preview = null }, onUpload = { uploadFor = item; preview = null }, onDismiss = { preview = null })
    }
    uploadFor?.let { item ->
        AlertDialog(
            onDismissRequest = { uploadFor = null },
            title = { Text("Upload ${item.displayName} to…") },
            text = {
                Column {
                    if (dests.isEmpty()) Text("No destinations yet. Add one in the Storage tab.")
                    dests.forEach { d -> TextButton(onClick = { vm.uploadTo(item, d.id) { info = it }; uploadFor = null }) { Text(d.name) } }
                }
            },
            confirmButton = { TextButton(onClick = { uploadFor = null }) { Text("Cancel") } },
        )
    }
    info?.let { msg -> AlertDialog(onDismissRequest = { info = null }, text = { Text(msg) }, confirmButton = { TextButton(onClick = { info = null }) { Text("OK") } }) }
    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete file?") },
            text = { Text("${item.displayName} will be permanently deleted from this device. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(item); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun uploadSummary(jobs: List<com.mediavault.core.database.UploadJobEntity>?): String {
    if (jobs.isNullOrEmpty()) return "Not uploaded"
    val done = jobs.count { it.status == JobStatus.DONE }
    val failed = jobs.count { it.status == JobStatus.FAILED }
    val waiting = jobs.count { it.status == JobStatus.QUEUED || it.status == JobStatus.UPLOADING || it.status == JobStatus.PAUSED }
    return "Uploaded $done/${jobs.size}" + (if (failed > 0) " • $failed failed" else "") + (if (waiting > 0) " • $waiting waiting" else "")
}

private fun formatSize(b: Long) = if (b >= 1_048_576) "%.1f MB".format(b / 1_048_576.0) else "${b / 1024} KB"

@Composable
private fun PreviewDialog(item: ManagedFileEntity, onDelete: () -> Unit, onUpload: () -> Unit, onDismiss: () -> Unit) {
    val kind = MediaKind.valueOf(item.kind)
    Dialog(onDismiss) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
            Text(item.displayName, style = MaterialTheme.typography.titleMedium)
            Text(item.path, style = MaterialTheme.typography.bodySmall)
            if (item.localDeleted) {
                Text("The local copy was deleted after a verified upload. Download from the destination to view it.", Modifier.padding(vertical = 8.dp))
            } else when (kind) {
                MediaKind.PHOTO -> {
                    val bmp by produceState<Bitmap?>(null, item.path) { value = withContext(Dispatchers.IO) { loadThumb(File(item.path), kind, 1600) } }
                    bmp?.let { Image(it.asImageBitmap(), item.displayName, Modifier.fillMaxWidth().padding(vertical = 8.dp), contentScale = ContentScale.Fit) }
                        ?: Text("Cannot decode this image (it may be corrupt).")
                }
                MediaKind.VIDEO -> AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 8.dp),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                            setVideoPath(item.path)
                            setOnErrorListener { _, _, _ -> true } // corrupt file: stay silent, dialog still closable
                            start()
                        }
                    },
                    onRelease = { it.stopPlayback() },
                )
                MediaKind.AUDIO -> AudioPlayerRow(item.path)
                MediaKind.DOCUMENT -> PdfPreview(item.path)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!item.localDeleted) TextButton(onClick = onUpload) { Text("Upload…") }
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun AudioPlayerRow(path: String) {
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val player = remember {
        MediaPlayer().apply {
            try { setDataSource(path); prepare() } catch (e: Exception) { failed = true }
            setOnCompletionListener { playing = false }
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    if (failed) {
        Text("Cannot play this file (it may be corrupt or incomplete).")
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (playing) player.pause() else player.start()
                playing = !playing
            }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
            Text("%d:%02d".format(player.duration / 60000, player.duration / 1000 % 60))
        }
    }
}


@Composable
private fun PdfPreview(path: String) {
    var page by remember { mutableStateOf(0) }
    val result by produceState<Pair<Bitmap?, Int>>(null to 0, path, page) {
        value = withContext(Dispatchers.IO) { PdfRender.page(File(path), page, 1400) }
    }
    val (bmp, count) = result
    if (bmp == null) { Text("Cannot render this PDF (it may be corrupt)."); return }
    Image(bmp.asImageBitmap(), "Page ${page + 1}", Modifier.fillMaxWidth().padding(vertical = 8.dp), contentScale = ContentScale.Fit)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text("Prev") }
        Text("Page ${page + 1} / $count")
        TextButton(onClick = { if (page < count - 1) page++ }, enabled = page < count - 1) { Text("Next") }
    }
}
