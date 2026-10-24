package com.huyan.tvguard.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huyan.tvguard.camera.CameraPreview
import com.huyan.tvguard.domain.FaceSignal
import com.huyan.tvguard.domain.IssueKind
import com.huyan.tvguard.ui.components.LevelBar
import com.huyan.tvguard.ui.components.TiltGauge
import com.huyan.tvguard.ui.theme.StatusPalette
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun GuardianRoute(viewModel: GuardianViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state.toast) {
        val toast = state.toast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(toast)
        viewModel.consumeToast()
    }

    val view = LocalView.current
    LaunchedEffect(state.monitoring) {
        view.keepScreenOn = state.monitoring
    }

    // 切后台就停，绝不后台偷偷用相机
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.stopMonitoring()
                Lifecycle.Event.ON_RESUME -> hasPermission = hasCameraPermission(context)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Header(
                onOpenSettings = { showSettings = true },
                onOpenStats = {
                    viewModel.refreshStats()
                    showStats = true
                },
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            PreviewCard(
                state = state,
                hasPermission = hasPermission,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onSignal = viewModel::onSignal,
                onFaceTooLarge = viewModel::onFaceTooLarge,
                onEngineError = viewModel::onEngineError,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
            )

            Spacer(Modifier.height(14.dp))
            PrimaryStatus(state)
            Spacer(Modifier.height(12.dp))
            MetricsCard(state)

            if (state.showDiagnostics) {
                Spacer(Modifier.height(12.dp))
                DiagnosticsCard(state)
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = viewModel::toggleDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (state.showDiagnostics) "收起诊断信息" else "显示诊断信息",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))

            Button(
                onClick = {
                    when {
                        state.monitoring -> viewModel.stopMonitoring()
                        hasPermission -> viewModel.startMonitoring()
                        else -> permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = if (state.monitoring) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    text = if (state.monitoring) "停止监测" else "开始监测",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = viewModel::calibrate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.calibrated) "重新校准（摆正坐姿后点）" else "校准：让孩子坐正",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(Modifier.height(14.dp))
            Disclaimer(Modifier.padding(bottom = 20.dp))
        }
    }

    if (showSettings) {
        SettingsSheet(
            state = state,
            onDismiss = { showSettings = false },
            onNearEnabled = viewModel::setNearEnabled,
            onNearRatio = viewModel::setNearRatio,
            onTiltEnabled = viewModel::setTiltEnabled,
            onTiltThreshold = viewModel::setTiltThreshold,
            onTiltSustain = viewModel::setTiltSustain,
            onLookEnabled = viewModel::setLookEnabled,
            onLookThreshold = viewModel::setLookThreshold,
            onVoiceEnabled = viewModel::setVoiceEnabled,
            onBoostVolume = viewModel::setBoostVolume,
            onAlertSound = viewModel::setAlertSound,
            onTestVoice = viewModel::testVoice,
            onClearCalibration = viewModel::clearCalibration,
        )
    }

    if (showStats) {
        StatsSheet(viewModel = viewModel, onDismiss = { showStats = false })
    }
}

