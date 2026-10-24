package com.huyan.tvguard.camera

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.huyan.tvguard.domain.FaceSignal
import java.util.concurrent.Executors

/**
 * CameraX 预览 + 人脸信号分析。
 *
 * 相机只在本页、且只在「监测中」时跑分析；离开页面或停止监测会立刻解绑，
 * 不会有后台偷偷采集。
 */
@Composable
fun CameraPreview(
    monitoring: Boolean,
    onSignal: (FaceSignal?) -> Unit,
    onFaceTooLarge: () -> Unit,
    onEngineError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentOnSignal by rememberUpdatedState(onSignal)
    val currentOnFaceTooLarge by rememberUpdatedState(onFaceTooLarge)
    val currentOnEngineError by rememberUpdatedState(onEngineError)

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val analyzer = remember {
        FaceSignalAnalyzer(
            appContext = context.applicationContext,
            onSignal = { signal -> currentOnSignal(signal) },
            onFaceTooLarge = { currentOnFaceTooLarge() },
            onEngineError = { message -> currentOnEngineError(message) },
        )
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var imageAnalysis by remember { mutableStateOf<ImageAnalysis?>(null) }

    AndroidView(
        modifier = modifier.background(Color(0xFF0B1512)),
        factory = { previewView },
    )

    DisposableEffect(lifecycleOwner, previewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrNull()
            if (provider == null) {
                currentOnEngineError("相机初始化失败，请重启 App 再试")
                return@addListener
            }

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // 固定 640x480 分析分辨率：校准基准和实时值必须同一尺度，换分辨率基准会失效
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            val bound = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis,
                )
                true
            }.getOrElse {
                currentOnEngineError("相机启动失败：${it.message ?: it.javaClass.simpleName}")
                false
            }

            imageAnalysis = if (bound) analysis else null
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            imageAnalysis = null
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    val analysis = imageAnalysis
    DisposableEffect(monitoring, analysis) {
        if (analysis != null) {
            if (monitoring) {
                analysis.setAnalyzer(analysisExecutor, analyzer)
            } else {
                analysis.clearAnalyzer()
            }
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }
}
