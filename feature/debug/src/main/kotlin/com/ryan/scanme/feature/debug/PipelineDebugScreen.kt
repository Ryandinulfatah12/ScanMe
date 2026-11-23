package com.ryan.scanme.feature.debug

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryan.scanme.core.imaging.enhance.FilterPreset
import com.ryan.scanme.core.ui.theme.ScanMeColors

/**
 * Debug-only screen: every pipeline stage side by side for one gallery photo, with filter parameter
 * sliders, per-stage timings and the paper-whiteness check from the acceptance criteria.
 */
@Composable
fun PipelineDebugScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PipelineDebugViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::load)
    }
    var zoomed by remember { mutableStateOf<StageImage?>(null) }

    Column(modifier.fillMaxSize().background(ScanMeColors.Background).systemBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Kembali", tint = ScanMeColors.Ink)
            }
            Text("Pipeline debug", style = MaterialTheme.typography.titleMedium, color = ScanMeColors.Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text("Pilih foto")
            }
        }
        if (ui.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.padding(top = 4.dp))
        ui.error?.let { Text(it, color = ScanMeColors.Danger, modifier = Modifier.padding(horizontal = 16.dp)) }

        if (!ui.hasImage) {
            Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text(
                    "Pilih foto dokumen dari galeri. Setiap tahap pipeline (deteksi, warp, iluminasi, filter) " +
                        "ditampilkan berdampingan; slider mengubah parameter filter secara langsung.",
                    color = ScanMeColors.Muted,
                )
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Summary(ui) }
            item(span = { GridItemSpan(maxLineSpan) }) { ParamsPanel(ui.params, viewModel::setParams) }
            items(ui.stages, key = { it.name }) { stage ->
                Column(Modifier.clickable { zoomed = stage }) {
                    Image(
                        bitmap = stage.bitmap.asImageBitmap(),
                        contentDescription = stage.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF202020)),
                    )
                    Text(stage.name, style = MaterialTheme.typography.labelMedium, color = ScanMeColors.Ink)
                    Text(stage.info, style = MaterialTheme.typography.labelSmall, color = ScanMeColors.Muted)
                }
            }
        }
    }

    zoomed?.let { ZoomDialog(it, onDismiss = { zoomed = null }) }
}

@Composable
private fun Summary(ui: DebugUiState) {
    Column {
        ui.detection?.let { Text("Deteksi: $it", color = ScanMeColors.Ink, style = MaterialTheme.typography.bodySmall) }
        ui.timings.forEach { (label, ms) ->
            Text("$label: $ms ms", color = ScanMeColors.Ink, style = MaterialTheme.typography.bodySmall)
        }
        ui.paperLStar?.let { l ->
            val ok = l > 90
            Text(
                "Kertas: median L* ${"%.1f".format(l)} (target > 90) · ${"%.1f".format((ui.paperBelow85 ?: 0.0) * 100)}% di bawah L* 85",
                color = if (ok) ScanMeColors.Ink else ScanMeColors.Danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamsPanel(p: DebugParams, onChange: (DebugParams) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterPreset.entries.forEach { preset ->
                FilterChip(
                    selected = p.preset == preset,
                    onClick = { onChange(p.copy(preset = preset)) },
                    label = { Text(preset.name.lowercase()) },
                )
            }
            TextButton(onClick = { onChange(DebugParams(preset = p.preset)) }) { Text("Reset") }
        }
        Text("Iluminasi (auto/gray/b&w)", style = MaterialTheme.typography.labelLarge, color = ScanMeColors.Muted)
        ParamSlider("dilate", p.dilateFraction, 0.004f..0.04f, "%.3f") { onChange(p.copy(dilateFraction = it)) }
        ParamSlider("median", p.medianFraction, 0.01f..0.08f, "%.3f") { onChange(p.copy(medianFraction = it)) }
        ParamSlider("objectDark", p.objectDarkness, 0.5f..1f, "%.2f") { onChange(p.copy(objectDarkness = it)) }
        Text("Tonal (auto/gray)", style = MaterialTheme.typography.labelLarge, color = ScanMeColors.Muted)
        ParamSlider("clahe", p.claheClip, 0f..4f, "%.2f") { onChange(p.copy(claheClip = it)) }
        ParamSlider("whitePt", p.whitePoint, 180f..255f, "%.0f") { onChange(p.copy(whitePoint = it)) }
        ParamSlider("sharpen", p.sharpenAmount, 0f..1.5f, "%.2f") { onChange(p.copy(sharpenAmount = it)) }
        ParamSlider("chroma", p.chromaBoost, 1f..1.6f, "%.2f") { onChange(p.copy(chromaBoost = it)) }
        Text("B&W", style = MaterialTheme.typography.labelLarge, color = ScanMeColors.Muted)
        ParamSlider("sauvola k", p.sauvolaK, 0.05f..0.6f, "%.2f") { onChange(p.copy(sauvolaK = it)) }
        ParamSlider("window", p.sauvolaWindow, 0.005f..0.06f, "%.3f") { onChange(p.copy(sauvolaWindow = it)) }
        ParamSlider("inkL", p.definiteInkL, 40f..180f, "%.0f") { onChange(p.copy(definiteInkL = it)) }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onChange: (Double) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ScanMeColors.Ink, modifier = Modifier.width(84.dp))
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toDouble()) }, valueRange = range, modifier = Modifier.weight(1f))
        Text(format.format(value), style = MaterialTheme.typography.bodySmall, color = ScanMeColors.Ink, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun ZoomDialog(stage: StageImage, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 8f)
            offset += pan
        }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
            Image(
                bitmap = stage.bitmap.asImageBitmap(),
                contentDescription = stage.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Text(
                "${stage.name} · ${stage.info}",
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(16.dp),
            )
        }
    }
}