@Composable
private fun Header(
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "电视护眼管家",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "盯着孩子歪头和凑近，歪了就喊一句",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenStats) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                "使用统计",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreviewCard(
    state: GuardianUiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSignal: (FaceSignal?) -> Unit,
    onFaceTooLarge: () -> Unit,
    onEngineError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1512)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (hasPermission) {
                CameraPreview(
                    monitoring = state.monitoring,
                    onSignal = onSignal,
                    onFaceTooLarge = onFaceTooLarge,
                    onEngineError = onEngineError,
                )
            } else {
                PermissionPlaceholder(onRequestPermission)
            }

            if (!state.monitoring && hasPermission) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0xBB000000))))
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(38.dp))
                        Text("还没开始监测", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            state.engineError?.let { error ->
                ErrorBar(
                    message = error,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionPlaceholder(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Face, null, tint = Color(0xFF7FD8BF), modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(10.dp))
        Text("需要相机才能看见孩子", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "画面只在手机上实时处理，不录像、不上传、不保存。",
            color = Color(0xB3FFFFFF),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRequest) { Text("允许使用相机") }
    }
}

@Composable
private fun ErrorBar(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xE6B3261E),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PrimaryStatus(state: GuardianUiState) {
    val dark = isSystemInDarkTheme()

    val title: String
    val subtitle: String
    val accent: Color

    when {
        state.engineError != null -> {
            title = "识别没法用"
            subtitle = "看下面的错误提示，或者先试着校准"
            accent = StatusPalette.danger(dark)
        }

        !state.monitoring -> {
            title = if (state.calibrated) "已暂停" else "先校准一次"
            subtitle = if (state.calibrated) {
                "点「开始监测」继续盯着"
            } else {
                "让孩子坐正 → 点「开始监测」→ 点「校准」"
            }
            accent = StatusPalette.neutral(dark)
        }

        !state.calibrated -> {
            title = "等你校准"
            subtitle = "孩子已经在画面里，点下面的校准按钮"
            accent = StatusPalette.warn(dark)
        }

        state.status == LiveStatus.NO_FACE -> {
            title = "看不到孩子"
            subtitle = "可能走开了，或手机角度偏了。这时不做任何判断"
            accent = StatusPalette.neutral(dark)
        }

        state.status == LiveStatus.SAFE -> {
            title = "坐得挺好"
            subtitle = "距离和头姿都正常"
            accent = StatusPalette.safe(dark)
        }

        state.status == LiveStatus.WARNING -> {
            title = "注意了"
            subtitle = warningText(state)
            accent = StatusPalette.warn(dark)
        }

        state.status == LiveStatus.ALERTING -> {
            title = alertText(state)
            subtitle = "已经提醒 ${state.alertCount} 次，调整好会自动解除"
            accent = StatusPalette.danger(dark)
        }

        else -> {
            title = "准备中"
            subtitle = "等画面稳定一下"
            accent = StatusPalette.neutral(dark)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state.status == LiveStatus.SAFE) Icons.Filled.Info else Icons.Filled.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = accent)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun warningText(state: GuardianUiState): String {
    val parts = state.activeIssues.keys.map {
        when (it) {
            IssueKind.HEAD_TILT -> "歪头"
            IssueKind.TOO_CLOSE -> "凑太近"
            IssueKind.LOOK_ASIDE -> "斜着看"
        }
    }
    val seconds = (state.nextAlertInMillis / 100f).roundToInt() / 10f
    return "${parts.joinToString("、")}，还差 $seconds 秒就提醒"
}

private fun alertText(state: GuardianUiState): String = when {
    IssueKind.HEAD_TILT in state.activeIssues -> "歪头了"
    IssueKind.TOO_CLOSE in state.activeIssues -> "太近了"
    IssueKind.LOOK_ASIDE in state.activeIssues -> "斜着看了"
    else -> "需要调整"
}

@Composable
private fun MetricsCard(state: GuardianUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val tiltRule = state.rules[IssueKind.HEAD_TILT]
            val tiltThreshold = tiltRule?.threshold ?: 15f
            val tiltEnabled = tiltRule?.enabled ?: true

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "头部倾斜",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                if (!tiltEnabled) {
                    Text(
                        "已关闭",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "阈值 ${tiltThreshold.roundToInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.monitoring && state.status != LiveStatus.NO_FACE) {
                TiltGauge(
                    tiltDeg = state.liveTiltDeg,
                    thresholdDeg = tiltThreshold,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "开始监测后会显示实时角度",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 22.dp),
                )
            }

            val nearRule = state.rules[IssueKind.TOO_CLOSE]
            val nearThreshold = nearRule?.threshold ?: 1.2f

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "离电视远近",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (state.liveNearRatio > 0f) {
                        "基准的 ${formatRatio(state.liveNearRatio)} 倍"
                    } else {
                        "—"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            LevelBar(
                fraction = if (state.liveNearRatio > 0f) (state.liveNearRatio - 0.6f) / 1.2f else 0f,
                thresholdFraction = (nearThreshold - 0.6f) / 1.2f,
                over = state.liveNearRatio >= nearThreshold,
            )

            Spacer(Modifier.height(14.dp))

            val lookRule = state.rules[IssueKind.LOOK_ASIDE]
            val lookThreshold = lookRule?.threshold ?: 30f

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "眼神偏离",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    // 测不到就直说，不要显示 0° 让人以为「正对着」
                    text = state.liveLookDeg?.let { "${abs(it).roundToInt()}°" } ?: "测不到",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            LevelBar(
                fraction = state.liveLookDeg?.let { abs(it) / 60f } ?: 0f,
                thresholdFraction = lookThreshold / 60f,
                over = (state.liveLookDeg?.let { abs(it) } ?: 0f) >= lookThreshold,
            )
        }
    }
}

/**
 * 诊断卡片：把「原始角度 / 扣除基线后的角度 / 可信度」直接摊开。
 *
 * 误报的时候光看一个仪表盘是查不出问题的，得能看到这些原始数字。
 */
@Composable
private fun DiagnosticsCard(state: GuardianUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "诊断信息",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))

            DiagnosticRow("原始倾斜角", "${state.rawTiltDeg.roundToInt()}°")
            DiagnosticRow("手机基线", "${state.baselineTiltDeg.roundToInt()}°")
            DiagnosticRow("扣除后角度", "${state.liveTiltDeg.roundToInt()}°")
            DiagnosticRow(
                "两眼跨度",
                "%.1f%%".format(state.eyeSpan * 100),
            )
            DiagnosticRow(
                "角度可信",
                if (state.tiltReliable) "是" else "否（脸太远或太偏）",
            )
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Disclaimer(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.Info,
            null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "这个 App 只记录「歪头」这个动作，不能判断是否斜视。" +
                "如果孩子歪头看电视已经持续一段时间，建议去眼科做一次检查，越早越容易处理。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun formatRatio(value: Float): String {
    val rounded = (value * 100).roundToInt()
    return "${rounded / 100}.${(rounded % 100).toString().padStart(2, '0')}"
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
