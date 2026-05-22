package com.example.qrscanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.example.qrscanner.ui.theme.QRScannerTheme
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Opens GitHub Releases in the browser so the user can download the APK manually. */
private const val GITHUB_RELEASES_LATEST_URL =
    "https://github.com/ItzHarshXD/QR-Scanner/releases/latest"

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QRScannerTheme {
                QrScannerApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerApp() {
    val context = LocalContext.current
    val scanner = rememberQrScanner()
    val scope = rememberCoroutineScope()
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberPermissionLauncher {
        permissionGranted = it
    }

    var scannedResult by remember { mutableStateOf<QrScanResult?>(null) }
    var lastSavedValue by remember { mutableStateOf<String?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    /** When false, rear camera (default). When true, front/selfie camera. */
    var useFrontCamera by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableStateOf(0f) }
    var maxZoomRatio by remember { mutableStateOf(0f) }
    val imagePickerLauncher = rememberImagePickerLauncher { selectedUri ->
        if (selectedUri != null) {
            scope.launch {
                val barcode = scanCodeFromGalleryImage(context, scanner, selectedUri)
                if (barcode?.rawValue.isNullOrBlank()) {
                    Toast.makeText(
                        context,
                        "No QR code or barcode found in that image.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    scannedResult = QrScanResult.fromBarcode(barcode)
                }
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = rememberSingleThreadExecutor()

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (permissionGranted) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text(text = "QR & Barcode Scanner") },
                        actions = {
                            IconButton(onClick = { settingsOpen = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = "Open settings"
                                )
                            }
                            IconButton(onClick = { historyOpen = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = "Open history"
                                )
                            }
                        }
                    )
                },
                bottomBar = {
                    BottomAppBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Flashlight button
                            FloatingActionButton(
                                onClick = {
                                    torchEnabled = !torchEnabled
                                    camera?.cameraControl?.enableTorch(torchEnabled)
                                },
                                modifier = Modifier.size(48.dp),
                                containerColor = if (torchEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                contentColor = if (torchEnabled) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            ) {
                                Icon(
                                    imageVector = if (torchEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                                    contentDescription = if (torchEnabled) "Turn torch off" else "Turn torch on"
                                )
                            }
                            
                            // Camera switch button
                            FloatingActionButton(
                                onClick = {
                                    useFrontCamera = !useFrontCamera
                                    if (useFrontCamera) {
                                        torchEnabled = false
                                        camera?.cameraControl?.enableTorch(false)
                                    }
                                },
                                modifier = Modifier.size(48.dp),
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Cameraswitch,
                                    contentDescription = if (useFrontCamera) {
                                        "Switch to rear camera"
                                    } else {
                                        "Switch to front camera"
                                    }
                                )
                            }
                            
                            // Gallery button
                            FloatingActionButton(
                                onClick = {
                                    imagePickerLauncher.launch("image/*")
                                },
                                modifier = Modifier.size(48.dp),
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PhotoLibrary,
                                    contentDescription = "Scan from gallery"
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    CameraPreview(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    val newZoom = (zoomRatio * zoom).coerceIn(0f, maxZoomRatio)
                                    zoomRatio = newZoom
                                    camera?.cameraControl?.setLinearZoom(newZoom)
                                }
                            },
                        lifecycleOwner = lifecycleOwner,
                        analyzerExecutor = analyzerExecutor,
                        scanner = scanner,
                        useFrontCamera = useFrontCamera,
                        onFrontCameraUnavailable = { useFrontCamera = false },
                        pauseAnalysis = scannedResult != null,
                        onCameraReady = { readyCamera -> 
                            camera = readyCamera
                            readyCamera.cameraInfo.zoomState.value?.let { zoomState ->
                                maxZoomRatio = zoomState.maxZoomRatio
                                zoomRatio = zoomState.linearZoom
                            }
                        },
                        onQrDetected = { barcode, bitmap ->
                            val raw = barcode.rawValue.orEmpty()
                            if (raw.isNotBlank() && raw != lastSavedValue) {
                                val result = QrScanResult.fromBarcode(barcode, bitmap)
                                scannedResult = result
                                saveScanToHistory(context, result)
                                lastSavedValue = raw
                            }
                        }
                    )
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        ScannerFrameOverlay(
                            modifier = Modifier.fillMaxSize(),
                            zoomRatio = zoomRatio,
                            maxZoomRatio = maxZoomRatio
                        )
                        
                        // Zoom controls inside camera frame
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 80.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val newZoom = (zoomRatio - 0.1f).coerceAtLeast(0f)
                                    zoomRatio = newZoom
                                    camera?.cameraControl?.setLinearZoom(newZoom)
                                },
                                enabled = zoomRatio > 0f
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ZoomOut,
                                    contentDescription = "Zoom out",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            if (zoomRatio > 0f) {
                                Text(
                                    text = "${(zoomRatio * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                )
                            }
                            
                            IconButton(
                                onClick = {
                                    val newZoom = (zoomRatio + 0.1f).coerceAtMost(maxZoomRatio)
                                    zoomRatio = newZoom
                                    camera?.cameraControl?.setLinearZoom(newZoom)
                                },
                                enabled = zoomRatio < maxZoomRatio
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ZoomIn,
                                    contentDescription = "Zoom in",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        
                        Card(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Text(
                                text = "Align QR code or barcode within the frame",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            PermissionPrompt(
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }
            )
        }

        if (settingsOpen) {
            ModalBottomSheet(
                onDismissRequest = { settingsOpen = false },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                SettingsSheet(
                    onOpenReleasesPage = {
                        openGitHubReleasesInBrowser(context)
                        settingsOpen = false
                    }
                )
            }
        }
        
        if (historyOpen) {
            ModalBottomSheet(
                onDismissRequest = { historyOpen = false },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                HistorySheet(
                    scanHistory = getScanHistory(context),
                    onClearHistory = {
                        clearScanHistory(context)
                        historyOpen = false
                    },
                    onClose = { 
                        historyOpen = false
                        scannedResult = null
                        lastSavedValue = null
                    }
                )
            }
        }

        scannedResult?.let { result ->
            ModalBottomSheet(
                onDismissRequest = { 
                    scannedResult = null
                    lastSavedValue = null
                },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                ResultSheet(
                    result = result,
                    onOpen = {
                        openResultAction(context, result)
                    },
                    onRescan = { 
                        scannedResult = null
                        lastSavedValue = null
                    }
                )
            }
        }
    }
}

private fun openGitHubReleasesInBrowser(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_LATEST_URL)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PermissionPrompt(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Camera permission is required to scan QR codes.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onRequestPermission) {
                    Text("Grant permission")
                }
                Button(onClick = onOpenSettings) {
                    Text("Open settings")
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    analyzerExecutor: ExecutorService,
    scanner: BarcodeScanner,
    useFrontCamera: Boolean,
    onFrontCameraUnavailable: () -> Unit,
    pauseAnalysis: Boolean,
    onCameraReady: (Camera) -> Unit,
    onQrDetected: (Barcode, Bitmap?) -> Unit
) {
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { previewView ->
            bindCamera(
                context = context,
                cameraProviderFuture = cameraProviderFuture,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                analyzerExecutor = analyzerExecutor,
                scanner = scanner,
                useFrontCamera = useFrontCamera,
                onFrontCameraUnavailable = onFrontCameraUnavailable,
                pauseAnalysis = pauseAnalysis,
                onCameraReady = onCameraReady,
                onQrDetected = onQrDetected
            )
        }
    )
}

private fun bindCamera(
    context: Context,
    cameraProviderFuture: ListenableFuture<ProcessCameraProvider>,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    analyzerExecutor: ExecutorService,
    scanner: BarcodeScanner,
    useFrontCamera: Boolean,
    onFrontCameraUnavailable: () -> Unit,
    pauseAnalysis: Boolean,
    onCameraReady: (Camera) -> Unit,
    onQrDetected: (Barcode, Bitmap?) -> Unit
) {
    cameraProviderFuture.addListener(
        {
            val cameraProvider = cameraProviderFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        analyzerExecutor,
                        QrAnalyzer(
                            scanner = scanner,
                            pauseAnalysis = { pauseAnalysis },
                            onDetected = onQrDetected
                        )
                    )
                }

            val cameraSelector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            cameraProvider.unbindAll()
            try {
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    analyzer
                )
                onCameraReady(camera)
            } catch (_: Exception) {
                if (useFrontCamera) {
                    onFrontCameraUnavailable()
                    val fallback = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analyzer
                    )
                    onCameraReady(fallback)
                }
            }
        },
        ContextCompat.getMainExecutor(context)
    )
}

