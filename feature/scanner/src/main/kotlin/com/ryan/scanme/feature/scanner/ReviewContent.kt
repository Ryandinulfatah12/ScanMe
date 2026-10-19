package com.ryan.scanme.feature.scanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryan.scanme.core.imaging.enhance.FilterPreset
import com.ryan.scanme.core.ui.component.CircleActionButton
import com.ryan.scanme.core.ui.component.MessagePill
import com.ryan.scanme.core.ui.component.ToolbarAction
import com.ryan.scanme.core.ui.theme.ScanMeColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewContent(
    state: ScanUiState.Review,
    viewModel: ScannerViewModel,
    onClose: () -> Unit,
    showTimings: Boolean,
) {
    val context = LocalContext.current
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val page = pages.getOrNull(state.index) ?: return
    val whatsApp = remember { installedWhatsApp(context) }
    var showShare by remember { mutableStateOf(false) }
    BackHandler { viewModel.addPage() }

    Column(Modifier.fillMaxSize().background(ScanMeColors.Background)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Tutup", tint = ScanMeColors.Ink) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Dokumen", style = MaterialTheme.typography.titleMedium, color = ScanMeColors.Ink)
                Text("Halaman ${state.index + 1} dari ${pages.size}", style = MaterialTheme.typography.labelSmall, color = ScanMeColors.Muted)
            }
            Spacer(Modifier.width(48.dp))
        }

        // Page preview with the floating share button
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Image(
                bitmap = state.preview.asImageBitmap(),
                contentDescription = "Hasil scan",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp, vertical = 12.dp)
                    .shadow(10.dp, RoundedCornerShape(2.dp)),
            )
            CircleActionButton(
                icon = Icons.Outlined.IosShare,
                contentDescription = "Bagikan",
                onClick = { showShare = true },
                container = ScanMeColors.Accent,
                size = 56.dp,
                enabled = state.busy == null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 12.dp),
            )
            (state.busy ?: state.message)?.let {
                MessagePill(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp, start = 24.dp, end = 24.dp))
            }
            if (state.message != null) {
                LaunchedEffect(state.message) {
                    delay(5000)
                    viewModel.clearMessage()
                }
            }
        }

        if (showTimings) {
            Text(
                "%dx%d px • warp %d ms • filter %d ms".format(state.pageWidth, state.pageHeight, state.rectifyMs, state.renderMs),
                style = MaterialTheme.typography.labelSmall,
                color = ScanMeColors.Muted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        if (!state.detected) {
            Text(
                "Tepi dokumen tidak terdeteksi — rapikan dengan Crop",
                style = MaterialTheme.typography.labelSmall,
                color = ScanMeColors.Overlay,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
            )
        }

        if (pages.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                    val selected = i == state.index
                    Box(
                        Modifier
                            .size(44.dp, 58.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScanMeColors.Surface)
                            .border(if (selected) 2.dp else 1.dp, if (selected) ScanMeColors.Accent else ScanMeColors.SurfaceMuted, RoundedCornerShape(6.dp))
                            .clickable { viewModel.openPage(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        p.thumbnail?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            ?: Text("${i + 1}", color = ScanMeColors.Muted)
                    }
                }
            }
        }

        FilterRow(selected = page.preset, onSelect = viewModel::selectPreset)

        // Bottom toolbar
        Row(
            Modifier
                .fillMaxWidth()
                .background(ScanMeColors.Surface)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToolbarAction(Icons.Outlined.AddPhotoAlternate, "Tambah", viewModel::addPage)
            ToolbarAction(Icons.Outlined.CameraAlt, "Ulangi", viewModel::retake)
            ToolbarAction(Icons.Outlined.Crop, "Crop", viewModel::startCrop)
            ToolbarAction(Icons.Outlined.Rotate90DegreesCw, "Putar", viewModel::rotate)
            ToolbarAction(Icons.Outlined.DeleteOutline, "Hapus", viewModel::deletePage, tint = ScanMeColors.Danger)
        }
    }

    if (showShare) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showShare = false }, sheetState = sheet, containerColor = ScanMeColors.Surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Kirim ${pages.size} halaman sebagai PDF", style = MaterialTheme.typography.titleMedium, color = ScanMeColors.Ink)
                Spacer(Modifier.height(12.dp))
                if (whatsApp != null) {
                    ShareOption(Icons.Outlined.Share, "WhatsApp", "Pilih kontak atau grup", ScanMeColors.WhatsApp) {
                        showShare = false
                        viewModel.sharePdf(whatsApp)
                    }
                }
                ShareOption(Icons.Outlined.IosShare, "Bagikan ke aplikasi lain", "Gmail, Drive, Telegram…", ScanMeColors.Accent) {
                    showShare = false
                    viewModel.sharePdf(null)
                }
                ShareOption(Icons.Outlined.Download, "Simpan ke Download", "Folder Download/ScanMe", ScanMeColors.Ink) {
                    showShare = false
                    viewModel.savePdf()
                }
            }
        }
    }
}

@Composable
private fun ShareOption(icon: ImageVector, title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ScanMeColors.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ScanMeColors.Muted)
        }
    }
}

/** Pill-shaped filter selector; the selected one is filled with the accent colour. */
@Composable
private fun FilterRow(selected: FilterPreset, onSelect: (FilterPreset) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        itemsIndexed(FilterPreset.entries) { _, preset ->
            val isSelected = preset == selected
            Text(
                presetLabel(preset),
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Color.White else ScanMeColors.Ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) ScanMeColors.Accent else ScanMeColors.Surface)
                    .clickable { onSelect(preset) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

internal fun presetLabel(preset: FilterPreset) = when (preset) {
    FilterPreset.ORIGINAL -> "Original"
    FilterPreset.AUTO_COLOR -> "Auto"
    FilterPreset.GRAYSCALE -> "Grayscale"
    FilterPreset.BLACK_WHITE -> "Hitam putih"
}
