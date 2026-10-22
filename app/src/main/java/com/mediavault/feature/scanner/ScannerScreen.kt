package com.mediavault.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Stage { CAPTURE, REVIEW, EDIT }

@Composable
fun ScannerScreen(onClose: () -> Unit, vm: ScannerViewModel = hiltViewModel()) {
    val pages by vm.pages.collectAsStateWithLifecycle()
    var stage by rememberSaveable { mutableStateOf(Stage.CAPTURE) }
    var editId by rememberSaveable { mutableLongStateOf(-1L) }

    when (stage) {
        Stage.CAPTURE -> CaptureStage(vm, pages.size, onDone = { stage = Stage.REVIEW }, onClose = onClose)
        Stage.REVIEW -> ReviewStage(vm, pages, onAdd = { stage = Stage.CAPTURE }, onEdit = { editId = it; stage = Stage.EDIT }, onClose = onClose)
        Stage.EDIT -> {
            val page = pages.firstOrNull { it.id == editId }
            if (page == null) stage = Stage.REVIEW else EditStage(vm, page, onBack = { stage = Stage.REVIEW })
        }
    }
}

// ---------------------------------------------------------------- capture

@Composable
private fun CaptureStage(vm: ScannerViewModel, count: Int, onDone: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { if (it.isNotEmpty()) vm.importImages(it) }

    if (!hasCamera) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera access is needed to scan documents.", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                OutlinedButton(onClick = { importLauncher.launch("image/*") }) { Text("Import images instead") }
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(ctx) }
    val controller = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
    }
    var flash by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var message by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycleOwner) { controller.bindToLifecycle(lifecycleOwner); onDispose { controller.unbind() } }
    LaunchedEffect(flash) { controller.imageCaptureFlashMode = flash }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(Modifier.fillMaxSize(), factory = { c -> PreviewView(c).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FIT_CENTER } })
        Row(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            IconButton(onClick = {
                flash = when (flash) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            }) {
                Icon(
                    when (flash) { ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn; ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto; else -> Icons.Filled.FlashOff },
                    "Flash", tint = Color.White,
                )
            }
            TextButton(onClick = onClose) { Text("Close", color = Color.White) }
        }
        message?.let { Text(it, Modifier.align(Alignment.Center), color = Color.White) }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { importLauncher.launch("image/*") }) { Icon(Icons.Filled.PhotoLibrary, "Import from gallery", tint = Color.White) }
            IconButton(
                onClick = {
                    val file = vm.newPageFile()
                    controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), mainExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { vm.addPage(file); message = null }
                        override fun onError(e: ImageCaptureException) { file.delete(); message = "Capture failed: ${e.message}" }
                    })
                },
                modifier = Modifier.size(72.dp).background(Color.White, CircleShape),
            ) { Icon(Icons.Filled.FiberManualRecord, "Capture page", tint = Color.Black, modifier = Modifier.size(40.dp)) }
            Button(onClick = onDone, enabled = count > 0) { Text("Done ($count)") }
        }
    }
}

// ---------------------------------------------------------------- review

@Composable
private fun ReviewStage(vm: ScannerViewModel, pages: List<ScanPage>, onAdd: () -> Unit, onEdit: (Long) -> Unit, onClose: () -> Unit) {
    var exporting by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("${pages.size} page(s)", style = MaterialTheme.typography.titleLarge)
        Text("Tap a page to crop and choose a filter.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Text("Filter all:  ", Modifier.align(Alignment.CenterVertically))
            ScanFilter.entries.forEach { f -> FilterChip(false, { vm.applyFilterToAll(f) }, label = { Text(f.label) }, modifier = Modifier.padding(end = 6.dp)) }
        }
        LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pages, key = { it.id }) { p ->
                val thumb by produceState<Bitmap?>(null, p) { value = withContext(Dispatchers.Default) { PageRenderer.render(p, 420) } }
                Column {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color(0xFFEEEEEE)).clickable { onEdit(p.id) }, contentAlignment = Alignment.Center) {
                        val t = thumb
                        if (t != null) Image(t.asImageBitmap(), "Page ${pages.indexOf(p) + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else CircularProgressIndicator()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${pages.indexOf(p) + 1}", Modifier.weight(1f))
                        TextButton(onClick = { vm.move(p.id, -1) }) { Text("◀") }
                        TextButton(onClick = { vm.move(p.id, 1) }) { Text("▶") }
                        TextButton(onClick = { vm.remove(p.id) }) { Text("Delete") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAdd) { Text("Add page") }
            Button(onClick = { exporting = true }, enabled = pages.isNotEmpty()) { Text("Save as PDF") }
            TextButton(onClick = { if (pages.isEmpty()) onClose() else confirmClose = true }) { Text("Close") }
        }
    }

    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("Discard scan?") },
        text = { Text("The ${pages.size} unsaved page(s) will be lost.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } },
    )
    if (exporting) ExportDialog(vm, onDismiss = { exporting = false }, onSaved = { exporting = false; onClose() })
}

@Composable
private fun ExportDialog(vm: ScannerViewModel, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var a4 by remember { mutableStateOf(true) }
    var jpeg by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { name = vm.defaultName() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Save document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("File name") }, singleLine = true, enabled = !busy)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(a4, { a4 = it }, enabled = !busy); Text(if (a4) "  A4 pages" else "  Page size follows the scan") }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(jpeg, { jpeg = it }, enabled = !busy); Text("  Also save each page as JPEG") }
                Text("Saved on this device first; upload rules then decide what is sent anywhere.", style = MaterialTheme.typography.bodySmall)
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    vm.export(name, a4, jpeg).fold(onSuccess = { onSaved() }, onFailure = { error = it.message ?: "Could not save"; busy = false })
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- edit

@Composable
private fun EditStage(vm: ScannerViewModel, page: ScanPage, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) } // 0 = crop, 1 = filters

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("‹ Pages") }
            FilterChip(tab == 0, { tab = 0 }, label = { Text("Crop") })
            FilterChip(tab == 1, { tab = 1 }, label = { Text("Filters") })
        }
        if (tab == 0) CropTab(vm, page, Modifier.weight(1f)) else FilterTab(vm, page, Modifier.weight(1f))
    }
}

@Composable
private fun CropTab(vm: ScannerViewModel, page: ScanPage, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, page.file, page.rotation) { value = withContext(Dispatchers.IO) { PageRenderer.decode(page.file, 1600, page.rotation) } }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b == null) CircularProgressIndicator() else CropEditor(b, page.corners, onChange = { vm.update(page.copy(corners = it)) })
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.redetect(page) }) { Text("Auto") }
            OutlinedButton(onClick = { vm.update(page.copy(corners = DocDetect.fullFrame())) }) { Text("Full page") }
            OutlinedButton(onClick = { vm.update(page.copy(rotation = (page.rotation + 90) % 360, corners = PageRenderer.rotateCornersCw(page.corners))) }) { Text("Rotate") }
        }
        Text("Drag the corners to the edges of the page.", Modifier.padding(start = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FilterTab(vm: ScannerViewModel, page: ScanPage, modifier: Modifier) {
    val preview by produceState<Bitmap?>(null, page) { value = null; value = withContext(Dispatchers.Default) { PageRenderer.render(page, 1400) } }
    var brightness by remember(page.id) { mutableFloatStateOf(page.brightness.toFloat()) }
    var contrast by remember(page.id) { mutableFloatStateOf(page.contrast) }

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF222222)), contentAlignment = Alignment.Center) {
            val p = preview
            if (p == null) CircularProgressIndicator() else Image(p.asImageBitmap(), "Filtered preview", Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
            ScanFilter.entries.forEach { f ->
                FilterChip(page.filter == f, { vm.update(page.copy(filter = f)) }, label = { Text(f.label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness", Modifier.padding(end = 8.dp))
            Slider(brightness, { brightness = it }, valueRange = -100f..100f, onValueChangeFinished = { vm.update(page.copy(brightness = brightness.toInt())) })
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Contrast", Modifier.padding(end = 8.dp))
            Slider(contrast, { contrast = it }, valueRange = 0.5f..2f, onValueChangeFinished = { vm.update(page.copy(contrast = contrast)) })
        }
    }
}
