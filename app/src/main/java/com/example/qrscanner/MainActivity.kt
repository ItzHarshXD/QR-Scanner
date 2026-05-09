package com.example.qrscanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.qrscanner.update.GitHubUpdateService
import com.example.qrscanner.update.ReleaseInfo
import com.example.qrscanner.update.UpdateState
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.example.qrscanner.ui.theme.QRScannerTheme
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    val updateService = remember { GitHubUpdateService(context.applicationContext) }
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
    var torchEnabled by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var lastAvailableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = rememberSingleThreadExecutor()

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        runUpdateCheck(
            service = updateService,
            isManual = false,
            onStateChange = { state ->
                updateState = state
                if (state is UpdateState.UpdateAvailable) {
                    lastAvailableRelease = state.releaseInfo
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (permissionGranted) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text(text = "QR Scanner") },
                        actions = {
                            IconButton(onClick = { settingsOpen = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = "Open settings"
                                )
                            }
                        }
                    )
                },
                bottomBar = {
                    BottomAppBar(
                        actions = {
                            FilledIconButton(
                                onClick = {
                                    torchEnabled = !torchEnabled
                                    camera?.cameraControl?.enableTorch(torchEnabled)
                                }
                            ) {
                                Icon(
                                    imageVector = if (torchEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                                    contentDescription = if (torchEnabled) "Turn torch off" else "Turn torch on"
                                )
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        lifecycleOwner = lifecycleOwner,
                        analyzerExecutor = analyzerExecutor,
                        scanner = scanner,
                        pauseAnalysis = scannedResult != null,
                        onCameraReady = { readyCamera -> camera = readyCamera },
                        onQrDetected = { barcode ->
                            val raw = barcode.rawValue.orEmpty()
                            if (raw.isNotBlank()) {
                                scannedResult = QrScanResult.fromBarcode(barcode)
                            }
                        }
                    )
                    ScannerFrameOverlay()
                    Text(
                        text = "Align QR code within the frame",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
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
                    updateState = updateState,
                    onCheckUpdate = {
                        scope.launch {
                            runUpdateCheck(
                                service = updateService,
                                isManual = true,
                                onStateChange = { state ->
                                    updateState = state
                                    if (state is UpdateState.UpdateAvailable) {
                                        lastAvailableRelease = state.releaseInfo
                                    }
                                }
                            )
                        }
                    },
                    onRetryDownload = {
                        val release = lastAvailableRelease ?: return@SettingsSheet
                        scope.launch {
                            runDownloadAndInstall(
                                service = updateService,
                                releaseInfo = release,
                                onStateChange = { updateState = it }
                            )
                        }
                    },
                    onInstallReady = { apkFile ->
                        updateState = updateService.launchInstaller(apkFile).fold(
                            onSuccess = { UpdateState.Idle },
                            onFailure = {
                                UpdateState.InstallFailed(
                                    it.message ?: "Install launch failed."
                                )
                            }
                        )
                    }
                )
            }
        }

        if (updateState is UpdateState.UpdateAvailable) {
            val releaseInfo = (updateState as UpdateState.UpdateAvailable).releaseInfo
            ModalBottomSheet(
                onDismissRequest = { },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                UpdateAvailableSheet(
                    releaseInfo = releaseInfo,
                    onUpdateNow = {
                        scope.launch {
                            runDownloadAndInstall(
                                service = updateService,
                                releaseInfo = releaseInfo,
                                onStateChange = { updateState = it }
                            )
                        }
                    },
                    onLater = {
                        updateService.markLaterForCurrentAppVersion()
                        updateState = UpdateState.Idle
                    }
                )
            }
        }

        scannedResult?.let { result ->
            ModalBottomSheet(
                onDismissRequest = { scannedResult = null },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                ResultSheet(
                    result = result,
                    onOpen = {
                        openResultAction(context, result)
                    },
                    onRescan = { scannedResult = null }
                )
            }
        }
    }
}

private suspend fun runUpdateCheck(
    service: GitHubUpdateService,
    isManual: Boolean,
    onStateChange: (UpdateState) -> Unit
) {
    onStateChange(UpdateState.Checking)
    val installedVersionName = service.getInstalledVersionName()
    service.checkLatestRelease().fold(
        onSuccess = { releaseInfo ->
            val isNewer = service.isUpdateAvailable(installedVersionName, releaseInfo.tag)
            when {
                !isNewer -> onStateChange(UpdateState.NoUpdate)
                !isManual && !service.shouldAutoPrompt() -> onStateChange(UpdateState.Idle)
                else -> onStateChange(UpdateState.UpdateAvailable(releaseInfo))
            }
        },
        onFailure = { error ->
            onStateChange(UpdateState.DownloadFailed(error.message ?: "Update check failed."))
        }
    )
}