private class QrAnalyzer(
    private val scanner: BarcodeScanner,
    private val pauseAnalysis: () -> Boolean,
    private val onDetected: (Barcode, Bitmap?) -> Unit
) : ImageAnalysis.Analyzer {
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (pauseAnalysis()) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.let { barcode ->
                    val bitmap = imageProxy.toBitmap()
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val rotatedBitmap = if (rotationDegrees != 0) {
                        val matrix = Matrix()
                        matrix.postRotate(rotationDegrees.toFloat())
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else {
                        bitmap
                    }
                    onDetected(barcode, rotatedBitmap)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}

@Composable
private fun ScannerFrameOverlay(
    modifier: Modifier = Modifier,
    zoomRatio: Float = 1.0f,
    maxZoomRatio: Float = 1.0f
) {
    var scanLinePosition by remember { mutableStateOf(0f) }
    
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameTime ->
                scanLinePosition = (frameTime % 2000) / 2000f
            }
        }
    }
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(280.dp)
                .background(Color.Transparent)
        ) {
            // Draw corner brackets
            val cornerLength = 40.dp.toPx()
            val cornerWidth = 4.dp.toPx()
            val cornerColor = Color.White
            
            // Top-left corner
            drawLine(
                color = cornerColor,
                start = Offset(0f, cornerLength),
                end = Offset(0f, 0f),
                strokeWidth = cornerWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = cornerColor,
                start = Offset(0f, 0f),
                end = Offset(cornerLength, 0f),
                strokeWidth = cornerWidth,
                cap = StrokeCap.Round
            )
            
            // Top-right corner
            drawLine(
                color = cornerColor,
                start = Offset(size.width - cornerLength, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = cornerWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = cornerColor,
                start = Offset(size.width, 0f),
                end = Offset(size.width, cornerLength),
                strokeWidth = cornerWidth,
                cap = StrokeCap.Round
            )
            
            // Bottom-left corner
            drawLine(
                color = cornerColor,
                start = Offset(0f, size.height - cornerLength),
                end = Offset(0f, size.height),
                strokeWidth = cornerWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = cornerColor,
                start = Offset(0f, size.height),
                end = Offset(cornerLength, size.height),
                strokeWidth = cornerWidth,
                cap = StrokeCap.Round
            )
            
            // Bottom-right corner
            drawLine(
                color = cornerColor,
                start = Offset(size.width - cornerLength, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = cornerWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = cornerColor,
                start = Offset(size.width, size.height - cornerLength),
                end = Offset(size.width, size.height),
                strokeWidth = cornerWidth,
                cap = StrokeCap.Round
            )
            
            // Draw scanning line animation effect
            val scanLineY = scanLinePosition * size.height
            drawLine(
                color = Color.White.copy(alpha = 0.6f),
                start = Offset(cornerLength, scanLineY),
                end = Offset(size.width - cornerLength, scanLineY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun ResultSheet(
    result: QrScanResult,
    onOpen: () -> Unit,
    onRescan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Success indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(
                text = "Scan Successful",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        // Result type badge
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                text = result.title,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Medium
            )
        }
        
        // Scanned code image
        result.barcodeImage?.let { bitmap ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scanned Code",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Scanned QR/Barcode",
                        modifier = Modifier
                            .size(120.dp)
                            .background(
                                color = Color.White,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(4.dp)
                    )
                }
            }
        }
        
        // Result content
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = result.value,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Action buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth()
            ) { 
                Text(result.actionLabel) 
            }
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier.fillMaxWidth()
            ) { 
                Text("Rescan") 
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsSheet(
    onOpenReleasesPage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings & Info",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "QR & Barcode Scanner v1.3.1\n\nA modern scanner app supporting QR codes and various barcode formats with zoom functionality.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Updates",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Get the latest version from GitHub releases. Download the APK and install it from your Downloads folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        Button(
            onClick = onOpenReleasesPage,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get Latest Version")
        }
    }
}

