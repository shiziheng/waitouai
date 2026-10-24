package com.huyan.tvguard.domain

/**
 * 检测哪一类异常。
 *
 * 这是「行为现象」的分类，不是「疾病」的分类。
 */
enum class IssueKind {
    /** 离电视太近 */
    TOO_CLOSE,

    /** 歪着头看 */
    HEAD_TILT,

    /** 斜着眼看（眼神明显偏到一边） */
    LOOK_ASIDE,
}

/**
 * 某类异常的判定参数。
 *
 * @param enabled     是否启用
 * @param threshold   触发阈值（靠近=倍数，歪头/斜视=角度）
 * @param sustainMillis 要连续超过阈值多久才提醒
 * @param cooldownMillis 两次提醒之间的最小间隔
 */
data class IssueRule(
    val kind: IssueKind,
    val enabled: Boolean,
    val threshold: Float,
    val sustainMillis: Long,
    val cooldownMillis: Long,
)

/** 一次判定的输出 */
data class IssueTick(
    val kind: IssueKind,
    val active: Boolean,
    /** 当前是否需要出声提醒（只在越过持续门槛的那一刻为 true） */
    val shouldAlert: Boolean = false,
    /** 本轮「从头攒时间」已经攒了多久（提醒后或被冷却挡住都会重新归零） */
    val heldMillis: Long = 0L,
    /** 还差多久进入提醒 */
    val remainMillis: Long = 0L,
    /** 这一路已经持续异常了多久（不受提醒、冷却影响，用于统计真实时长） */
    val continuousMillis: Long = 0L,
    /** 当前实测值（角度或倍数），用于界面展示 */
    val value: Float = 0f,
)

/**
 * 单类异常的持续判定器。
 *
 * 三类异常共用这套逻辑，只是喂进来的数值不同。设计约束：
 * 1. 单帧异常不处罚，必须连续超阈值达到 sustainMillis；
 * 2. 短暂丢帧不清零，超过容忍窗口才重置；
 * 3. 提醒后进入冷却，冷却内不重复出声；
 * 4. 提醒后重新计时，冷却一结束不会立刻又喊。
 */
class SustainedAnomalyDetector(
    var rule: IssueRule,
    private val lostToleranceMillis: Long = DEFAULT_LOST_TOLERANCE_MILLIS,
) {

    private var overSince = NO_TIME
    private var lastAlertAt = NO_TIME
    private var lastValidAt = NO_TIME
    private var wasActive = false

    /** 本轮持续异常的起点；只在这一路从「正常」翻到「异常」时记录，提醒/冷却不重置 */
    private var epochStart = NO_TIME

    var active: Boolean = false
        private set


    fun reset() {
        overSince = NO_TIME
        lastAlertAt = NO_TIME
        lastValidAt = NO_TIME
        wasActive = false
        epochStart = NO_TIME
        active = false
    }

    /**
     * @param value 当前实测值；无效时传 null
     * @param now   单调时钟毫秒
     */
    fun onSample(value: Float?, now: Long): IssueTick {
        val rule = this.rule

        if (!rule.enabled) {
            reset()
            return IssueTick(rule.kind, active = false)
        }

        if (value == null || value.isNaN()) {
            return onLost(now)
        }

        lastValidAt = now

        if (value < rule.threshold) {
            overSince = NO_TIME
            epochStart = NO_TIME
            active = false
            wasActive = false
            return IssueTick(rule.kind, active = false, value = value)
        }

        if (overSince == NO_TIME) {
            overSince = now
        }
        if (epochStart == NO_TIME) {
            epochStart = now
        }

        active = true
        wasActive = true

        // 什么时候可以再喊一次：
        //   本轮已经连续超出阈值满 sustainMillis，并且距上次提醒已经过了 cooldownMillis。
        // 这两个条件分开算，不要混进同一个 held 里——混进去以后冷却期一过
        // held 恰好归零，提醒就再也不会触发（这个坑真踩过）。
        val heldThisRound = now - overSince
        val cooldownOver = lastAlertAt == NO_TIME || now - lastAlertAt >= rule.cooldownMillis
        val enoughHeld = heldThisRound >= rule.sustainMillis

        if (!enoughHeld || !cooldownOver) {
            // 还在攒时间，或者攒够了但仍在冷却里
            val remain = when {
                !cooldownOver -> rule.cooldownMillis - (now - lastAlertAt)
                else -> rule.sustainMillis - heldThisRound
            }
            return IssueTick(
                kind = rule.kind,
                active = true,
                heldMillis = minOf(heldThisRound, rule.sustainMillis).coerceAtLeast(0L),
                remainMillis = remain.coerceAtLeast(0L),
                continuousMillis = continuousMillis(now),
                value = value,
            )
        }

        // 够条件了，出声提醒；下一轮重新攒
        lastAlertAt = now
        overSince = now
        return IssueTick(
            kind = rule.kind,
            active = true,
            shouldAlert = true,
            heldMillis = 0L,
            remainMillis = rule.sustainMillis,
            continuousMillis = continuousMillis(now),
            value = value,
        )
    }

    /** 本轮持续异常的真实总时长（提醒、冷却都不会把它清零） */
    private fun continuousMillis(now: Long): Long =
        if (epochStart == NO_TIME) 0L else now - epochStart

    private fun onLost(now: Long): IssueTick {
        val recentlySeen = lastValidAt != NO_TIME && now - lastValidAt <= lostToleranceMillis
        if (recentlySeen && wasActive) {
            // 短暂丢帧，保持原状态但不推进计时
            return IssueTick(rule.kind, active = active)
        }
        reset()
        return IssueTick(rule.kind, active = false)
    }

    companion object {
        const val NO_TIME = -1L

        /** 丢帧容忍窗口：孩子低头捡个东西不该重新排队 */
        const val DEFAULT_LOST_TOLERANCE_MILLIS = 1_200L
    }
}

