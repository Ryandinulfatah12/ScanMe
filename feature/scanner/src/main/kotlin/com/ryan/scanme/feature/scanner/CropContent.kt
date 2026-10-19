package com.ryan.scanme.feature.scanner

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.ryan.scanme.core.ui.theme.ScanMeColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manual crop: drag the four corners over the original photo. While dragging, a loupe at the top shows the
 * area under the finger magnified (the finger itself hides the corner, which is why scanners need one).
 */
@Composable
internal fun CropContent(
    photo: Bitmap,
    initial: Quad,
    autoQuad: Quad?,
    onApply: (Quad) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val image = remember(photo) { photo.asImageBitmap() }
    var quad by remember(initial) { mutableStateOf(initial) }
    var active by remember { mutableIntStateOf(-1) }

    /** Where the photo is drawn inside a [w]x[h] box (ContentScale.Fit, centred). */
    fun imageRect(w: Float, h: Float): Rect {
        val scale = min(w / photo.width, h / photo.height)
        val dw = photo.width * scale
        val dh = photo.height * scale
        return Rect(Offset((w - dw) / 2, (h - dh) / 2), Size(dw, dh))
    }

    Column(Modifier.fillMaxSize().background(ScanMeColors.Background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Batal", color = ScanMeColors.Accent) }
            Text(
                "Crop",
                style = MaterialTheme.typography.titleMedium,
                color = ScanMeColors.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onApply(quad) }, enabled = quad.isConvex()) {
                Text("Terapkan", color = if (quad.isConvex()) ScanMeColors.Accent else ScanMeColors.Muted)
            }
        }
        Text(
            "Geser titik sudut ke tepi dokumen",
            color = ScanMeColors.Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .pointerInput(photo) {
                    val touchRadius = 48.dp.toPx()
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = imageRect(size.width.toFloat(), size.height.toFloat())
                            val corners = quad.corners.map { Offset(r.left + (it.x * r.width).toFloat(), r.top + (it.y * r.height).toFloat()) }
                            val nearest = corners.indices.minBy { (corners[it] - pos).getDistance() }
                            active = if ((corners[nearest] - pos).getDistance() <= touchRadius) nearest else -1
                        },
                        onDrag = { change, drag ->
                            if (active < 0) return@detectDragGestures
                            change.consume()
                            val r = imageRect(size.width.toFloat(), size.height.toFloat())
                            val c = quad.corners.toMutableList()
                            val p = c[active]
                            c[active] = Vec2(
                                (p.x + drag.x / r.width).coerceIn(0.0, 1.0),
                                (p.y + drag.y / r.height).coerceIn(0.0, 1.0),
                            )
                            quad = Quad(c[0], c[1], c[2], c[3])
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = imageRect(size.width, size.height)
                drawImage(
                    image,
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                )
                val pts = quad.corners.map { Offset(r.left + (it.x * r.width).toFloat(), r.top + (it.y * r.height).toFloat()) }
                val valid = quad.isConvex()
                val color = if (valid) ScanMeColors.Overlay else ScanMeColors.Danger
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, if (valid) ScanMeColors.OverlayFill else color.copy(alpha = 0.2f))
                drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
                pts.forEachIndexed { i, p ->
                    val radius = (if (i == active) 14 else 10).dp.toPx()
                    drawCircle(Color.White, radius = radius, center = p)
                    drawCircle(color, radius = radius, center = p, style = Stroke(3.dp.toPx()))
                }

                if (active >= 0) {
                    // Loupe: 3x magnification of the photo around the active corner, drawn top-centre.
                    val radius = 64.dp.toPx()
                    val zoom = 3f
                    val center = Offset(size.width / 2, radius + 8.dp.toPx())
                    val corner = quad.corners[active]
                    val px = corner.x * photo.width
                    val py = corner.y * photo.height
                    val screenScale = r.width / photo.width
                    val srcHalf = radius / (screenScale * zoom)
                    val loupe = Path().apply { addOval(Rect(center, radius)) }
                    clipPath(loupe) {
                        drawRect(Color.Black, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
                        val srcLeft = (px - srcHalf).roundToInt()
                        val srcTop = (py - srcHalf).roundToInt()
                        val srcSize = (srcHalf * 2).roundToInt().coerceAtLeast(1)
                        // Clamp the source window to the bitmap; shift the destination to keep alignment.
                        val sx = srcLeft.coerceIn(0, photo.width - 1)
                        val sy = srcTop.coerceIn(0, photo.height - 1)
                        val sw = min(srcSize - (sx - srcLeft), photo.width - sx).coerceAtLeast(1)
                        val sh = min(srcSize - (sy - srcTop), photo.height - sy).coerceAtLeast(1)
                        val k = (radius * 2) / srcSize
                        drawImage(
                            image,
                            srcOffset = IntOffset(sx, sy),
                            srcSize = IntSize(sw, sh),
                            dstOffset = IntOffset((center.x - radius + (sx - srcLeft) * k).roundToInt(), (center.y - radius + (sy - srcTop) * k).roundToInt()),
                            dstSize = IntSize((sw * k).roundToInt(), (sh * k).roundToInt()),
                        )
                    }
                    drawCircle(ScanMeColors.Overlay, radius = radius, center = center, style = Stroke(3.dp.toPx()))
                    drawLine(color, center - Offset(14.dp.toPx(), 0f), center + Offset(14.dp.toPx(), 0f), strokeWidth = 2.dp.toPx())
                    drawLine(color, center - Offset(0f, 14.dp.toPx()), center + Offset(0f, 14.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(ScanMeColors.Surface).navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            if (autoQuad != null) CropPill("Deteksi otomatis") { quad = autoQuad }
            CropPill("Seluruh foto") { quad = Quad.FULL_FRAME }
        }
    }
}

@Composable
private fun CropPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = ScanMeColors.Accent,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ScanMeColors.AccentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