@Composable
private fun HistorySheet(
    scanHistory: List<ScanHistoryEntry>,
    onClearHistory: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Scan History",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(
                onClick = onClearHistory
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Clear history"
                )
            }
        }
        
        if (scanHistory.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "No scan history yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Your scanned QR codes and barcodes will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scanHistory) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = formatTimestamp(entry.timestamp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        // Copy to clipboard
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = "Copy"
                                    )
                                }
                            }
                            Text(
                                text = entry.value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f)
            ) {
                Text("Close")
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000} minutes ago"
        diff < 86400000 -> "${diff / 3600000} hours ago"
        diff < 604800000 -> "${diff / 86400000} days ago"
        else -> "${diff / 604800000} weeks ago"
    }
}

private fun openResultAction(context: Context, result: QrScanResult) {
    when (result.type) {
        ScanType.URL -> {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.value))
            context.startActivity(intent)
        }

        ScanType.WIFI -> {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            context.startActivity(intent)
        }

        ScanType.TEXT -> Unit
        
        ScanType.EMAIL -> {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${result.value}"))
            context.startActivity(intent)
        }
        
        ScanType.PHONE -> {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${result.value}"))
            context.startActivity(intent)
        }
        
        ScanType.SMS -> {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${result.value}"))
            context.startActivity(intent)
        }
        
        ScanType.CALENDAR -> {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "vnd.android.cursor.item/event"
            }
            context.startActivity(intent)
        }
        
        ScanType.LOCATION -> {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${result.value}"))
            context.startActivity(intent)
        }
    }
}

