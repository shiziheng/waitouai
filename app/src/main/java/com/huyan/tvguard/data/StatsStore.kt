package com.huyan.tvguard.data

import android.content.Context
import com.huyan.tvguard.domain.IssueKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 一天的观测统计。
 *
 * 只记数字，不记图像、不记人名、不记具体时间点。
 * 目标是给医生一份客观的行为记录，不给任何诊断结论。
 */
data class DayStats(
    val date: String,
    val closeCount: Int = 0,
    val closeMillis: Long = 0,
    val tiltCount: Int = 0,
    val tiltMillis: Long = 0,
    val tiltLeftCount: Int = 0,
    val tiltRightCount: Int = 0,
    val lookCount: Int = 0,
    val lookMillis: Long = 0,
    val alertCount: Int = 0,
    val watchMillis: Long = 0,
) {
    val hasData: Boolean
        get() = closeCount > 0 || tiltCount > 0 || lookCount > 0 || watchMillis > 0

    fun countOf(kind: IssueKind): Int = when (kind) {
        IssueKind.TOO_CLOSE -> closeCount
        IssueKind.HEAD_TILT -> tiltCount
        IssueKind.LOOK_ASIDE -> lookCount
    }

    fun millisOf(kind: IssueKind): Long = when (kind) {
        IssueKind.TOO_CLOSE -> closeMillis
        IssueKind.HEAD_TILT -> tiltMillis
        IssueKind.LOOK_ASIDE -> lookMillis
    }
}

/**
 * 按天滚动的统计存储。
 *
 * 用 SharedPreferences 存一个紧凑的文本块，每行一天。
 * 三十天的数据量很小，没必要为此引入 Room。
 */
class StatsStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------- 记录 ----------

    /** 一次异常行为结束（持续够久了） */
    fun recordIssue(kind: IssueKind, durationMillis: Long, tiltLeft: Boolean? = null) {
        mutate(currentDate()) { stats ->
            when (kind) {
                IssueKind.TOO_CLOSE -> stats.copy(
                    closeCount = stats.closeCount + 1,
                    closeMillis = stats.closeMillis + durationMillis,
                )

                IssueKind.HEAD_TILT -> stats.copy(
                    tiltCount = stats.tiltCount + 1,
                    tiltMillis = stats.tiltMillis + durationMillis,
                    tiltLeftCount = stats.tiltLeftCount + if (tiltLeft == true) 1 else 0,
                    tiltRightCount = stats.tiltRightCount + if (tiltLeft == false) 1 else 0,
                )

                IssueKind.LOOK_ASIDE -> stats.copy(
                    lookCount = stats.lookCount + 1,
                    lookMillis = stats.lookMillis + durationMillis,
                )
            }
        }
    }

    /** 一次语音提醒 */
    fun recordAlert() {
        mutate(currentDate()) { it.copy(alertCount = it.alertCount + 1) }
    }

    /** 累计监测时长 */
    fun recordWatchTime(millis: Long) {
        if (millis <= 0L) return
        mutate(currentDate()) { it.copy(watchMillis = it.watchMillis + millis) }
    }

    // ---------- 读取 ----------

    fun loadAll(): List<DayStats> = parse(prefs.getString(KEY_DATA, "") ?: "")

    fun loadRecent(days: Int): List<DayStats> {
        val all = loadAll().associateBy { it.date }
        return (0 until days).map { offset ->
            val date = dateOf(offset)
            all[date] ?: DayStats(date)
        }
    }

    fun loadToday(): DayStats = loadAll().firstOrNull { it.date == currentDate() } ?: DayStats(currentDate())

    fun clearAll() {
        prefs.edit().remove(KEY_DATA).apply()
    }

    // ---------- 导出 ----------

    /**
     * 生成一份可以直接给医生看的纯文本。
     *
     * 刻意不写任何结论，只陈述观测到的事实，并注明这是家庭环境下的粗略估计。
     */
    fun buildReport(hasTiltDetection: Boolean = true): String {
        val days = loadRecent(REPORT_DAYS)
        val withData = days.filter { it.hasData }
        val sb = StringBuilder()

        sb.appendLine("电视护眼管家 · 观看行为记录")
        sb.appendLine("生成时间：${nowText()}")
        sb.appendLine()

        if (withData.isEmpty()) {
            sb.appendLine("还没有可用的记录。需要先开始监测并积累几天数据。")
            return sb.toString()
        }

        sb.appendLine("【说明】")
        sb.appendLine("以下数据由手机摄像头在家庭环境中自动记录，用于观察孩子的观看距离和头部姿态，" )
        sb.appendLine("不代表任何医学检查结果，不能作为诊断依据。请结合临床检查判断。")
        sb.appendLine()

        sb.appendLine("【近 ${REPORT_DAYS} 天汇总】")
        val tiltTotal = withData.sumOf { it.tiltCount }
        val tiltLeft = withData.sumOf { it.tiltLeftCount }
        val tiltRight = withData.sumOf { it.tiltRightCount }
        sb.appendLine("有记录的天数：${withData.size} 天")
        sb.appendLine("累计监测时长：${formatDuration(withData.sumOf { it.watchMillis })}")
        sb.appendLine("歪头发生：${tiltTotal} 次，累计 ${formatDuration(withData.sumOf { it.tiltMillis })}")
        if (tiltTotal > 0) {
            val leftPct = tiltLeft * 100 / tiltTotal
            sb.appendLine("  其中偏左 ${tiltLeft} 次（${leftPct}%），偏右 ${tiltRight} 次（${100 - leftPct}%）")
            val dominant = when {
                leftPct >= 65 -> "偏左为主"
                leftPct <= 35 -> "偏右为主"
                else -> "左右相当"
            }
            sb.appendLine("  倾向：$dominant")
        }
        sb.appendLine("凑近电视：${withData.sumOf { it.closeCount }} 次，累计 ${formatDuration(withData.sumOf { it.closeMillis })}")
        sb.appendLine("眼神偏离：${withData.sumOf { it.lookCount }} 次，累计 ${formatDuration(withData.sumOf { it.lookMillis })}")
        sb.appendLine("语音提醒：${withData.sumOf { it.alertCount }} 次")
        sb.appendLine()

        sb.appendLine("【逐日明细】")
        sb.appendLine("日期        监测时长   歪头次数  歪头时长  左/右     凑近  提醒")
        days.sortedByDescending { it.date }.forEach { d ->
            if (!d.hasData) return@forEach
            sb.appendLine(
                String.format(
                    Locale.CHINA,
                    "%s  %6s  %6d  %7s  %2d/%-2d  %4d  %4d",
                    d.date,
                    formatDuration(d.watchMillis),
                    d.tiltCount,
                    formatDuration(d.tiltMillis),
                    d.tiltLeftCount,
                    d.tiltRightCount,
                    d.closeCount,
                    d.alertCount,
                )
            )
        }

        sb.appendLine()
        sb.appendLine("【观测方式说明】")
        sb.appendLine("· 手机放在电视附近，前置摄像头每秒约 5 次采样，全部在本机处理，不保存图像。")
        if (hasTiltDetection) {
            sb.appendLine("· 歪头判定：两眼连线相对水平线偏转超过阈值并持续数秒，扣除手机摆放角度后计入。")
            sb.appendLine("· 该角度由单张二维画面估算，受孩子转头、手机位置、光线影响，精度有限。")
        }
        sb.appendLine("· 数据仅供参考，如需精确评估请以眼科检查为准。")

        return sb.toString()
    }

    // ---------- 内部 ----------

    private fun mutate(date: String, transform: (DayStats) -> DayStats) {
        val all = loadAll().associateBy { it.date }.toMutableMap()
        val current = all[date] ?: DayStats(date)
        all[date] = transform(current)
        save(all.values.sortedByDescending { it.date }.take(MAX_DAYS))
    }

    private fun save(days: List<DayStats>) {
        val text = days.joinToString("\n") { d ->
            listOf(
                d.date,
                d.closeCount,
                d.closeMillis,
                d.tiltCount,
                d.tiltMillis,
                d.tiltLeftCount,
                d.tiltRightCount,
                d.lookCount,
                d.lookMillis,
                d.alertCount,
                d.watchMillis,
            ).joinToString("|")
        }
        prefs.edit().putString(KEY_DATA, text).apply()
    }

    private fun parse(text: String): List<DayStats> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 11) return@mapNotNull null
            runCatching {
                DayStats(
                    date = p[0],
                    closeCount = p[1].toInt(),
                    closeMillis = p[2].toLong(),
                    tiltCount = p[3].toInt(),
                    tiltMillis = p[4].toLong(),
                    tiltLeftCount = p[5].toInt(),
                    tiltRightCount = p[6].toInt(),
                    lookCount = p[7].toInt(),
                    lookMillis = p[8].toLong(),
                    alertCount = p[9].toInt(),
                    watchMillis = p[10].toLong(),
                )
            }.getOrNull()
        }.toList()
    }

    private fun currentDate(): String = dateOf(0)

    private fun dateOf(offsetDays: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -offsetDays)
        return dateFormat.format(cal.time)
    }

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())

    companion object {
        private const val FILE_NAME = "tv_eye_guardian_stats"
        private const val KEY_DATA = "daily_stats"
        private const val MAX_DAYS = 30
        private const val REPORT_DAYS = 14

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

        fun formatDuration(millis: Long): String {
            if (millis <= 0L) return "0分"
            val totalMinutes = millis / 60_000L
            if (totalMinutes <= 0L) {
                val seconds = (millis / 1000L).coerceAtLeast(1L)
                return "${seconds}秒"
            }
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分"
        }
    }
}
