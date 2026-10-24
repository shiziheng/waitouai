package com.huyan.tvguard.domain

import kotlin.math.abs

/**
 * 一帧人脸信号。所有角度单位是度，正值表示画面里的顺时针方向。
 *
 * @param faceWidth 脸宽占画面宽度的比例（用于判断远近）
 * @param tiltDeg   两眼连线相对水平线的角度。画面里孩子歪头偏左，读数偏负；偏右偏正。
 *                  注意这是「画面坐标」，不是孩子的真实头姿，见 [Angles.residualTilt]
 * @param lookDeg   眼神偏离「正前方」的角度。绝对值越大，越是在斜着看。
 *                  **null 表示这一帧没测出来**（头转太多、虹膜没抓到），不等于「正对」
 * @param eyeSpan   两只外眼角的归一化距离。越小说明脸在画面里越远、角度越不可信
 */
data class FaceSignal(
    val faceWidth: Float,
    val tiltDeg: Float,
    val lookDeg: Float? = null,
    val eyeSpan: Float = 0f,
) {
    /**
     * 这个倾斜角读数是否可信。
     *
     * 两眼外眼角跨度太小的时候，几个像素的关键点抖动就能让 atan2 算出的角度
     * 飘出十几二十度——这正是「明明没歪头却一直报歪头」的主因之一。
     * 跨度低于 [MIN_TRUSTED_EYE_SPAN] 时宁可不判断。
     */
    val tiltReliable: Boolean
        get() = eyeSpan >= MIN_TRUSTED_EYE_SPAN

    companion object {
        /**
         * 可信的最小眼角跨度。
         *
         * 640 宽的画面上，这个值约等于 45 像素。低于这个距离，眼角的定位精度
         * （MediaPipe 自带误差量级）换算成角度已经超过 5 度，不足以支撑 15 度阈值。
         */
        const val MIN_TRUSTED_EYE_SPAN = 0.07f
    }
}

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
