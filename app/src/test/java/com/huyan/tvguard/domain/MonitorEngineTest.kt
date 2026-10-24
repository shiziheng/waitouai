package com.huyan.tvguard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 多路并发 + 角度归一化 + 统计时长。
 */
class MonitorEngineTest {

    private lateinit var engine: MonitorEngine

    @Before
    fun setUp() {
        engine = MonitorEngine()
    }

    @Test
    fun `三路可以同时各自计时`() {
        engine.onFrame(nearRatio = 1.4f, tiltDeg = 25f, lookDeg = 40f, now = 0L)

        // 歪头默认 2 秒就提醒，比靠近（3 秒）更早
        val at2100 = engine.onFrame(1.4f, 25f, 40f, 2_100L)
        assertTrue(at2100.alerts.contains(IssueKind.HEAD_TILT))
        assertFalse(at2100.alerts.contains(IssueKind.TOO_CLOSE))

        // 到 3 秒时靠近也提醒
        val at3100 = engine.onFrame(1.4f, 25f, 40f, 3_100L)
        assertTrue(at3100.alerts.contains(IssueKind.TOO_CLOSE))
    }

    @Test
    fun `只有歪头时只报歪头`() {
        engine.onFrame(1.0f, 22f, 10f, 0L)
        val tick = engine.onFrame(1.0f, 22f, 10f, 2_000L)

        assertEquals(listOf(IssueKind.HEAD_TILT), tick.alerts)
        assertFalse(tick.tick(IssueKind.TOO_CLOSE)!!.active)
        assertFalse(tick.tick(IssueKind.LOOK_ASIDE)!!.active)
    }

    @Test
    fun `负角度也算歪头`() {
        engine.onFrame(1.0f, -25f, 5f, 0L)
        val tick = engine.onFrame(1.0f, -25f, 5f, 2_000L)

        assertTrue(tick.tick(IssueKind.HEAD_TILT)!!.active)
        assertTrue(tick.alerts.contains(IssueKind.HEAD_TILT))
    }

    @Test
    fun `异常结束后统计出持续时长`() {
        engine.onFrame(1.0f, 25f, 5f, 0L)
        engine.onFrame(1.0f, 25f, 5f, 1_000L)
        // 2 秒门槛到了，出声提醒一次
        assertTrue(engine.onFrame(1.0f, 25f, 5f, 4_000L).alerts.contains(IssueKind.HEAD_TILT))

        // 6 秒时恢复正常：这一轮从 0 开始算，共持续 6 秒
        val tick = engine.onFrame(1.0f, 2f, 5f, 6_000L)
        assertEquals(6_000L, tick.finishedDurations[IssueKind.HEAD_TILT])
    }
    @Test
    fun `太短的异常不记账`() {
        engine.onFrame(1.0f, 25f, 5f, 0L)
        val tick = engine.onFrame(1.0f, 2f, 5f, 500L)

        assertTrue(tick.finishedDurations.isEmpty())
    }

    @Test
    fun `关掉某一路后该路不参与判定`() {
        val rules = MonitorEngine.defaultRules().toMutableMap()
        rules[IssueKind.HEAD_TILT] = rules.getValue(IssueKind.HEAD_TILT).copy(enabled = false)
        val custom = MonitorEngine(rules)

        custom.onFrame(1.0f, 30f, 5f, 0L)
        val tick = custom.onFrame(1.0f, 30f, 5f, 5_000L)

        assertFalse(tick.tick(IssueKind.HEAD_TILT)!!.active)
        assertTrue(tick.alerts.isEmpty())
    }

    @Test
    fun `无效信号不会误触发`() {
        var now = 0L
        repeat(20) {
            val tick = engine.onFrame(null, null, null, now)
            assertTrue(tick.alerts.isEmpty())
            assertFalse(tick.anyActive)
            now += 250
        }
    }
}

class AnglesTest {

    @Test
    fun `角度归一到负90到90`() {
        assertEquals(85f, Angles.normalizeTilt(85f), 0.001f)
        assertEquals(-80f, Angles.normalizeTilt(100f), 0.001f)
        assertEquals(80f, Angles.normalizeTilt(-100f), 0.001f)
        assertEquals(10f, Angles.normalizeTilt(190f), 0.001f)
    }

    @Test
    fun `扣除手机基线后得到剩余倾斜`() {
        // 手机斜着放，基线 12 度；孩子头正 → 剩余应为 0
        assertEquals(0f, Angles.residualTilt(12f, 12f), 0.001f)
        // 孩子多歪了 20 度
        assertEquals(20f, Angles.residualTilt(32f, 12f), 0.001f)
        // 往反方向歪
        assertEquals(-24f, Angles.residualTilt(-12f, 12f), 0.001f)
    }

    @Test
    fun `跨越正负号时结果保持连续`() {
        // 基线 70 度（手机快侧放了），当前 -60 度：净差 -130 度，轴向折回应为 +50 度
        assertEquals(50f, Angles.residualTilt(-60f, 70f), 0.001f)
        // 从 89.9 到 -89.9，净差 -179.8，轴向折回后接近 +0.2，不能跳成 -179.8
        assertEquals(0.2f, Angles.residualTilt(-89.9f, 89.9f), 0.01f)
    }

    @Test
    fun `左右方向判断`() {
        assertTrue(Angles.isLeft(-20f))
        assertFalse(Angles.isLeft(20f))
    }
}
