package com.mediavault.feature.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/** Shows the source image with four draggable corner handles. Corners are normalised (0..1) to the image. */
@Composable
fun CropEditor(bitmap: Bitmap, corners: List<Pt>, onChange: (List<Pt>) -> Unit, modifier: Modifier = Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val current by rememberUpdatedState(corners)
    val emit by rememberUpdatedState(onChange)

    val scale = if (box.width == 0 || box.height == 0) 1f else min(box.width.toFloat() / bitmap.width, box.height.toFloat() / bitmap.height)
    val dispW = bitmap.width * scale
    val dispH = bitmap.height * scale
    val ox = (box.width - dispW) / 2f
    val oy = (box.height - dispH) / 2f

    fun toView(p: Pt) = Offset(ox + p.x * dispW, oy + p.y * dispH)

    Box(modifier.fillMaxSize().onSizeChanged { box = it }) {
        Image(bitmap.asImageBitmap(), contentDescription = "Page to crop", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(
            Modifier.fillMaxSize().pointerInput(box, bitmap) {
                var active = -1
                detectDragGestures(
                    onDragStart = { start ->
                        val handles = current.map { toView(it) }
                        val (idx, dist) = handles.mapIndexed { i, o -> i to (o - start).getDistance() }.minByOrNull { it.second }!!
                        active = if (dist < 72f * density) idx else -1
                    },
                    onDrag = { change, _ ->
                        if (active >= 0 && dispW > 0 && dispH > 0) {
                            change.consume()
                            val nx = ((change.position.x - ox) / dispW).coerceIn(0f, 1f)
                            val ny = ((change.position.y - oy) / dispH).coerceIn(0f, 1f)
                            emit(current.toMutableList().also { it[active] = Pt(nx, ny) })
                        }
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                )
            },
        ) {
            val pts = corners.map { toView(it) }
            if (pts.size == 4) {
                for (i in 0 until 4) drawLine(Color(0xFF00B0FF), pts[i], pts[(i + 1) % 4], strokeWidth = 4f)
                pts.forEach {
                    drawCircle(Color.White, 22f, it)
                    drawCircle(Color(0xFF00B0FF), 16f, it)
                }
            }
        }
    }
}