/**
 * 三路并发的规则引擎：靠近 + 歪头 + 斜视。
 *
 * 每路独立计时、独立冷却、独立统计，互不干扰。
 * 孩子同时既近又歪头时，两路都会各自计时，提醒只发一次（由上层合并）。
 */
class MonitorEngine(
    initialRules: Map<IssueKind, IssueRule> = defaultRules(),
) {

    private val detectors: MutableMap<IssueKind, SustainedAnomalyDetector> =
        initialRules.mapValues { (_, rule) -> SustainedAnomalyDetector(rule) }.toMutableMap()

    /** 每一路异常开始的时刻；判定器内部会在提醒/冷却时重置计时，所以时长在这里记账 */
    private val episodeStart = mutableMapOf<IssueKind, Long>()

    fun updateRules(rules: Map<IssueKind, IssueRule>) {
        rules.forEach { (kind, rule) ->
            detectors[kind]?.rule = rule
            if (!rule.enabled) detectors[kind]?.reset()
        }
    }

    fun reset() {
        detectors.values.forEach { it.reset() }
        episodeStart.clear()
    }

    /**
     * 喂一帧。
     *
     * @param nearRatio 当前脸宽 / 校准基准；无效传 null
     * @param tiltDeg   扣除基线后的剩余倾斜角；无效传 null
     * @param lookDeg   视线偏离角；无效传 null
     * @return 每一路的判定输出，以及刚刚结束的异常持续时长
     */
    fun onFrame(
        nearRatio: Float?,
        tiltDeg: Float?,
        lookDeg: Float?,
        now: Long,
    ): FrameResult {
        val ticks = LinkedHashMap<IssueKind, IssueTick>()
        val finished = mutableMapOf<IssueKind, Long>()
        val alerts = mutableListOf<IssueKind>()

        fun feed(kind: IssueKind, value: Float?) {
            val detector = detectors[kind] ?: return
            val tick = detector.onSample(value, now)
            ticks[kind] = tick

            if (tick.shouldAlert) {
                alerts += kind
            }

            if (tick.active) {
                episodeStart.putIfAbsent(kind, now)
            } else {
                val startedAt = episodeStart.remove(kind) ?: 0L
                val duration = now - startedAt
                if (duration >= MIN_RECORDED_DURATION_MILLIS) {
                    finished[kind] = duration
                }
            }
        }

        feed(IssueKind.TOO_CLOSE, nearRatio)
        // 歪头和斜视只用「偏移多大的绝对值」比较，方向单独记录
        feed(IssueKind.HEAD_TILT, tiltDeg?.let { kotlin.math.abs(it) })
        feed(IssueKind.LOOK_ASIDE, lookDeg?.let { kotlin.math.abs(it) })

        return FrameResult(ticks = ticks, finishedDurations = finished, alerts = alerts)
    }

    companion object {
        /** 短于这个时长的异常不记账，避免统计被抖动的零碎事件灌满 */
        const val MIN_RECORDED_DURATION_MILLIS = 1_000L

        const val DEFAULT_NEAR_RATIO = 1.20f

        /**
         * 歪头阈值。
         *
         * 原来是 15°，实测误报太多——孩子靠着沙发靠背、稍微侧一点身子，
         * 头自然就是歪几度的，15° 很容易被日常坐姿撞破。
         * 20° 才是「明显歪着头」的程度，配合 3 秒持续，能滤掉大部分正常姿势。
         */
        const val DEFAULT_TILT_DEG = 20f
        const val DEFAULT_LOOK_DEG = 30f

        fun defaultRules(): Map<IssueKind, IssueRule> = mapOf(
            IssueKind.TOO_CLOSE to IssueRule(
                kind = IssueKind.TOO_CLOSE,
                enabled = true,
                threshold = DEFAULT_NEAR_RATIO,
                sustainMillis = 3_000L,
                cooldownMillis = 15_000L,
            ),
            IssueKind.HEAD_TILT to IssueRule(
                kind = IssueKind.HEAD_TILT,
                enabled = true,
                threshold = DEFAULT_TILT_DEG,
                // 歪头比靠近更值得管，但也不能一歪就喊：给 3 秒让人自然坐正
                sustainMillis = 3_000L,
                cooldownMillis = 20_000L,
            ),
            IssueKind.LOOK_ASIDE to IssueRule(
                kind = IssueKind.LOOK_ASIDE,
                enabled = true,
                threshold = DEFAULT_LOOK_DEG,
                sustainMillis = 3_000L,
                cooldownMillis = 30_000L,
            ),
        )
    }
}

data class FrameResult(
    val ticks: Map<IssueKind, IssueTick>,
    val finishedDurations: Map<IssueKind, Long>,
    /** 这一帧里越过持续门槛、应当出声提醒的异常 */
    val alerts: List<IssueKind> = emptyList(),
) {
    fun tick(kind: IssueKind): IssueTick? = ticks[kind]

    val alertingKinds: List<IssueKind>
        get() = ticks.values.filter { it.shouldAlert }.map { it.kind }

    val anyActive: Boolean
        get() = ticks.values.any { it.active }
}
