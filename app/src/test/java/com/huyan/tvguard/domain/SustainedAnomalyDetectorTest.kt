package com.huyan.tvguard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SustainedAnomalyDetectorTest {

    private lateinit var detector: SustainedAnomalyDetector

    private val rule = IssueRule(
        kind = IssueKind.HEAD_TILT,
        enabled = true,
        threshold = 15f,
        sustainMillis = 2_000L,
        cooldownMillis = 20_000L,
    )

    @Before
    fun setUp() {
        detector = SustainedAnomalyDetector(rule)
    }

    @Test
    fun `没到阈值不触发`() {
        val tick = detectorSample(12f, 0L)
        assertFalse(tick.active)
        assertFalse(tick.shouldAlert)
    }

    @Test
    fun `刚超阈值但时间不够时不提醒`() {
        detectorSample(20f, 0L)
        val tick = detectorSample(20f, 1_500L)

        assertTrue(tick.active)
        assertFalse(tick.shouldAlert)
        assertEquals(500L, tick.remainMillis)
    }

    @Test
    fun `持续到时间才提醒一次`() {
        detectorSample(20f, 0L)
        detectorSample(20f, 1_000L)

        val tick = detectorSample(20f, 2_000L)
        assertTrue(tick.shouldAlert)
    }

    @Test
    fun `冷却期内不重复提醒`() {
        detectorSample(20f, 0L)
        assertTrue(detectorSample(20f, 2_000L).shouldAlert)

        assertFalse(detectorSample(20f, 3_500L).shouldAlert)
        assertFalse(detectorSample(20f, 10_000L).shouldAlert)
        assertFalse(detectorSample(20f, 20_000L).shouldAlert)
    }

    @Test
    fun `冷却结束后会再次提醒`() {
        detectorSample(20f, 0L)
        assertTrue(detectorSample(20f, 2_000L).shouldAlert)

        // 冷却自 2_000 起算 20 秒，到 22_000 结束；期间不重复出声
        assertFalse(detectorSample(20f, 21_000L).shouldAlert)
        // 冷却一结束、孩子还在歪，立刻再喊一次
        assertTrue(detectorSample(20f, 22_000L).shouldAlert)
    }

    @Test
    fun `冷却期内一直歪头，冷却一过还会再提醒`() {
        detectorSample(20f, 0L)
        assertTrue(detectorSample(20f, 2_000L).shouldAlert)

        // 冷却到 22_000 才结束，中间只喂一帧也要保持不响
        assertFalse(detectorSample(20f, 21_500L).shouldAlert)
        // 冷却结束后的第一帧就该响
        assertTrue(detectorSample(20f, 22_100L).shouldAlert)
    }

    @Test
    fun `恢复正常后计时清零`() {
        detectorSample(20f, 0L)
        detectorSample(20f, 1_500L)

        val normal = detectorSample(5f, 1_600L)
        assertFalse(normal.active)

        // 2_000 时重新歪头，要重新攒满 2 秒；3_400 时已攒 1_400，还差 600
        assertFalse(detectorSample(20f, 2_000L).shouldAlert)
        assertEquals(600L, detectorSample(20f, 3_400L).remainMillis)
    }

    @Test
    fun `短暂丢帧不清零`() {
        detectorSample(20f, 0L)
        detectorSample(20f, 1_000L)

        val lost = detectorSample(null, 1_300L)
        assertTrue(lost.active)

        assertTrue(detectorSample(20f, 2_000L).shouldAlert)
    }

    @Test
    fun `长时间丢帧重置状态`() {
        detectorSample(20f, 0L)
        detectorSample(20f, 1_000L)

        val lost = detectorSample(null, 1_000L + SustainedAnomalyDetector.DEFAULT_LOST_TOLERANCE_MILLIS + 1)
        assertFalse(lost.active)
    }

    @Test
    fun `规则关闭后永不触发`() {
        detector.rule = rule.copy(enabled = false)

        var now = 0L
        repeat(30) {
            val tick = detectorSample(45f, now)
            assertFalse(tick.active)
            assertFalse(tick.shouldAlert)
            now += 200
        }
    }

    @Test
    fun `reset 后回到初始状态`() {
        detectorSample(20f, 0L)
        detectorSample(20f, 3_000L)
        detector.reset()

        assertFalse(detectorSample(20f, 3_100L).shouldAlert)
    }

    private fun detectorSample(value: Float?, now: Long): IssueTick =
        detector.onSample(value, now)
}
