package dev.hermes.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScanner(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestResult by rememberUpdatedState(onResult)
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var cameraError by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("setup-scan-close")) { Icon(Icons.Rounded.Close, contentDescription = "Close QR scanner") }
            Text("Scan Cypher QR", style = MaterialTheme.typography.titleLarge)
        }
        Text("Point the camera at the QR code on your Hermes dashboard.", style = MaterialTheme.typography.bodyLarge)
        if (!permitted) {
            Text("Camera access is used only while scanning the QR code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("setup-camera-permission")) { Text("Allow camera") }
        } else {
            val options = remember { BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build() }
            val scanner = remember(options) { BarcodeScanning.getClient(options) }
            val boundUseCases = remember { mutableStateOf<Pair<ProcessCameraProvider, Pair<Preview, ImageAnalysis>>?>(null) }
            val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
            val detected = remember { AtomicBoolean(false) }
            val active = remember { AtomicBoolean(false) }
            val mainHandler = remember { Handler(Looper.getMainLooper()) }
            DisposableEffect(lifecycleOwner, scanner, cameraExecutor) {
                active.set(true)
                onDispose {
                    active.set(false)
                    boundUseCases.value?.let { (provider, useCases) -> provider.unbind(useCases.first, useCases.second) }
                    boundUseCases.value = null
                    scanner.close()
                    cameraExecutor.shutdown()
                }
            }
            if (cameraError.isNotBlank()) Text(cameraError, color = MaterialTheme.colorScheme.error)
            AndroidView(
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        val future = ProcessCameraProvider.getInstance(viewContext)
                        future.addListener({
                            if (!active.get()) return@addListener
                            try {
                                val provider = future.get()
                                if (!active.get()) return@addListener
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                                analysis.setAnalyzer(cameraExecutor) { proxy ->
                                    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                                    val image = proxy.image
                                    if (image == null || detected.get()) { proxy.close(); return@setAnalyzer }
                                    try {
                                        scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                                            .addOnSuccessListener { codes ->
                                                val value = codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                                                if (value != null && active.get() && detected.compareAndSet(false, true)) mainHandler.post { if (active.get()) latestResult(value) }
                                            }
                                            .addOnCompleteListener { proxy.close() }
                                    } catch (_: Exception) { proxy.close() }
                                }
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                                boundUseCases.value = provider to (preview to analysis)
                            } catch (_: Exception) {
                                if (active.get()) mainHandler.post { if (active.get()) cameraError = "Camera unavailable. Close the scanner and paste the link or address instead." }
                            }
                        }, ContextCompat.getMainExecutor(viewContext))
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).testTag("setup-qr-preview"),
            )
        }
        Spacer(Modifier.size(4.dp))
    }
}
