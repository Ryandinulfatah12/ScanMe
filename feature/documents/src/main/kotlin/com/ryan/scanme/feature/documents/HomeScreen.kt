package com.ryan.scanme.feature.documents

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ryan.scanme.core.ui.component.ActionCard
import com.ryan.scanme.core.ui.component.CircleActionButton
import com.ryan.scanme.core.ui.theme.ScanMeColors

@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHelp by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                Image(
                    painter = painterResource(com.ryan.scanme.core.ui.R.drawable.logo_mark),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).shadow(6.dp, RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text("ScanMe", style = MaterialTheme.typography.titleLarge, color = ScanMeColors.Ink)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showHelp = true }) {
                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Bantuan", tint = ScanMeColors.Ink)
                }
            }
            Spacer(Modifier.height(64.dp))
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

        CircleActionButton(
            icon = Icons.Outlined.Add,
            contentDescription = "Scan baru",
            onClick = onScan,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp),
            size = 64.dp,
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
                        "• Tepi kurang pas? Pakai Crop dan geser titik sudutnya.\n" +
                        "• Kirim hasilnya sebagai PDF langsung ke WhatsApp.",
                )
            },
        )
    }
}
