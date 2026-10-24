package com.huyan.tvguard.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.huyan.tvguard.audio.ReminderSpeaker
import com.huyan.tvguard.data.DayStats
import com.huyan.tvguard.data.SettingsStore
import com.huyan.tvguard.data.StatsStore
import com.huyan.tvguard.domain.Angles
import com.huyan.tvguard.domain.FaceSignal
import com.huyan.tvguard.domain.IssueKind
import com.huyan.tvguard.domain.IssueRule
import com.huyan.tvguard.domain.IssueTick
import com.huyan.tvguard.domain.MonitorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 实际判定的总体状态，只用于界面显示 */
enum class LiveStatus {
    IDLE,
    NOT_CALIBRATED,
    NO_FACE,
    SAFE,
    WARNING,
    ALERTING,
    ERROR,
}

data class GuardianUiState(
    val monitoring: Boolean = false,
    val calibrated: Boolean = false,
    val baselineTiltDeg: Float = 0f,
    val status: LiveStatus = LiveStatus.IDLE,

    /** 实时倾斜角（已扣除手机基线） */
    val liveTiltDeg: Float = 0f,
    val liveLookDeg: Float = 0f,
    val liveNearRatio: Float = 0f,

    /** 当前正在计时的异常，以及还差多少毫秒提醒 */
    val activeIssues: Map<IssueKind, IssueTick> = emptyMap(),
    val nextAlertInMillis: Long = 0L,

    /** 今日统计 */
    val today: DayStats = DayStats(""),
    val alertCount: Int = 0,

    val rules: Map<IssueKind, IssueRule> = MonitorEngine.defaultRules(),
    val voiceEnabled: Boolean = true,
    val boostVolume: Boolean = true,
    val alertSound: Boolean = true,

    val engineError: String? = null,
    val toast: String? = null,
) {
    val anyActive: Boolean get() = activeIssues.isNotEmpty()
}

class GuardianViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val stats = StatsStore(app)
    private val speaker = ReminderSpeaker(app)
    private val engine = MonitorEngine(settings.rules())

    private val _uiState = MutableStateFlow(
        GuardianUiState(
            calibrated = settings.calibrated,
            baselineTiltDeg = settings.baselineTilt,
            rules = settings.rules(),
            voiceEnabled = settings.voiceEnabled,
            boostVolume = settings.boostVolume,
            alertSound = settings.alertSound,
            today = stats.loadToday(),
            status = if (settings.calibrated) LiveStatus.IDLE else LiveStatus.NOT_CALIBRATED,
        )
    )
    val uiState: StateFlow<GuardianUiState> = _uiState.asStateFlow()

    private val recentTilt = ArrayDeque<Sample>()
    private val recentWidth = ArrayDeque<Sample>()

    private var lastFrameAt = 0L
    private var monitoringStartedAt = 0L
    private var countedWatchMillis = 0L
    private var tickerJob: Job? = null

    private var alertIndex = 0

    init {
        speaker.onStatus = { status -> _uiState.update { it.copy(engineError = status) } }
        speaker.initialize()
    }

    // ---------- 监测开关 ----------

    fun startMonitoring() {
        if (_uiState.value.monitoring) return

        engine.updateRules(settings.rules())
        engine.reset()
        recentTilt.clear()
        recentWidth.clear()
        lastFrameAt = 0L
        monitoringStartedAt = SystemClock.elapsedRealtime()
        countedWatchMillis = 0L

        _uiState.update {
            it.copy(
                monitoring = true,
                alertCount = 0,
                activeIssues = emptyMap(),
                nextAlertInMillis = 0L,
                liveTiltDeg = 0f,
                liveLookDeg = 0f,
                liveNearRatio = 0f,
                status = if (it.calibrated) LiveStatus.NO_FACE else LiveStatus.NOT_CALIBRATED,
            )
        }
        startTicker()
    }

    fun stopMonitoring() {
        if (!_uiState.value.monitoring) return

        flushWatchTime()
        engine.reset()
        speaker.stop()
        stopTicker()
        recentTilt.clear()
        recentWidth.clear()

        _uiState.update {
            it.copy(
                monitoring = false,
                status = if (it.calibrated) LiveStatus.IDLE else LiveStatus.NOT_CALIBRATED,
                activeIssues = emptyMap(),
                nextAlertInMillis = 0L,
                liveTiltDeg = 0f,
                liveLookDeg = 0f,
                liveNearRatio = 0f,
                today = stats.loadToday(),
            )
        }
    }

    /** 倒计时用：界面需要每 100ms 重算「还差多久」，也负责把人脸超时打回 NO_FACE */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                delay(TICK_INTERVAL_MILLIS)
                val state = _uiState.value
                if (!state.monitoring) break

                flushWatchTimeIfNeeded()

                val now = SystemClock.elapsedRealtime()
                if (lastFrameAt != 0L && now - lastFrameAt > FACE_STALE_MILLIS &&
                    (state.activeIssues.isNotEmpty() || state.status == LiveStatus.SAFE)
                ) {
                    engine.reset()
                    _uiState.update {
                        it.copy(
                            activeIssues = emptyMap(),
                            nextAlertInMillis = 0L,
                            status = LiveStatus.NO_FACE,
                        )
                    }
                }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // ---------- 识别回调 ----------

    fun onSignal(signal: FaceSignal?) {
        viewModelScope.launch(Dispatchers.Main) {
            val now = SystemClock.elapsedRealtime()
            val state = _uiState.value
            if (!state.monitoring) return@launch

            if (signal == null) {
                if (lastFrameAt == 0L || now - lastFrameAt > FACE_STALE_MILLIS) {
                    _uiState.update {
                        it.copy(
                            status = LiveStatus.NO_FACE,
                            activeIssues = emptyMap(),
                            nextAlertInMillis = 0L,
                        )
                    }
                }
                return@launch
            }

            lastFrameAt = now

            recentTilt.addLast(Sample(signal.tiltDeg, now))
            recentWidth.addLast(Sample(signal.faceWidth, now))
            trim(recentTilt)
            trim(recentWidth)

            if (!state.calibrated) {
                _uiState.update {
                    it.copy(
                        status = LiveStatus.NOT_CALIBRATED,
                        liveTiltDeg = signal.tiltDeg,
                        liveLookDeg = signal.lookDeg,
                        liveNearRatio = 0f,
                    )
                }
                return@launch
            }

            val baselineWidth = settings.baselineFaceWidth
            val baselineTilt = settings.baselineTilt

            val nearRatio = if (baselineWidth > 0f) signal.faceWidth / baselineWidth else null
            val residualTilt = Angles.residualTilt(signal.tiltDeg, baselineTilt)
            val look = signal.lookDeg

            val result = engine.onFrame(nearRatio, residualTilt, look, now)

            // 记账：异常结束时记录持续时长
            result.finishedDurations.forEach { (kind, millis) ->
                if (kind == IssueKind.HEAD_TILT) {
                    stats.recordIssue(kind, millis, tiltLeft = residualTilt < 0f)
                } else {
                    stats.recordIssue(kind, millis)
                }
            }

            val activeIssues = result.ticks.filterValues { it.active }
            val nextAlertIn = activeIssues.values
                .filter { it.remainMillis > 0L }
                .minOfOrNull { it.remainMillis } ?: 0L

            _uiState.update {
                it.copy(
                    status = when {
                        activeIssues.isEmpty() -> LiveStatus.SAFE
                        activeIssues.values.any { it.remainMillis == 0L } -> LiveStatus.ALERTING
                        else -> LiveStatus.WARNING
                    },
                    liveTiltDeg = residualTilt,
                    liveLookDeg = look,
                    liveNearRatio = nearRatio ?: 0f,
                    activeIssues = activeIssues,
                    nextAlertInMillis = nextAlertIn,
                    today = if (result.finishedDurations.isNotEmpty()) stats.loadToday() else it.today,
                )
            }

            if (result.alerts.isNotEmpty()) {
                onAlert(result.alerts, residualTilt)
            }
        }
    }

    fun onFaceTooLarge() {
        viewModelScope.launch(Dispatchers.Main) {
            if (!_uiState.value.monitoring) return@launch
            _uiState.update {
                it.copy(
                    status = LiveStatus.NO_FACE,
                    activeIssues = emptyMap(),
                    nextAlertInMillis = 0L,
                )
            }
        }
    }

    fun onEngineError(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update { it.copy(engineError = message, status = LiveStatus.ERROR) }
        }
    }

    private fun onAlert(kinds: List<IssueKind>, residualTilt: Float) {
        val state = _uiState.value
        _uiState.update { it.copy(alertCount = it.alertCount + 1) }
        stats.recordAlert()

        // 多类同时触发只喊一次，歪头优先级最高——这是这个 App 最该管的事
        val primary = kinds.firstOrNull { it == IssueKind.HEAD_TILT } ?: kinds.first()

        val line = when (primary) {
            IssueKind.HEAD_TILT -> {
                val lines = if (residualTilt < 0f) TILT_LEFT_LINES else TILT_RIGHT_LINES
                lines[alertIndex % lines.size]
            }
            IssueKind.TOO_CLOSE -> NEAR_LINES[alertIndex % NEAR_LINES.size]
            IssueKind.LOOK_ASIDE -> LOOK_LINES[alertIndex % LOOK_LINES.size]
        }
        alertIndex++

        if (!state.voiceEnabled) return
        if (state.alertSound) speaker.alertTone()

        if (!speaker.speak(line, state.boostVolume)) {
            _uiState.update { it.copy(toast = "语音引擎还没准备好，这次没出声") }
        }
    }

    // ---------- 校准 ----------

    fun calibrate() {
        val now = SystemClock.elapsedRealtime()
        val widths = recentWidth.filter { now - it.at <= SAMPLE_FRESH_MILLIS }
        val tilts = recentTilt.filter { now - it.at <= SAMPLE_FRESH_MILLIS }

        if (widths.size < MIN_CALIBRATION_SAMPLES) {
            _uiState.update {
                it.copy(toast = "画面里看不清孩子的脸。让孩子坐在合适的位置，正对手机 1~2 秒再点校准。")
            }
            return
        }

        val avgWidth = widths.map { it.value }.average().toFloat()
        // 取中位数而非平均值，避免孩子歪头那一两帧把基线带偏
        val medianTilt = tilts.map { it.value }.sorted().let {
            if (it.isEmpty()) 0f else it[it.size / 2]
        }

        settings.baselineFaceWidth = avgWidth
        settings.baselineTilt = medianTilt

        engine.updateRules(settings.rules())
        engine.reset()

        _uiState.update {
            it.copy(
                calibrated = true,
                baselineTiltDeg = medianTilt,
                status = if (it.monitoring) LiveStatus.SAFE else LiveStatus.IDLE,
                toast = if (abs(medianTilt) > TILT_BASELINE_WARN_DEG) {
                    "基准已记录。手机本身斜了 ${medianTilt.roundToInt()} 度，已补偿；摆正手机会更准。"
                } else {
                    "基准已记录：这个距离和这个坐姿算正常"
                },
            )
        }
    }

    fun clearCalibration() {
        settings.clearCalibration()
        engine.reset()
        _uiState.update {
            it.copy(
                calibrated = false,
                baselineTiltDeg = 0f,
                status = if (it.monitoring) LiveStatus.NOT_CALIBRATED else LiveStatus.IDLE,
                activeIssues = emptyMap(),
                toast = "已清除校准，需要重新校准",
            )
        }
    }

    // ---------- 设置 ----------

    fun setNearEnabled(enabled: Boolean) {
        settings.nearEnabled = enabled
        applyRules()
    }

    fun setNearRatio(value: Float) {
        settings.nearRatio = value.coerceIn(1.05f, 2.00f)
        applyRules()
    }

    fun setTiltEnabled(enabled: Boolean) {
        settings.tiltEnabled = enabled
        applyRules()
    }

    fun setTiltThreshold(deg: Float) {
        settings.tiltThresholdDeg = deg.coerceIn(8f, 40f)
        applyRules()
    }

    fun setTiltSustain(millis: Long) {
        settings.tiltSustainMillis = millis.coerceIn(1_000L, 10_000L)
        applyRules()
    }

    fun setLookEnabled(enabled: Boolean) {
        settings.lookEnabled = enabled
        applyRules()
    }

    fun setLookThreshold(deg: Float) {
        settings.lookThresholdDeg = deg.coerceIn(15f, 60f)
        applyRules()
    }

    fun setVoiceEnabled(enabled: Boolean) {
        settings.voiceEnabled = enabled
        _uiState.update { it.copy(voiceEnabled = enabled) }
    }

    fun setBoostVolume(enabled: Boolean) {
        settings.boostVolume = enabled
        _uiState.update { it.copy(boostVolume = enabled) }
    }

    fun setAlertSound(enabled: Boolean) {
        settings.alertSound = enabled
        _uiState.update { it.copy(alertSound = enabled) }
    }

    fun testVoice() {
        val state = _uiState.value
        if (state.alertSound) speaker.alertTone()
        val ok = speaker.speak("这是提醒声音，孩子歪头看电视时会这样提醒", state.boostVolume)
        _uiState.update {
            it.copy(toast = if (ok) "正在试听" else "语音引擎还没准备好，请稍后再试")
        }
    }

    private fun applyRules() {
        val rules = settings.rules()
        engine.updateRules(rules)
        _uiState.update { it.copy(rules = rules) }
    }

    // ---------- 统计 ----------

    fun refreshStats() {
        _uiState.update { it.copy(today = stats.loadToday()) }
    }

    fun recentStats(days: Int): List<DayStats> = stats.loadRecent(days)

    fun buildReport(): String = stats.buildReport()

    fun clearStats() {
        stats.clearAll()
        _uiState.update { it.copy(today = stats.loadToday(), toast = "已清空统计数据") }
    }

    // ---------- 杂项 ----------

    fun consumeToast() {
        _uiState.update { it.copy(toast = null) }
    }

    fun dismissEngineError() {
        _uiState.update { it.copy(engineError = null) }
    }

    /** 监测时长按 10 秒一段累加，避免每帧都写盘 */
    private fun flushWatchTimeIfNeeded() {
        if (!_uiState.value.monitoring || monitoringStartedAt == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - monitoringStartedAt
        if (elapsed - countedWatchMillis >= WATCH_FLUSH_MILLIS) {
            val delta = elapsed - countedWatchMillis
            countedWatchMillis = elapsed
            stats.recordWatchTime(delta)
        }
    }

    private fun flushWatchTime() {
        if (monitoringStartedAt == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - monitoringStartedAt
        val delta = elapsed - countedWatchMillis
        if (delta > 0L) stats.recordWatchTime(delta)
        monitoringStartedAt = 0L
        countedWatchMillis = 0L
    }

    private fun trim(queue: ArrayDeque<Sample>) {
        while (queue.size > MAX_SAMPLES) queue.removeFirst()
    }

    override fun onCleared() {
        flushWatchTime()
        stopTicker()
        speaker.release()
        super.onCleared()
    }

    private data class Sample(val value: Float, val at: Long)

    private companion object {
        const val MAX_SAMPLES = 15
        const val MIN_CALIBRATION_SAMPLES = 3
        const val SAMPLE_FRESH_MILLIS = 1_500L
        const val FACE_STALE_MILLIS = 1_500L
        const val TICK_INTERVAL_MILLIS = 100L
        const val WATCH_FLUSH_MILLIS = 10_000L
        const val TILT_BASELINE_WARN_DEG = 8f

        val NEAR_LINES = listOf(
            "小朋友，离电视远一点哦",
            "太近啦，往后退一点点",
            "往后退一步，眼睛要休息",
        )

        /** 画面里孩子头往左歪，要让他往右正回来 */
        val TILT_LEFT_LINES = listOf(
            "头往右边正一正，别歪着看",
            "歪头啦，坐正一点，别歪着看",
            "把头摆正，两边肩膀放平",
        )
        val TILT_RIGHT_LINES = listOf(
            "头往左边正一正，别歪着看",
            "歪头啦，坐正一点，别歪着看",
            "把头摆正，两边肩膀放平",
        )
        val LOOK_LINES = listOf(
            "眼睛看正前方，别斜着看",
            "把头转过来，眼睛对着电视",
        )
    }
}