private suspend fun scanCodeFromGalleryImage(
    context: Context,
    scanner: BarcodeScanner,
    imageUri: Uri
): Barcode? {
    return try {
        val bytes = context.contentResolver.openInputStream(imageUri)?.use { input ->
            input.readBytes()
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val image = InputImage.fromBitmap(bitmap, 0)
        val barcodes = processImageForCodes(scanner, image)
        barcodes.firstOrNull { it.rawValue?.isNotBlank() == true }
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }
}

private suspend fun processImageForCodes(
    scanner: BarcodeScanner,
    image: InputImage
): List<Barcode> = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            if (continuation.isActive) continuation.resume(barcodes) {}
        }
        .addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
}

@Composable
private fun rememberQrScanner(): BarcodeScanner {
    val scanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()
        BarcodeScanning.getClient(options)
    }
    DisposableEffect(scanner) {
        onDispose { scanner.close() }
    }
    return scanner
}

@Composable
private fun rememberSingleThreadExecutor(): ExecutorService {
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(executor) {
        onDispose { executor.shutdown() }
    }
    return executor
}

@Composable
private fun rememberPermissionLauncher(
    onResult: (Boolean) -> Unit
) = androidx.activity.compose.rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
    onResult = onResult
)

@Composable
private fun rememberImagePickerLauncher(
    onResult: (Uri?) -> Unit
) = androidx.activity.compose.rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
    onResult = onResult
)

