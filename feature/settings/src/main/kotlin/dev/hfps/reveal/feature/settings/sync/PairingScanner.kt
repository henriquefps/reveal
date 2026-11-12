package dev.hfps.reveal.feature.settings.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.settings.R
import java.util.concurrent.Executors

/**
 * The camera reading the server's pairing QR code (ADR 0018 "Pairing"): CameraX preview + analysis, decoded with
 * ZXing's core (Apache-2.0, no Play services). [onLink] gets the first `revealsync://` text it reads.
 */
@Composable
fun PairingScanner(onLink: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp).testTag(SyncTags.SCANNER),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (denied) R.string.sync_camera_denied else R.string.sync_scan_hint),
            style = RevealTheme.typography.body,
            color = RevealTheme.colors.mutedForeground,
        )
        if (granted) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .background(RevealTheme.colors.muted, RevealTheme.shapes.card)
                    .border(1.dp, RevealTheme.colors.border, RevealTheme.shapes.card),
            ) {
                CameraPreview(onLink, Modifier.fillMaxSize())
            }
        }
        GhostButton(stringResource(R.string.sync_cancel), onClose)
    }
}

@Composable
private fun CameraPreview(onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onLink)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var done = false
        future.addListener(
            {
                val cameras = future.get()
                provider = cameras
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    val text = image.use(::decodeQr)
                    if (text != null && text.startsWith("revealsync:", ignoreCase = true) && !done) {
                        done = true
                        ContextCompat.getMainExecutor(context).execute { latest(text) }
                    }
                }
                runCatching {
                    cameras.unbindAll()
                    cameras.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

private val reader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
}

/** The QR text in the luminance plane of [image], or null. */
private fun decodeQr(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val stride = plane.rowStride
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    val source = PlanarYUVLuminanceSource(bytes, stride, image.height, 0, 0, image.width, image.height, false)
    return try {
        synchronized(reader) { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }
    } catch (_: NotFoundException) {
        null
    } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
        null
    } finally {
        synchronized(reader) { reader.reset() }
    }
}
