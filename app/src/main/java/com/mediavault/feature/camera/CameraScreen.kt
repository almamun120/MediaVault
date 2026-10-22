package com.mediavault.feature.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mediavault.core.model.MediaKind
import com.mediavault.core.storage.MediaDirs
import com.mediavault.core.util.FilenameTemplate
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZonedDateTime
import kotlin.math.roundToInt

enum class CaptureMode { PHOTO, VIDEO }
enum class FlashSetting { OFF, AUTO, ON }
enum class ExtMode(val label: String, val code: Int) {
    NONE("Standard", ExtensionMode.NONE),
    HDR("HDR", ExtensionMode.HDR),
    NIGHT("Night", ExtensionMode.NIGHT),
    BOKEH("Portrait", ExtensionMode.BOKEH),
    FACE("Retouch", ExtensionMode.FACE_RETOUCH),
}

private val videoQualities = linkedMapOf(
    "4K" to Quality.UHD, "1080p" to Quality.FHD, "720p" to Quality.HD, "480p" to Quality.SD,
)

private fun granted(c: Context, p: String) =
    ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var hasMic by remember { mutableStateOf(granted(context, Manifest.permission.RECORD_AUDIO)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCamera = granted(context, Manifest.permission.CAMERA)
        hasMic = granted(context, Manifest.permission.RECORD_AUDIO)
    }

    if (hasCamera) {
        CameraContent(hasMic = hasMic, requestMic = { launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) })
    } else {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera access needed", style = MaterialTheme.typography.titleLarge)
            Text(
                "MediaVault needs the camera to preview, take photos and record video. Nothing is uploaded unless you configure a destination.",
                Modifier.padding(vertical = 12.dp),
            )
            Button(onClick = { launcher.launch(arrayOf(Manifest.permission.CAMERA)) }) { Text("Allow camera") }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraContent(hasMic: Boolean, requestMic: () -> Unit, vm: CaptureViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val controller = remember { LifecycleCameraController(context) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    var mode by rememberSaveable { mutableStateOf(CaptureMode.PHOTO) }
    var front by rememberSaveable { mutableStateOf(false) }
    var flash by rememberSaveable { mutableStateOf(FlashSetting.OFF) }
    var ext by rememberSaveable { mutableStateOf(ExtMode.NONE) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var ratio43 by rememberSaveable { mutableStateOf(true) }
    var timerSec by rememberSaveable { mutableIntStateOf(0) }
    var burst by rememberSaveable { mutableStateOf(false) }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var audioOn by rememberSaveable { mutableStateOf(true) }
    var qualityLabel by rememberSaveable { mutableStateOf("1080p") }

    var countdown by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var paused by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bytes by remember { mutableLongStateOf(0L) }
    var expIndex by remember { mutableFloatStateOf(0f) }
    var ready by remember { mutableStateOf(false) }
    var infoTick by remember { mutableIntStateOf(0) }
    var extManager by remember { mutableStateOf<ExtensionsManager?>(null) }

    val liveRecording by rememberUpdatedState(recording)

    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    // Bind / unbind with the lifecycle. A running recording is stopped (and finalized) if the screen goes away.
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            liveRecording?.stop()
            controller.unbind()
        }
    }

    LaunchedEffect(controller) {
        controller.initializationFuture.await()
        ready = true
        val provider = ProcessCameraProvider.getInstance(context).await()
        extManager = ExtensionsManager.getInstanceAsync(context, provider).await()
    }

    val baseSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    val availableExt = ExtMode.entries.filter {
        it == ExtMode.NONE || extManager?.isExtensionAvailable(baseSelector, it.code) == true
    }

    // Use cases + lens + extension. Extensions do not support video capture, so video always uses the plain selector.
    LaunchedEffect(mode, front, ext, extManager) {
        if (recording != null) return@LaunchedEffect
        val wanted = if (mode == CaptureMode.VIDEO || ext !in availableExt) ExtMode.NONE else ext
        if (wanted != ext && mode == CaptureMode.PHOTO) ext = wanted
        controller.setEnabledUseCases(
            if (mode == CaptureMode.PHOTO) CameraController.IMAGE_CAPTURE else CameraController.VIDEO_CAPTURE,
        )
        controller.cameraSelector = if (wanted == ExtMode.NONE) baseSelector
        else extManager!!.getExtensionEnabledCameraSelector(baseSelector, wanted.code)
        expIndex = 0f
        delay(700) // cameraInfo is refreshed after rebinding
        infoTick++
    }

    LaunchedEffect(flash, mode) {
        controller.imageCaptureFlashMode = when (flash) {
            FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
        }
        controller.enableTorch(mode == CaptureMode.VIDEO && flash == FlashSetting.ON)
    }

    LaunchedEffect(ratio43) {
        val size = CameraController.OutputSize(if (ratio43) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9)
        controller.imageCaptureTargetSize = size
        controller.previewTargetSize = size
    }

    LaunchedEffect(settings.defaultVideoQuality) {
        if (recording == null) qualityLabel = settings.defaultVideoQuality
    }

    LaunchedEffect(qualityLabel) {
        controller.videoCaptureQualitySelector = QualitySelector.from(
            videoQualities.getValue(qualityLabel),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
        )
    }

    val info = remember(infoTick) { controller.cameraInfo }
    val hasFlash = info?.hasFlashUnit() == true
    val zoom by controller.zoomState.observeAsState()
    val supportedQualities = remember(infoTick, mode) {
        info?.let { QualitySelector.getSupportedQualities(it) }.orEmpty()
    }
    val exposure = info?.exposureState

    // ---- Capture actions -------------------------------------------------

    fun takePhoto(remaining: Int) {
        val dir = MediaDirs.dirFor(context, MediaKind.PHOTO)
        val tmp = File(dir, ".capture_${System.nanoTime()}.tmp") // written fully, then renamed
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = front && mirror }
        val options = ImageCapture.OutputFileOptions.Builder(tmp).setMetadata(metadata).build()
        controller.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val name = FilenameTemplate.render(settings.photoTemplate, ZonedDateTime.now())
                val finalFile = FilenameTemplate.uniqueFile(dir, name, MediaKind.PHOTO.extension)
                if (tmp.renameTo(finalFile)) {
                    vm.register(finalFile, MediaKind.PHOTO)
                    if (remaining <= 1) say("Saved ${finalFile.name}")
                } else {
                    tmp.delete()
                    say("Could not save photo")
                }
                if (remaining > 1) takePhoto(remaining - 1)
            }

            override fun onError(e: ImageCaptureException) {
                tmp.delete()
                say("Photo failed: ${e.message}")
            }
        })
    }

    fun shutter() {
        scope.launch {
            if (timerSec > 0) {
                for (s in timerSec downTo 1) { countdown = s; delay(1000) }
                countdown = 0
            }
            takePhoto(if (burst) 5 else 1)
        }
    }

    fun startVideo() {
        val dir = MediaDirs.dirFor(context, MediaKind.VIDEO)
        val tmp = File(dir, ".rec_${System.currentTimeMillis()}.tmp")
        val withAudio = audioOn && hasMic
        durationMs = 0; bytes = 0
        recording = controller.startRecording(
            FileOutputOptions.Builder(tmp).build(),
            AudioConfig.create(withAudio),
            mainExecutor,
        ) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    bytes = event.recordingStats.numBytesRecorded
                }
                is VideoRecordEvent.Pause -> paused = true
                is VideoRecordEvent.Resume -> paused = false
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    paused = false
                    val base = FilenameTemplate.render(settings.videoTemplate, ZonedDateTime.now())
                    if (!event.hasError()) {
                        val f = FilenameTemplate.uniqueFile(dir, base, MediaKind.VIDEO.extension)
                        if (tmp.renameTo(f)) { vm.register(f, MediaKind.VIDEO); say("Saved ${f.name}") }
                    } else if (tmp.exists() && tmp.length() > 0) {
                        // Keep what we have but label it clearly as possibly incomplete.
                        val f = FilenameTemplate.uniqueFile(dir, base + "_INTERRUPTED", MediaKind.VIDEO.extension)
                        if (tmp.renameTo(f)) vm.register(f, MediaKind.VIDEO, interrupted = true)
                        say("Recording ended with an error (code ${event.error}); partial file kept")
                    } else {
                        tmp.delete()
                        say("Recording failed (code ${event.error})")
                    }
                }
            }
        }
    }

    // ---- UI --------------------------------------------------------------

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller // enables tap-to-focus and pinch-to-zoom
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
            },
        )

        if (grid) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.4f)
                for (i in 1..2) {
                    drawLine(c, androidx.compose.ui.geometry.Offset(size.width * i / 3, 0f), androidx.compose.ui.geometry.Offset(size.width * i / 3, size.height), 2f)
                    drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height * i / 3), androidx.compose.ui.geometry.Offset(size.width, size.height * i / 3), 2f)
                }
            }
        }

        if (countdown > 0) {
            Text(countdown.toString(), Modifier.align(Alignment.Center), color = Color.White, style = MaterialTheme.typography.displayLarge)
        }

        // Top controls
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasFlash) {
                    CamButton(
                        when (flash) { FlashSetting.OFF -> Icons.Filled.FlashOff; FlashSetting.AUTO -> Icons.Filled.FlashAuto; FlashSetting.ON -> Icons.Filled.FlashOn },
                        "Flash: ${flash.name.lowercase()}",
                    ) {
                        flash = when (flash) {
                            FlashSetting.OFF -> if (mode == CaptureMode.VIDEO) FlashSetting.ON else FlashSetting.AUTO
                            FlashSetting.AUTO -> FlashSetting.ON
                            FlashSetting.ON -> FlashSetting.OFF
                        }
                    }
                }
                if (mode == CaptureMode.PHOTO) {
                    CamButton(Icons.Filled.Timer, "Timer: ${if (timerSec == 0) "off" else "$timerSec seconds"}", selected = timerSec > 0) {
                        timerSec = when (timerSec) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
                    }
                    CamButton(Icons.Filled.BurstMode, "Burst of 5", selected = burst) { burst = !burst }
                }
                CamButton(Icons.Filled.GridOn, "Grid", selected = grid) { grid = !grid }
                CamButton(Icons.Filled.AspectRatio, if (ratio43) "Ratio 4:3" else "Ratio 16:9") { ratio43 = !ratio43 }
                if (front && mode == CaptureMode.PHOTO) {
                    CamButton(Icons.Filled.Flip, "Mirror saved selfie", selected = mirror) { mirror = !mirror }
                }
                if (mode == CaptureMode.VIDEO) {
                    CamButton(if (audioOn && hasMic) Icons.Filled.Mic else Icons.Filled.MicOff, "Microphone", selected = audioOn && hasMic) {
                        if (recording != null) return@CamButton
                        if (!hasMic) requestMic()
                        audioOn = !audioOn
                    }
                }
            }
            if (mode == CaptureMode.PHOTO && availableExt.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    availableExt.forEach { m ->
                        FilterChip(selected = ext == m, onClick = { ext = m }, label = { Text(m.label) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
            if (mode == CaptureMode.VIDEO && recording == null && supportedQualities.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    videoQualities.filter { it.value in supportedQualities }.keys.forEach { label ->
                        FilterChip(selected = qualityLabel == label, onClick = { qualityLabel = label }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
            if (recording != null) {
                val s = durationMs / 1000
                Text(
                    "%02d:%02d  •  %.1f MB%s".format(s / 60, s % 60, bytes / 1_048_576.0, if (paused) "  (paused)" else ""),
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                    color = Color.White,
                )
            }
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            zoom?.let { z ->
                if (z.maxZoomRatio > z.minZoomRatio) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%.1fx".format(z.zoomRatio), color = Color.White, modifier = Modifier.padding(end = 8.dp))
                        Slider(
                            value = z.zoomRatio,
                            onValueChange = { controller.setZoomRatio(it) },
                            valueRange = z.minZoomRatio..z.maxZoomRatio,
                        )
                    }
                }
            }
            if (exposure?.isExposureCompensationSupported == true) {
                val r = exposure.exposureCompensationRange
                val step = exposure.exposureCompensationStep.toFloat()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EV %+.1f".format(expIndex * step), color = Color.White, modifier = Modifier.padding(end = 8.dp))
                    Slider(
                        value = expIndex,
                        onValueChange = {
                            expIndex = it.roundToInt().toFloat()
                            controller.cameraControl?.setExposureCompensationIndex(expIndex.roundToInt())
                        },
                        valueRange = r.lower.toFloat()..r.upper.toFloat(),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                // Mode switch
                CamButton(
                    if (mode == CaptureMode.PHOTO) Icons.Filled.FiberManualRecord else Icons.Filled.BurstMode,
                    if (mode == CaptureMode.PHOTO) "Switch to video" else "Switch to photo",
                ) { if (recording == null) mode = if (mode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO }

                // Shutter / record
                IconButton(
                    onClick = {
                        if (mode == CaptureMode.PHOTO) shutter()
                        else if (recording == null) startVideo() else recording?.stop()
                    },
                    modifier = Modifier.size(76.dp).background(if (mode == CaptureMode.VIDEO) Color.Red else Color.White, CircleShape),
                ) {
                    Icon(
                        if (mode == CaptureMode.VIDEO && recording != null) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = if (mode == CaptureMode.PHOTO) "Take photo" else if (recording == null) "Start recording" else "Stop recording",
                        tint = if (mode == CaptureMode.VIDEO) Color.White else Color.Black,
                        modifier = Modifier.size(40.dp),
                    )
                }

                if (recording != null) {
                    CamButton(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Resume" else "Pause") {
                        if (paused) recording?.resume() else recording?.pause()
                    }
                } else {
                    CamButton(Icons.Filled.Cameraswitch, "Switch camera") {
                        val target = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                        if (controller.hasCamera(target)) front = !front else say("No ${if (front) "rear" else "front"} camera on this device")
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun CamButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f),
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
        ),
        modifier = Modifier.padding(horizontal = 3.dp),
    ) { Icon(icon, contentDescription = description) }
}
