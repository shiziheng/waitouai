package com.huyan.tvguard.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huyan.tvguard.data.DayStats
import com.huyan.tvguard.data.StatsStore
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsSheet(
    viewModel: GuardianViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val days = remember { viewModel.recentStats(14).filter { it.hasData }.reversed() }
    var report by remember { mutableStateOf<String?>(null) }

    val totalTilt = days.sumOf { it.tiltCount }
    val tiltLeft = days.sumOf { it.tiltLeftCount }
    val tiltRight = days.sumOf { it.tiltRightCount }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "最近 14 天",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "看医生时可以带上这些。只记时长和次数，不存任何画面。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (days.isEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    "还没有记录。开始监测并让孩子看一会儿电视，这里就会有数据。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("知道了") }
                return@Column
            }

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "歪头次数",
                    value = "$totalTilt",
                    unit = "次",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "累计歪头",
                    value = StatsStore.formatDuration(days.sumOf { it.tiltMillis }),
                    unit = "",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(10.dp))

            if (totalTilt > 0) {
                TiltBalance(left = tiltLeft, right = tiltRight)
                Spacer(Modifier.height(10.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "凑近次数",
                    value = "${days.sumOf { it.closeCount }}",
                    unit = "次",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "监测时长",
                    value = StatsStore.formatDuration(days.sumOf { it.watchMillis }),
                    unit = "",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))

            Text(
                "逐日明细",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))

            days.forEach { day ->
                DayRow(day)
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { report = viewModel.buildReport() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text("生成给医生看的记录")
            }

            report?.let { text ->
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(14.dp)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { shareText(context, text) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("发送")
                    }
                    OutlinedButton(
                        onClick = { copyText(context, text) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("复制")
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "这些数字来自家庭环境下的摄像头估算，能力有限，只作为就诊时的参考，不构成任何诊断。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.height(0.dp))
                Text(
                    " $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

/** 左右倾向条：一半左一半右，谁多谁占得多 */
@Composable
private fun TiltBalance(left: Int, right: Int) {
    val total = (left + right).coerceAtLeast(1)
    val leftFraction = left.toFloat() / total

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "偏左 $left 次",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "偏右 $right 次",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            Box(
                Modifier
                    .weight(leftFraction.coerceAtLeast(0.001f))
                    .height(10.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp),
                    )
            )
            Box(
                Modifier
                    .weight((1f - leftFraction).coerceAtLeast(0.001f))
                    .height(10.dp)
                    .background(
                        MaterialTheme.colorScheme.secondary,
                        RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp),
                    )
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                leftFraction >= 0.65f -> "明显偏向左侧，看医生时值得提一句"
                leftFraction <= 0.35f -> "明显偏向右侧，看医生时值得提一句"
                else -> "左右差不多"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayRow(day: DayStats) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = day.date.substring(5),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "歪头 ${day.tiltCount} 次 · ${StatsStore.formatDuration(day.tiltMillis)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(0.dp))
        Text(
            text = "  ${day.tiltLeftCount}/${day.tiltRightCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "电视护眼管家 · 观看行为记录")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "发送记录"))
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("观看行为记录", text))
}

@Suppress("unused")
private fun ratioPercent(a: Int, b: Int): Int = (a * 100f / b.coerceAtLeast(1)).roundToInt()
