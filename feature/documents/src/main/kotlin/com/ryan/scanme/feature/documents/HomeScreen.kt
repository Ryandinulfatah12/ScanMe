package com.ryan.scanme.feature.documents

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryan.scanme.core.domain.model.Document
import com.ryan.scanme.core.ui.component.ActionCard
import com.ryan.scanme.core.ui.component.CircleActionButton
import com.ryan.scanme.core.ui.theme.ScanMeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onImport: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
    /** Pipeline debug screen; null hides the button (release builds). */
    onDebug: (() -> Unit)? = null,
    viewModel: DocumentsViewModel = hiltViewModel(),
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    var showHelp by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Document?>(null) }
    var deleting by remember { mutableStateOf<Document?>(null) }

    Box(
        modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                    Image(
                        painter = painterResource(com.ryan.scanme.core.ui.R.drawable.logo_mark),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).shadow(6.dp, RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("ScanMe", style = MaterialTheme.typography.titleLarge, color = ScanMeColors.Ink)
                    Spacer(Modifier.weight(1f))
                    onDebug?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Outlined.BugReport, contentDescription = "Pipeline debug", tint = ScanMeColors.Muted)
                        }
                    }
                    IconButton(onClick = onAbout) {
                        Icon(Icons.Outlined.Info, contentDescription = "Tentang", tint = ScanMeColors.Ink)
                    }
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Bantuan", tint = ScanMeColors.Ink)
                    }
                }
            }
            item {
                Spacer(Modifier.height(if (documents.isNullOrEmpty()) 64.dp else 24.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = ScanMeColors.Muted)) { append("Halo,\n") }
                        append("Mau scan apa\nhari ini?")
                    },
                    style = MaterialTheme.typography.displaySmall,
                    color = ScanMeColors.Ink,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionCard(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "Scan",
                        subtitle = "Dokumen, multi-halaman",
                        onClick = onScan,
                        modifier = Modifier.weight(1f),
                    )
                    ActionCard(
                        icon = Icons.Outlined.PhotoLibrary,
                        title = "Impor",
                        subtitle = "Foto dari galeri",
                        onClick = onImport,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val docs = documents.orEmpty()
            if (docs.isNotEmpty()) {
                item {
                    Text(
                        "Dokumen",
                        style = MaterialTheme.typography.titleMedium,
                        color = ScanMeColors.Ink,
                        modifier = Modifier.padding(start = 8.dp, top = 28.dp, bottom = 8.dp),
                    )
                }
                items(docs, key = { it.id }) { doc ->
                    DocumentRow(
                        doc,
                        onOpen = { onOpenDocument(doc.id) },
                        onRename = { renaming = doc },
                        onDelete = { deleting = doc },
                    )
                }
            } else if (documents != null) {
                item {
                    Text(
                        "Dokumen yang kamu simpan akan muncul di sini.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ScanMeColors.Muted,
                        modifier = Modifier.padding(start = 8.dp, top = 28.dp),
                    )
                }
            }
        }

        CircleActionButton(
            icon = Icons.Outlined.Add,
            contentDescription = "Scan baru",
            onClick = onScan,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp),
            size = 64.dp,
        )
    }

    renaming?.let { doc ->
        var title by remember(doc.id) { mutableStateOf(doc.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Ganti nama") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(doc.id, title)
                    renaming = null
                }, enabled = title.isNotBlank()) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Batal") } },
        )
    }

    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Hapus \"${doc.title}\"?") },
            text = { Text("${doc.pages.size} halaman dan fotonya dihapus dari HP. Tindakan ini tidak bisa dibatalkan.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(doc.id)
                    deleting = null
                }) { Text("Hapus", color = ScanMeColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } },
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Mengerti") } },
            title = { Text("Tips scan") },
            text = {
                Text(
                    "• Letakkan kertas di permukaan yang lebih gelap dari kertasnya.\n" +
                        "• Tahan HP diam sebentar; dengan mode Auto foto diambil otomatis.\n" +
                        "• Bayangan tangan dan pencahayaan tidak rata dibersihkan otomatis.\n" +
                        "• Halaman buku melengkung diluruskan otomatis (tombol Lurus).\n" +
                        "• Tepi kurang pas? Pakai Crop dan geser titik sudutnya.\n" +
                        "• Tekan Simpan agar dokumen muncul di daftar dan bisa diedit lagi.",
                )
            },
        )
    }
}

private val dateFormat = SimpleDateFormat("d MMM yyyy, HH.mm", Locale("id"))

@Composable
private fun DocumentRow(doc: Document, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(doc.pages.firstOrNull()?.thumbnailPath)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.title, style = MaterialTheme.typography.titleSmall, color = ScanMeColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${doc.pages.size} halaman · ${dateFormat.format(Date(doc.updatedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = ScanMeColors.Muted,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Menu ${doc.title}", tint = ScanMeColors.Muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Hapus", color = ScanMeColors.Danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String?) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    }
    Box(
        Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(6.dp)).background(ScanMeColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Icon(Icons.Outlined.Description, contentDescription = null, tint = ScanMeColors.Muted)
    }
}
