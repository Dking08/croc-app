package com.dking.crocapp.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dking.crocapp.ui.components.EmptyState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onCodeScanned: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Scan QR Code",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                CameraPreview(
                    onCodeScanned = { code ->
                        onCodeScanned(code)
                        onNavigateBack()
                    }
                )

                // Overlay instructions
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Point your camera at the QR code",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The code will be detected automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            EmptyState(
                icon = Icons.Rounded.CameraAlt,
                title = "Camera Permission Required",
                subtitle = "Please grant camera permission to scan QR codes",
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun CameraPreview(
    onCodeScanned: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanned by remember { mutableStateOf(false) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val qrReader = remember {
        MultiFormatReader().apply {
            setHints(
                EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                    put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
                    put(DecodeHintType.TRY_HARDER, true)
                }
            )
        }
    }

    DisposableEffect(analyzerExecutor) {
        onDispose {
            analyzerExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val mainExecutor = ContextCompat.getMainExecutor(ctx)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    if (scanned) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val result = imageProxy.decodeQrCode(qrReader)
                    imageProxy.close()

                    val value = result?.text?.trim().orEmpty()
                    if (value.isNotEmpty()) {
                        mainExecutor.execute {
                            if (!scanned) {
                                scanned = true
                                onCodeScanned(value)
                            }
                        }
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, mainExecutor)

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun ImageProxy.decodeQrCode(reader: MultiFormatReader): Result? {
    if (format != ImageFormat.YUV_420_888 || planes.isEmpty()) {
        return null
    }

    return try {
        val source = toQrLuminanceSource() ?: return null
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
    } catch (_: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}

private fun ImageProxy.toQrLuminanceSource(): PlanarYUVLuminanceSource? {
    val luminance = extractLuminanceBytes() ?: return null

    return when (imageInfo.rotationDegrees) {
        90 -> PlanarYUVLuminanceSource(
            rotate90(luminance, width, height),
            height,
            width,
            0,
            0,
            height,
            width,
            false
        )
        180 -> PlanarYUVLuminanceSource(
            rotate180(luminance),
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
        270 -> PlanarYUVLuminanceSource(
            rotate270(luminance, width, height),
            height,
            width,
            0,
            0,
            height,
            width,
            false
        )
        else -> PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
    }
}

private fun ImageProxy.extractLuminanceBytes(): ByteArray? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val data = ByteArray(width * height)

    if (pixelStride == 1 && rowStride == width) {
        buffer.get(data)
        return data
    }

    val rowBuffer = ByteArray(rowStride)
    var outputOffset = 0

    for (row in 0 until height) {
        val bytesToRead = minOf(rowStride, buffer.remaining())
        if (bytesToRead <= (width - 1) * pixelStride) {
            return null
        }

        buffer.get(rowBuffer, 0, bytesToRead)
        var inputOffset = 0
        repeat(width) {
            data[outputOffset++] = rowBuffer[inputOffset]
            inputOffset += pixelStride
        }
    }

    return data
}

private fun rotate90(data: ByteArray, width: Int, height: Int): ByteArray {
    val rotated = ByteArray(data.size)
    var index = 0
    for (x in 0 until width) {
        for (y in height - 1 downTo 0) {
            rotated[index++] = data[y * width + x]
        }
    }
    return rotated
}

private fun rotate180(data: ByteArray): ByteArray {
    val rotated = ByteArray(data.size)
    for (index in data.indices) {
        rotated[index] = data[data.lastIndex - index]
    }
    return rotated
}

private fun rotate270(data: ByteArray, width: Int, height: Int): ByteArray {
    val rotated = ByteArray(data.size)
    var index = 0
    for (x in width - 1 downTo 0) {
        for (y in 0 until height) {
            rotated[index++] = data[y * width + x]
        }
    }
    return rotated
}
