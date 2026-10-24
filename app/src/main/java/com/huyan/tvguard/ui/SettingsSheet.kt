package com.huyan.tvguard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huyan.tvguard.domain.IssueKind
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: GuardianUiState,
    onDismiss: () -> Unit,
    onNearEnabled: (Boolean) -> Unit,
    onNearRatio: (Float) -> Unit,
    onTiltEnabled: (Boolean) -> Unit,
    onTiltThreshold: (Float) -> Unit,
    onTiltSustain: (Long) -> Unit,
    onLookEnabled: (Boolean) -> Unit,
    onLookThreshold: (Float) -> Unit,
    onVoiceEnabled: (Boolean) -> Unit,
    onBoostVolume: (Boolean) -> Unit,
    onAlertSound: (Boolean) -> Unit,
    onTestVoice: () -> Unit,
    onClearCalibration: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "数值是给家里试用的起点，不是医学标准。按你家客厅的情况调就行。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(22.dp))

            SectionTitle("歪头", "这一期最该管的事")
            SwitchRow(
                title = "歪头提醒",
                hint = "头部倾斜超过阈值并持续一段时间就提醒。",
                checked = state.rules[IssueKind.HEAD_TILT]?.enabled ?: true,
                onCheckedChange = onTiltEnabled,
            )

            val tiltThreshold = state.rules[IssueKind.HEAD_TILT]?.threshold ?: 15f
            var tiltDeg by remember(tiltThreshold) { mutableFloatStateOf(tiltThreshold) }
            SliderRow(
                title = "歪多少度算歪",
                valueText = "${tiltDeg.roundToInt()}°",
                hint = "两眼连线相对水平线偏转多少度。默认 15° 已经挺明显了；调到 10° 更敏感，但孩子随口应一声也会报。",
                value = tiltDeg,
                valueRange = 8f..35f,
                steps = 8,
                onChange = { tiltDeg = it },
                onCommit = { onTiltThreshold(tiltDeg) },
            )

            val sustain = state.rules[IssueKind.HEAD_TILT]?.sustainMillis ?: 2_000L
            var sustainSec by remember(sustain) { mutableFloatStateOf(sustain / 1000f) }
            SliderRow(
                title = "歪多久才提醒",
                valueText = "${sustainSec.roundToInt()} 秒",
                hint = "短于这个时间不算，避免孩子低头捡个东西就被喊。",
                value = sustainSec,
                valueRange = 1f..8f,
                steps = 6,
                onChange = { sustainSec = it },
                onCommit = { onTiltSustain((sustainSec * 1000).roundToLong()) },
            )

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            SectionTitle("斜眼看", "辅助观察")
            SwitchRow(
                title = "眼神偏离提醒",
                hint = "瞳孔明显偏到一侧时提醒。普通摄像头估算，精度有限，只当辅助。",
                checked = state.rules[IssueKind.LOOK_ASIDE]?.enabled ?: true,
                onCheckedChange = onLookEnabled,
            )

            val lookThreshold = state.rules[IssueKind.LOOK_ASIDE]?.threshold ?: 30f
            var lookDeg by remember(lookThreshold) { mutableFloatStateOf(lookThreshold) }
            SliderRow(
                title = "眼神偏多少度算斜看",
                valueText = "${lookDeg.roundToInt()}°",
                hint = "只在头基本正对屏幕时判断。头明显转过去时不看这项，避免把转头误判成斜眼。",
                value = lookDeg,
                valueRange = 15f..50f,
                steps = 6,
                onChange = { lookDeg = it },
                onCommit = { onLookThreshold(lookDeg) },
            )

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            SectionTitle("距离", "次要")
            SwitchRow(
                title = "靠近提醒",
                hint = "离电视太近时一并提醒。",
                checked = state.rules[IssueKind.TOO_CLOSE]?.enabled ?: true,
                onCheckedChange = onNearEnabled,
            )

            val nearRatio = state.rules[IssueKind.TOO_CLOSE]?.threshold ?: 1.2f
            var ratio by remember(nearRatio) { mutableFloatStateOf(nearRatio) }
            SliderRow(
                title = "多近算「太近」",
                valueText = "${formatOneDecimal(ratio)} 倍",
                hint = "脸比校准时大这么多倍就开始计时。",
                value = ratio,
                valueRange = 1.10f..1.80f,
                steps = 13,
                onChange = { ratio = it },
                onCommit = { onNearRatio(ratio) },
            )

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            SectionTitle("提醒方式", "手机怎么喊")
            SwitchRow(
                title = "语音提醒",
                hint = "用手机扬声器喊中文。",
                checked = state.voiceEnabled,
                onCheckedChange = onVoiceEnabled,
            )
            SwitchRow(
                title = "先响一声提示音",
                hint = "孩子看电视时注意力在屏幕上，先响一下更容易听进去。",
                checked = state.alertSound,
                onCheckedChange = onAlertSound,
            )
            SwitchRow(
                title = "提醒时临时调大音量",
                hint = "播报时把媒体音量抬到 70%，播完还原。",
                checked = state.boostVolume,
                onCheckedChange = onBoostVolume,
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onTestVoice, modifier = Modifier.fillMaxWidth()) {
                Text("试听一下提醒声音")
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            SectionTitle("校准", null)
            Text(
                text = if (state.calibrated) {
                    "已校准。手机本身倾斜 ${state.baselineTiltDeg.roundToInt()} 度，已从这个角度里扣掉。"
                } else {
                    "还没校准。让孩子坐正，再点主界面的「校准」。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onClearCalibration,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.calibrated,
            ) {
                Text("清除校准")
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "这个 App 记录的是「歪头」这个动作，不是斜视诊断。" +
                    "歪头可能是习惯，也可能是视力问题的信号，想确认只能靠眼科检查。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text("知道了")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    hint: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatOneDecimal(value: Float): String {
    val rounded = (value * 10).roundToInt()
    return "${rounded / 10}.${rounded % 10}"
}
