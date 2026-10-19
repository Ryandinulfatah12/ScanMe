package com.ryan.scanme.feature.scanner

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryan.scanme.core.ui.component.MessagePill
import com.ryan.scanme.core.ui.theme.ScanMeColors
import kotlinx.coroutines.delay

/**
 * Scanner flow host: camera -> review (multi-page) -> crop, plus gallery import and PDF sharing.
 * The camera stays bound underneath the other screens so returning to it is instant.
 */
@Composable
fun ScannerScreen(
    startWithImport: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }

    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT)) { uris ->
        if (uris.isEmpty()) viewModel.onImportCancelled() else viewModel.importImages(uris)
    }
    val openPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    var importLaunched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startWithImport && !importLaunched) {
            importLaunched = true
            openPicker()
        } else if (!cameraGranted) {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.shareRequests.collect { request ->
            runCatching { sharePdf(context, request) }.onFailure { viewModel.onShareFailed(it.message) }
        }
    }

    val close = { if (pages.isNotEmpty()) confirmDiscard = true else onClose() }
    val debuggable = remember { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (cameraGranted) {
            CameraContent(viewModel, onCancel = close, onImport = openPicker, showTimings = debuggable)
        } else if (ui == ScanUiState.Camera) {
            PermissionContent(onGrant = { permission.launch(Manifest.permission.CAMERA) }, onImport = openPicker, onClose = close)
        }
        when (val state = ui) {
            ScanUiState.Camera -> Unit
            is ScanUiState.Processing -> ProcessingContent(state.message)
            is ScanUiState.Review -> ReviewContent(state, viewModel, onClose = close, showTimings = debuggable)
            is ScanUiState.Crop -> CropContent(
                photo = state.photo,
                initial = state.quad,
                autoQuad = state.autoQuad,
                onApply = viewModel::applyCrop,
                onCancel = viewModel::cancelCrop,
            )
        }
        error?.let {
            MessagePill(it, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp))
            LaunchedEffect(it) {
                delay(4000)
                viewModel.clearError()
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Buang ${pages.size} halaman?") },
            text = { Text("Halaman yang belum disimpan atau dibagikan akan hilang.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discardAll()
                    onClose()
                }) { Text("Buang", color = ScanMeColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun ProcessingContent(message: String) {
    Box(Modifier.fillMaxSize().background(ScanMeColors.Background), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = ScanMeColors.Accent)
            Spacer(Modifier.height(16.dp))
            Text(message, color = ScanMeColors.Ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PermissionContent(onGrant: () -> Unit, onImport: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(ScanMeColors.Background).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Izinkan kamera", style = MaterialTheme.typography.titleLarge, color = ScanMeColors.Ink)
        Text(
            "Kamera dipakai untuk memindai dokumen. Foto tidak dikirim ke mana pun.",
            textAlign = TextAlign.Center,
            color = ScanMeColors.Muted,
        )
        Button(onClick = onGrant) { Text("Beri izin") }
        TextButton(onClick = onImport) { Text("Impor dari galeri") }
        TextButton(onClick = onClose) { Text("Kembali") }
    }
}

private const val MAX_IMPORT = 20

private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

/** WhatsApp or WhatsApp Business package if installed (visible via <queries> in :core:data's manifest). */
internal fun installedWhatsApp(context: Context): String? = WHATSAPP_PACKAGES.firstOrNull { pkg ->
    runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
}

/**
 * Starts the share. Read access is granted explicitly to every app that can receive the PDF, not only via
 * the Intent flag: an Intent grant lives only as long as the receiving activity, but WhatsApp uploads in a
 * background job after its share screen has closed (and the grant would also vanish if ScanMe's task were
 * swiped away). Explicit grants last until revoked or reboot. The target also opens in its own task.
 */
private fun sharePdf(context: Context, request: ShareRequest) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, request.uri)
        // ClipData carries the read grant to targets that ignore EXTRA_STREAM grants.
        clipData = ClipData.newRawUri("", request.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        request.packageName?.let { setPackage(it) }
    }
    val receivers = request.packageName?.let { listOf(it) }
        ?: context.packageManager.queryIntentActivities(send, 0).map { it.activityInfo.packageName }.distinct()
    receivers.forEach { context.grantUriPermission(it, request.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }

    val launch = if (request.packageName != null) send else Intent.createChooser(send, "Bagikan PDF")
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launch)
}
