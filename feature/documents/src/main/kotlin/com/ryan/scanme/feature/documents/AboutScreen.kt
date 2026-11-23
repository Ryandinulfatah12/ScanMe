package com.ryan.scanme.feature.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ryan.scanme.core.ui.theme.ScanMeColors

private class License(val name: String, val version: String, val license: String, val url: String)

/**
 * Libraries shipped in the APK (test-only ones such as JUnit and the desktop OpenCV build are not).
 * Keep in sync with gradle/libs.versions.toml when adding or upgrading a dependency.
 */
private val LICENSES = listOf(
    License("OpenCV", "4.9.0", "Apache License 2.0", "https://opencv.org/license/"),
    License("Kotlin standard library", "2.0.21", "Apache License 2.0", "https://kotlinlang.org"),
    License("kotlinx.coroutines", "1.9.0", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    License("Jetpack Compose (UI, Material 3, Material Icons)", "BOM 2024.12.01", "Apache License 2.0", "https://developer.android.com/jetpack/compose"),
    License("AndroidX Core / Activity", "1.15.0 / 1.9.3", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    License("AndroidX Lifecycle", "2.8.7", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
    License("AndroidX Navigation", "2.8.5", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/navigation"),
    License("CameraX", "1.4.1", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/camera"),
    License("Room", "2.6.1", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/room"),
    License("Dagger Hilt / AndroidX Hilt Navigation", "2.52 / 1.2.0", "Apache License 2.0", "https://dagger.dev/hilt/"),
)

@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Column(modifier.fillMaxSize().background(ScanMeColors.Background).systemBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Kembali", tint = ScanMeColors.Ink)
            }
            Text("Tentang ScanMe", style = MaterialTheme.typography.titleMedium, color = ScanMeColors.Ink)
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text("Versi $version", style = MaterialTheme.typography.bodyMedium, color = ScanMeColors.Ink)
                Text(
                    "Semua pemrosesan gambar berjalan di HP; foto tidak dikirim ke server mana pun.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ScanMeColors.Muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )
                Text("Lisensi open-source", style = MaterialTheme.typography.titleSmall, color = ScanMeColors.Ink)
            }
            items(LICENSES) { lib ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("${lib.name} ${lib.version}", style = MaterialTheme.typography.bodyMedium, color = ScanMeColors.Ink)
                    Text(lib.license, style = MaterialTheme.typography.bodySmall, color = ScanMeColors.Accent)
                    Text(lib.url, style = MaterialTheme.typography.bodySmall, color = ScanMeColors.Muted)
                }
                HorizontalDivider(color = ScanMeColors.SurfaceMuted)
            }
            item {
                Text(
                    "Teks lengkap Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = ScanMeColors.Muted,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