private suspend fun runDownloadAndInstall(
    service: GitHubUpdateService,
    releaseInfo: ReleaseInfo,
    onStateChange: (UpdateState) -> Unit
) {
    onStateChange(UpdateState.Downloading(progress = 0))
    service.downloadApk(releaseInfo) { progress ->
        onStateChange(UpdateState.Downloading(progress))
    }.fold(
        onSuccess = { apkFile ->
            onStateChange(UpdateState.InstallReady(apkFile, releaseInfo))
            val installResult = service.launchInstaller(apkFile)
            onStateChange(
                installResult.fold(
                    onSuccess = { UpdateState.Idle },
                    onFailure = { UpdateState.InstallFailed(it.message ?: "Install failed.") }
                )
            )
        },
        onFailure = { error ->
            onStateChange(UpdateState.DownloadFailed(error.message ?: "Download failed."))
        }
    )
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
    pauseAnalysis: Boolean,
    onCameraReady: (Camera) -> Unit,
    onQrDetected: (Barcode) -> Unit
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
    pauseAnalysis: Boolean,
    onCameraReady: (Camera) -> Unit,
    onQrDetected: (Barcode) -> Unit
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

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                analyzer
            )
            onCameraReady(camera)
        },
        ContextCompat.getMainExecutor(context)
    )
}

private class QrAnalyzer(
    private val scanner: BarcodeScanner,
    private val pauseAnalysis: () -> Boolean,
    private val onDetected: (Barcode) -> Unit
) : ImageAnalysis.Analyzer {
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
                barcodes.firstOrNull()?.let(onDetected)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}

@Composable
private fun ScannerFrameOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(250.dp)
                .background(Color.Transparent)
        ) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.65f),
                size = size,
                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                style = Stroke(width = 3.dp.toPx())
            )
            val centerY = size.height / 2f
            drawLine(
                color = Color.White.copy(alpha = 0.5f),
                start = Offset(24.dp.toPx(), centerY),
                end = Offset(size.width - 24.dp.toPx(), centerY),
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = result.title,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = result.value,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpen) { Text(result.actionLabel) }
            Button(onClick = onRescan) { Text("Rescan") }
        }
        Box(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun UpdateAvailableSheet(
    releaseInfo: ReleaseInfo,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Update available (${releaseInfo.tag})",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = releaseInfo.shortNotes,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onUpdateNow) { Text("Update now") }
            Button(onClick = onLater) { Text("Later") }
        }
    }
}

@Composable
private fun SettingsSheet(
    updateState: UpdateState,
    onCheckUpdate: () -> Unit,
    onRetryDownload: () -> Unit,
    onInstallReady: (java.io.File) -> Unit
) {
    val statusText = when (updateState) {
        UpdateState.Idle -> "Idle"
        UpdateState.Checking -> "Checking for updates..."
        UpdateState.NoUpdate -> "No update available."
        is UpdateState.UpdateAvailable -> "Update found: ${updateState.releaseInfo.tag}"
        is UpdateState.Downloading -> "Downloading update: ${updateState.progress}%"
        is UpdateState.DownloadFailed -> "Download failed: ${updateState.message}"
        is UpdateState.InstallReady -> "Download complete. Ready to install."
        is UpdateState.InstallFailed -> "Install failed: ${updateState.message}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onCheckUpdate) {
            Text("Check for updates")
        }

        if (updateState is UpdateState.DownloadFailed) {
            Button(onClick = onRetryDownload) {
                Text("Retry download")
            }
        }

        if (updateState is UpdateState.InstallReady) {
            Button(onClick = { onInstallReady(updateState.apkFile) }) {
                Text("Install downloaded APK")
            }
        }
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
    }
}

@Composable
private fun rememberQrScanner(): BarcodeScanner {
    val scanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
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

private data class QrScanResult(
    val title: String,
    val value: String,
    val type: ScanType,
    val actionLabel: String
) {
    companion object {
        fun fromBarcode(barcode: Barcode): QrScanResult {
            val value = barcode.rawValue.orEmpty()
            return when (barcode.valueType) {
                Barcode.TYPE_URL -> QrScanResult(
                    title = "Website",
                    value = value,
                    type = ScanType.URL,
                    actionLabel = "Open link"
                )

                Barcode.TYPE_WIFI -> QrScanResult(
                    title = "Wi-Fi",
                    value = value,
                    type = ScanType.WIFI,
                    actionLabel = "Open Wi-Fi"
                )

                else -> QrScanResult(
                    title = "QR content",
                    value = value,
                    type = ScanType.TEXT,
                    actionLabel = "Close"
                )
            }
        }
    }
}

private enum class ScanType {
    URL, WIFI, TEXT
}