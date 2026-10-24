package com.huyan.tvguard.domain

import kotlin.math.abs

/**
 * 一帧人脸信号。所有角度单位是度，正值表示画面里的顺时针方向。
 *
 * @param faceWidth 脸宽占画面宽度的比例（用于判断远近）
 * @param tiltDeg   两眼连线相对水平线的角度。画面里孩子歪头偏左，读数偏负；偏右偏正。
 *                  注意这是「画面坐标」，不是孩子的真实头姿，见 [TiltRuleEngine]
 * @param lookDeg   眼神偏离「正前方」的角度。绝对值越大，越是在斜着看
 */
data class FaceSignal(
    val faceWidth: Float,
    val tiltDeg: Float,
    val lookDeg: Float,
)

/** 一帧的检测结果：要么有有效信号，要么说清楚为什么没有 */
sealed interface FrameReading {
    data class Ok(val signal: FaceSignal) : FrameReading

    /** 画面里没有可用的脸 */
    data object NoFace : FrameReading

    /** 脸太大，超出画面，判断不了 */
    data object TooClose : FrameReading

    /** 识别引擎出错 */
    data class Failed(val reason: String) : FrameReading
}

/** 角度工具 */
object Angles {

    /**
     * 把角度规整到 [-90, 90]。
     *
     * 只用两眼连线判断头部左右是否歪，返回本质上是一个轴向（mod 180），
     * 所以 -85° 和 +95° 描述的是同一种歪法，必须归一化到同一侧。
     */
    fun normalizeTilt(angle: Float): Float {
        if (angle.isNaN()) return 0f
        // 折回 (-90, 90]：85 → 85；100 → -80；190 → 10；-100 → 80。
        // 注意这里是「轴向」归一，只保留 ±90 以内的表述，
        // 不试图区分 176° 和 4°，因为单看角度本身这两个数字无法分辨（详见 residualTilt）。
        var a = angle % 180f
        if (a > 90f) a -= 180f
        if (a <= -90f) a += 180f
        if (a.isNaN()) return 0f
        return a
    }

    /**
     * 扣除「手机本身没摆平」的基线倾斜。
     *
     * 手机侧着放、或者倾斜着摆，画面里的两眼连线天然就是斜的。
     * 校准的时候记下这个读数当基线，之后每次减掉它，剩下的才是孩子真正多歪的部分。
     */
    fun residualTilt(rawTilt: Float, baselineTilt: Float): Float =
        normalizeTilt(rawTilt - baselineTilt)

    /** 判断是否偏向某一侧（用剩余倾斜） */
    fun isLeft(deg: Float): Boolean = deg < 0f

    fun abs(deg: Float): Float = abs(deg)
}
