package club.dwdc.keymaster.ui.screens

import android.Manifest
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import club.dwdc.keymaster.data.KeyMasterProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private const val TAG = "RestoreGpgScan"

@Composable
fun RestoreGpgScanScreen(
    onRestored: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val handleJson: (String) -> Unit = { rawJson ->
        Log.d(TAG, "handleJson called, scanned=$scanned, len=${rawJson.length}")
        if (!scanned) {
            try {
                val entries = parseGpgRestoreJson(rawJson.trim())
                val controller = KeyMasterProvider.getController(context)
                    ?: throw IllegalStateException("No seed configured")

                val grouped = entries.groupBy { it.metadata.identity }
                for ((identity, keys) in grouped) {
                    controller.restoreIdentity(identity)
                    for (key in keys) {
                        controller.restoreGpgKeys(
                            identity,
                            key.metadata.pgp_name,
                            key.metadata.pgp_email,
                            key.metadata.creation_time.toLong()
                        )
                    }
                }

                // Refresh the account list
                KeyMasterProvider.getAccounts(context)

                val count = grouped.size
                val noun = if (count == 1) "identity" else "identities"
                Toast.makeText(context, "Restored $count $noun", Toast.LENGTH_SHORT).show()

                scanned = true
                onRestored()
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    when {
        hasPermission -> {
            RestoreGpgCameraContent(
                onJsonFound = handleJson,
                onPaste = { showPasteDialog = true },
                onBack = onBack,
                scanned = scanned
            )
        }
        permissionDenied -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Camera permission is required to scan QR codes.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }) {
                    Text("Grant Permission")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { showPasteDialog = true }) {
                    Text("Paste JSON instead")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onBack) {
                    Text("Back")
                }
            }
        }
        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    if (showPasteDialog) {
        PasteGpgRestoreDialog(
            onDismiss = { showPasteDialog = false },
            onSubmit = { json ->
                Log.d(TAG, "PasteDialog onSubmit called, len=${json.length}")
                showPasteDialog = false
                handleJson(json)
            }
        )
    }
}

private data class GpgKeyMetadata(
    val identity: String,
    val pgp_name: String,
    val pgp_email: String,
    val creation_time: String
)

private data class GpgRestoreEntry(
    val path: String,
    val metadata: GpgKeyMetadata
)

private fun parseGpgRestoreJson(json: String): List<GpgRestoreEntry> {
    val type = object : TypeToken<List<GpgRestoreEntry>>() {}.type
    return Gson().fromJson(json, type)
}

@Composable
private fun PasteGpgRestoreDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste GPG Restore JSON") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("JSON") },
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(text) },
                enabled = text.trimStart().startsWith("[") && text.contains("identity")
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RestoreGpgCameraContent(
    onJsonFound: (String) -> Unit,
    onPaste: () -> Unit,
    onBack: () -> Unit,
    scanned: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val executor = Executors.newSingleThreadExecutor()

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                processRestoreImage(imageProxy, scanner) { rawValue ->
                                    if (rawValue.trimStart().startsWith("[") && rawValue.contains("identity")) {
                                        onJsonFound(rawValue)
                                    }
                                }
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera bind failed", e)
                        Toast.makeText(ctx, "Failed to start camera", Toast.LENGTH_SHORT).show()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay: instruction text at top
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                )
            ) {
                Text(
                    text = "Scan a GPG key restore QR code",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextButton(onClick = onPaste) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    )
                ) {
                    Text(
                        text = "Paste JSON instead",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onBack) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    )
                ) {
                    Text(
                        text = "Back",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processRestoreImage(
    imageProxy: androidx.camera.core.ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onBarcodeFound: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                if (barcode.valueType == Barcode.TYPE_TEXT || barcode.valueType == Barcode.TYPE_URL) {
                    barcode.rawValue?.let { onBarcodeFound(it) }
                }
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
