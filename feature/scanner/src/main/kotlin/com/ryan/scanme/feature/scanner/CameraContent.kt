package com.ryan.scanme.feature.scanner

import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryan.scanme.core.camera.LiveDetection
import com.ryan.scanme.core.camera.focalToSensorLongSide
import com.ryan.scanme.core.imaging.geometry.Vec2
import com.ryan.scanme.core.imaging.live.LiveState
import com.ryan.scanme.core.ui.component.MessagePill
import com.ryan.scanme.core.ui.theme.ScanMeColors
import java.util.concurrent.Executors
import kotlin.math.max

@Composable
internal fun CameraContent(
    viewModel: ScannerViewModel,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    showTimings: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val live by viewModel.analyzer.detections.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val autoCapture by viewModel.autoCapture.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var torch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val callbackExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // Analysis at ~640x480: the live-detection budget (< 50 ms/frame) is defined at <= 640 px.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, viewModel.analyzer) }
            // ~12 MP is ~340 DPI on A4, well above the 200 DPI export; full 50 MP sensors only add capture,
            // decode and processing time without visible gain.
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build(),
                )
                .build()
            provider.unbindAll()
            val bound = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
            viewModel.focalRatio = bound.cameraInfo.focalToSensorLongSide()
            camera = bound
            imageCapture = capture
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            analysisExecutor.shutdown()
        }
    }

    fun takePicture() {
        val capture = imageCapture ?: return
        if (!viewModel.onCaptureStarted()) return
        capture.takePicture(callbackExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) = viewModel.onPhotoCaptured(image)
            override fun onError(exception: ImageCaptureException) = viewModel.onCaptureFailed(exception.message)
        })
    }

    val state = live?.state
    LaunchedEffect(state, autoCapture, ui) {
        if (autoCapture && state == LiveState.ReadyToCapture && ui == ScanUiState.Camera) takePicture()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (grid) GridOverlay(Modifier.fillMaxSize())
        QuadOverlay(live, Modifier.fillMaxSize())

        // Top bar: Batal | flash | grid | Auto
        Row(
            Modifier
                .fillMaxWidth()
                .background(ScanMeColors.CameraBar)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Batal", color = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                torch = !torch
                camera?.cameraControl?.enableTorch(torch)
            }) {
                Icon(if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, contentDescription = "Flash", tint = Color.White)
            }
            IconButton(onClick = { grid = !grid }) {
                Icon(Icons.Outlined.Grid3x3, contentDescription = "Grid", tint = if (grid) ScanMeColors.AccentSoft else Color.White)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.setAutoCapture(!autoCapture) }) {
                Text(if (autoCapture) "Auto" else "Manual", color = Color.White)
            }
        }

        val hint = hintText(live)
        if (hint != null || showTimings) {
            MessagePill(
                listOfNotNull(hint, live?.takeIf { showTimings }?.let { "%.0f ms/frame".format(it.detectMs) }).joinToString(" • "),
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
            )
        }

        // Bottom: gallery | shutter | page stack
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp, start = 28.dp, end = 28.dp),
        ) {
            IconButton(onClick = onImport, modifier = Modifier.align(Alignment.CenterStart).size(52.dp)) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Impor dari galeri", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            ShutterButton(
                progress = when (state) {
                    is LiveState.Steadying -> state.progress.toFloat()
                    LiveState.ReadyToCapture -> 1f
                    else -> 0f
                },
                onClick = ::takePicture,
                modifier = Modifier.align(Alignment.Center),
            )
            if (pages.isNotEmpty() && ui == ScanUiState.Camera) {
                PageStack(
                    thumbnail = pages.last().thumbnail,
                    count = pages.size,
                    onClick = viewModel::backToReview,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

private fun hintText(live: LiveDetection?): String? = when {
    live?.cutOff == true -> "Dokumen terpotong — jauhkan HP sedikit"
    else -> stateHint(live)
}

private fun stateHint(live: LiveDetection?): String? = when (live?.state) {
    null, LiveState.NoDocument -> "Arahkan kamera ke dokumen"
    LiveState.Unstable -> "Tahan stabil"
    is LiveState.Steadying -> "Tahan stabil…"
    LiveState.ReadyToCapture -> null
    LiveState.WaitingForNextPage -> "Ganti halaman berikutnya"
}

/** White disc inside a ring; the ring fills with the accent colour while the user holds steady. */
@Composable
private fun ShutterButton(progress: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(80.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = GeometrySize(size.width - stroke, size.height - stroke)
            drawCircle(Color.White.copy(alpha = 0.6f), radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (progress > 0f) {
                drawArc(
                    ScanMeColors.Accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            drawCircle(Color.White, radius = size.minDimension / 2 - stroke * 2.4f)
        }
    }
}

/** Last page thumbnail, slightly tilted like a paper stack, with a count badge. */
@Composable
private fun PageStack(thumbnail: android.graphics.Bitmap?, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(
                Modifier
                    .size(46.dp, 60.dp)
                    .rotate(-6f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(2.dp, Color.White, RoundedCornerShape(4.dp)),
            ) {
                thumbnail?.let {
                    Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                "$count",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(ScanMeColors.Overlay)
                    .padding(top = 3.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Lihat halaman", tint = Color.White)
    }
}

@Composable
private fun GridOverlay(modifier: Modifier) {
    Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), strokeWidth = 1.dp.toPx())
            drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), strokeWidth = 1.dp.toPx())
        }
    }
}

/** Violet outline with a soft lavender fill. Maps the upright analysis frame onto the FILL_CENTER preview. */
@Composable
private fun QuadOverlay(live: LiveDetection?, modifier: Modifier) {
    Canvas(modifier) {
        val d = live ?: return@Canvas
        val q = d.quad ?: return@Canvas
        val scale = max(size.width / d.frameWidth, size.height / d.frameHeight)
        val ox = (size.width - d.frameWidth * scale) / 2
        val oy = (size.height - d.frameHeight * scale) / 2
        fun map(v: Vec2) = Offset((v.x * d.frameWidth * scale + ox).toFloat(), (v.y * d.frameHeight * scale + oy).toFloat())
        val path = Path().apply {
            val c = q.corners.map(::map)
            moveTo(c[0].x, c[0].y)
            c.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        // Amber when the page runs off the frame, violet otherwise.
        val stroke = if (d.cutOff) Color(0xFFFFB020) else ScanMeColors.Overlay
        drawPath(path, if (d.cutOff) stroke.copy(alpha = 0.18f) else ScanMeColors.OverlayFill)
        drawPath(path, stroke, style = Stroke(width = 4.dp.toPx()))
    }
}