private data class QrScanResult(
    val title: String,
    val value: String,
    val type: ScanType,
    val actionLabel: String,
    val barcodeImage: Bitmap? = null
) {
    companion object {
        fun fromBarcode(barcode: Barcode, bitmap: Bitmap? = null): QrScanResult {
            val value = barcode.rawValue.orEmpty()
            return when (barcode.valueType) {
                Barcode.TYPE_URL -> QrScanResult(
                    title = "Website",
                    value = value,
                    type = ScanType.URL,
                    actionLabel = "Open link",
                    barcodeImage = bitmap
                )

                Barcode.TYPE_WIFI -> QrScanResult(
                    title = "Wi-Fi",
                    value = value,
                    type = ScanType.WIFI,
                    actionLabel = "Open Wi-Fi",
                    barcodeImage = bitmap
                )

                Barcode.TYPE_EMAIL -> QrScanResult(
                    title = "Email",
                    value = value,
                    type = ScanType.EMAIL,
                    actionLabel = "Open Email",
                    barcodeImage = bitmap
                )

                Barcode.TYPE_PHONE -> QrScanResult(
                    title = "Phone",
                    value = value,
                    type = ScanType.PHONE,
                    actionLabel = "Call",
                    barcodeImage = bitmap
                )

                Barcode.TYPE_SMS -> QrScanResult(
                    title = "SMS",
                    value = value,
                    type = ScanType.SMS,
                    actionLabel = "Open Messages",
                    barcodeImage = bitmap
                )

                Barcode.TYPE_CALENDAR_EVENT -> QrScanResult(
                    title = "Calendar Event",
                    value = value,
                    type = ScanType.CALENDAR,
                    actionLabel = "Open Calendar",
                    barcodeImage = bitmap
                )

                Barcode.TYPE_GEO -> QrScanResult(
                    title = "Location",
                    value = value,
                    type = ScanType.LOCATION,
                    actionLabel = "Open Maps",
                    barcodeImage = bitmap
                )

                else -> {
                    val format = when (barcode.format) {
                        Barcode.FORMAT_AZTEC -> "Aztec"
                        Barcode.FORMAT_CODE_128 -> "Code 128"
                        Barcode.FORMAT_CODE_39 -> "Code 39"
                        Barcode.FORMAT_EAN_13 -> "EAN-13"
                        Barcode.FORMAT_EAN_8 -> "EAN-8"
                        Barcode.FORMAT_ITF -> "ITF"
                        Barcode.FORMAT_UPC_A -> "UPC-A"
                        Barcode.FORMAT_UPC_E -> "UPC-E"
                        Barcode.FORMAT_PDF417 -> "PDF417"
                        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
                        else -> "QR Code"
                    }
                    QrScanResult(
                        title = "$format content",
                        value = value,
                        type = ScanType.TEXT,
                        actionLabel = "Close",
                        barcodeImage = bitmap
                    )
                }
            }
        }
    }
}

private enum class ScanType {
    URL, WIFI, TEXT, EMAIL, PHONE, SMS, CALENDAR, LOCATION
}

// Data class for scan history entry
private data class ScanHistoryEntry(
    val value: String,
    val type: ScanType,
    val timestamp: Long,
    val title: String
)

// SharedPreferences helper functions
private fun getScanHistory(context: Context): List<ScanHistoryEntry> {
    val prefs = context.getSharedPreferences("scan_history", Context.MODE_PRIVATE)
    val historySet = prefs.getStringSet("history", emptySet())
    
    return historySet?.mapNotNull { entry ->
        val parts = entry.split("|")
        if (parts.size >= 4) {
            val timestamp = parts[0].toLongOrNull() ?: 0L
            val title = parts[1]
            val value = parts[2]
            val type = try {
                ScanType.valueOf(parts[3])
            } catch (e: Exception) {
                ScanType.TEXT
            }
            ScanHistoryEntry(value, type, timestamp, title)
        } else {
            null
        }
    }?.sortedByDescending { it.timestamp } ?: emptyList()
}

private fun saveScanToHistory(context: Context, result: QrScanResult) {
    val prefs = context.getSharedPreferences("scan_history", Context.MODE_PRIVATE)
    val historySet = prefs.getStringSet("history", emptySet())?.toMutableSet() ?: mutableSetOf()
    
    val timestamp = System.currentTimeMillis()
    val entry = "${timestamp}|${result.title}|${result.value}|${result.type}"
    
    historySet.add(entry)
    
    // Keep only last 50 scans
    if (historySet.size > 50) {
        val sortedList = historySet.mapNotNull { rawEntry ->
            val parts = rawEntry.split("|")
            if (parts.isNotEmpty()) {
                val ts = parts[0].toLongOrNull() ?: 0L
                ts to rawEntry
            } else {
                null
            }
        }.sortedByDescending { it.first }.take(50)
        
        historySet.clear()
        sortedList.forEach { historySet.add(it.second) }
    }
    
    prefs.edit().putStringSet("history", historySet).apply()
}

private fun clearScanHistory(context: Context) {
    val prefs = context.getSharedPreferences("scan_history", Context.MODE_PRIVATE)
    prefs.edit().remove("history").apply()
}