package com.huyan.tvguard.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.huyan.tvguard.domain.Angles
import com.huyan.tvguard.domain.FaceSignal
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * 从每帧画面里提取三样东西：
 *
 * 1. **脸宽比例** —— 用于判断靠近（相对校准基准的倍数）
 * 2. **头部倾斜角** —— 两眼连线相对水平线的角度，用于判断歪头
 * 3. **视线偏离角** —— 瞳孔相对眼角的位置，用于判断斜着看
 *
 * 三样都在本地算完就丢，不保存图像、不记录关键点、不上传。
 */
class FaceSignalAnalyzer(
    private val appContext: Context,
    private val onSignal: (FaceSignal?) -> Unit,
    private val onFaceTooLarge: () -> Unit,
    private val onEngineError: (String) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    private var landmarker: FaceLandmarker? = null
    private var engineFailed = false
    private var closed = false

    private var lastTimestamp = 0L
    private var inferenceInFlight = false

    @Volatile
    private var lastFeedAt = 0L

    /** 平滑用的历史值。人脸抖动很厉害，不平滑的话角度会乱跳 */
    private var smoothTilt: Float? = null
    private var smoothLook: Float? = null
    private var smoothWidth: Float? = null

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()

        if (closed) {
            image.close()
            return
        }

        // 约 5 帧/秒，够判断又不过热
        if (now - lastFeedAt < MIN_FRAME_INTERVAL_MILLIS || inferenceInFlight) {
            image.close()
            return
        }
        lastFeedAt = now

        val engine = ensureLandmarker()
        if (engine == null) {
            image.close()
            return
        }

        var bitmap: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            bitmap = image.toArgbBitmap()
            rotated = bitmap.rotateBy(image.imageInfo.rotationDegrees)

            lastTimestamp = max(lastTimestamp + 1, now)
            inferenceInFlight = true
            engine.detectAsync(BitmapImageBuilder(rotated).build(), lastTimestamp)
        } catch (t: Throwable) {
            inferenceInFlight = false
            onEngineError("识别失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            image.close()
            if (rotated !== bitmap) bitmap?.recycle()
        }
    }

    private fun ensureLandmarker(): FaceLandmarker? {
        landmarker?.let { return it }
        if (engineFailed || closed) return null

        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(2)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { error -> onEngineError(error.message ?: "识别引擎出错") }
                .build()

            FaceLandmarker.createFromOptions(appContext, options).also { landmarker = it }
        } catch (t: Throwable) {
            engineFailed = true
            onEngineError("识别模型加载失败：${t.message ?: t.javaClass.simpleName}")
            null
        }
    }

    private fun handleResult(result: FaceLandmarkerResult) {
        inferenceInFlight = false
        if (closed) return

        val faces = result.faceLandmarks()
        if (faces.isEmpty()) {
            clearSmoothing()
            onSignal(null)
            return
        }

        // 多人入镜时取面积最大的那张，也就是离手机最近的孩子
        val face = faces.maxByOrNull { faceWidth(it) }

        if (face == null || face.size < REQUIRED_LANDMARK_COUNT) {
            clearSmoothing()
            onSignal(null)
            return
        }

        val width = faceWidth(face)

        // 脸撑满画面：既算不准远近，也算不准角度，直接说判断不了
        if (width > FACE_TOO_LARGE_RATIO) {
            clearSmoothing()
            onFaceTooLarge()
            return
        }

        if (width < MIN_VALID_WIDTH) {
            clearSmoothing()
            onSignal(null)
            return
        }

        val rawTilt = tiltDegrees(face)
        val rawLook = lookAsideDegrees(face)

        val tilt = smoothTilt(rawTilt)
        val look = rawLook?.let { smoothLook(it) }
        val smoothW = smoothWidth(width)

        onSignal(
            FaceSignal(
                faceWidth = smoothW,
                tiltDeg = tilt,
                // 测不到就传 null，不要伪装成 0 度。
                // 传 0 会让上层分不清「眼睛确实正对」和「根本没测出来」两件事。
                lookDeg = look,
                // 脸在画面里越小，角度读数越不可靠，一起交给上层判断
                eyeSpan = eyeSpan(face),
            )
        )
    }

    // ---------- 几何计算 ----------

    private fun faceWidth(face: List<NormalizedLandmark>): Float {
        if (face.size <= RIGHT_CHEEK) return 0f
        return abs(face[RIGHT_CHEEK].x() - face[LEFT_CHEEK].x())
    }

    /**
     * 两只外眼角在画面里的实际距离（归一化，0~1）。
     *
     * 这个值决定倾斜角算得准不准：跨度越小，同样的关键点抖动换算出的角度误差越大。
     * 上层用它来判断「这个角度值该不该信」，而不是看到一个数就当真理。
     */
    private fun eyeSpan(face: List<NormalizedLandmark>): Float {
        if (face.size <= LEFT_EYE_OUTER) return 0f
        val a = face[RIGHT_EYE_OUTER]
        val b = face[LEFT_EYE_OUTER]
        return hypot((b.x() - a.x()).toDouble(), (b.y() - a.y()).toDouble()).toFloat()
    }

    /**
     * 两眼连线相对水平线的夹角。
     *
     * 用外眼角（33 = 左外，263 = 右外）算，距离比瞳孔远，角度更稳。
     * 结果经 [Angles.normalizeTilt] 收敛到 (-90, 90]，再直接映射成度数。
     */
    private fun tiltDegrees(face: List<NormalizedLandmark>): Float {
        val a = face[RIGHT_EYE_OUTER]
        val b = face[LEFT_EYE_OUTER]
        val dx = b.x() - a.x()
        val dy = b.y() - a.y()
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return Angles.normalizeTilt(degrees)
    }

    /**
     * 眼神偏了多少度。
     *
     * 思路：用内外眼角算出眼睛的「宽度轴」和「朝向轴」，得到当前瞳孔相对眼角的位置比例 t。
     * t 接近 0.5 表示正看镜头，偏向任一侧都意味着斜着看。
     *
     * 这个值受头姿影响——头转过去眼神自然偏。所以只有当**头基本正对屏幕**时才有意义，
     * 头转了的情况下返回 null，交给歪头规则去管，这里不重复判断。
     */
    private fun lookAsideDegrees(face: List<NormalizedLandmark>): Float? {
        val tilt = tiltDegrees(face)
        // 头明显偏转时，眼神偏移不代表在斜看，不判断
        if (abs(tilt) > MAX_TILT_FOR_LOOK_CHECK) return null

        val leftIris = irisCenter(face, 468, 469, 470, 471)
        val rightIris = irisCenter(face, 473, 474, 475, 476)
        if (leftIris == null || rightIris == null) return null

        val leftRatio = eyePositionRatio(
            inner = face[LEFT_EYE_INNER], outer = face[LEFT_EYE_OUTER], iris = leftIris
        ) ?: return null

        val rightRatio = eyePositionRatio(
            inner = face[RIGHT_EYE_INNER], outer = face[RIGHT_EYE_OUTER], iris = rightIris
        ) ?: return null

        // 偏左偏右对「斜着看」是等价的，取绝对值；两眼的平均更抗单眼误差
        val mean = (leftRatio + rightRatio) / 2f
        return abs((mean - 0.5f) * 180f)
    }

    private fun eyePositionRatio(
        inner: NormalizedLandmark,
        outer: NormalizedLandmark,
        iris: Pair<Float, Float>,
    ): Float? {
        val dx = inner.x() - outer.x()
        val dy = inner.y() - outer.y()
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return null

        // 瞳孔在内→外连线上的投影比例
        val px = iris.first - outer.x()
        val py = iris.second - outer.y()
        return ((px * dx + py * dy) / lenSq).coerceIn(0f, 1f)
    }

    private fun irisCenter(
        face: List<NormalizedLandmark>,
        centerIdx: Int,
        a: Int,
        b: Int,
        c: Int,
    ): Pair<Float, Float>? {
        if (face.size <= maxOf(centerIdx, a, b, c)) return null
        val i = face[centerIdx]
        // 用虹膜四个边缘点校验一下，太小说明检测不可靠
        val ra = face[a]
        val rb = face[c]
        val span = hypot((rb.x() - ra.x()).toDouble(), (rb.y() - ra.y()).toDouble()).toFloat()
        if (span < 1e-5f) return null
        return i.x() to i.y()
    }

    // ---------- 平滑 ----------

    private fun smoothTilt(value: Float): Float {
        val previous = smoothTilt
        val next = if (previous == null) value else lerp(previous, value, SMOOTHING_ALPHA)
        smoothTilt = next
        return next
    }

    private fun smoothLook(value: Float): Float {
        val previous = smoothLook
        val next = if (previous == null) value else lerp(previous, value, LOOK_SMOOTHING_ALPHA)
        smoothLook = next
        return next
    }

    private fun smoothWidth(value: Float): Float {
        val previous = smoothWidth
        val next = if (previous == null) value else lerp(previous, value, SMOOTHING_ALPHA)
        smoothWidth = next
        return next
    }

    private fun lerp(from: Float, to: Float, alpha: Float): Float = from + (to - from) * alpha

    private fun clearSmoothing() {
        smoothTilt = null
        smoothLook = null
        smoothWidth = null
    }

    override fun close() {
        closed = true
        inferenceInFlight = false
        runCatching { landmarker?.close() }
        landmarker = null
    }

    // ---------- 图像转换 ----------

    private fun ImageProxy.toArgbBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)

        val buffer = plane.buffer
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        return if (paddedWidth == width) {
            padded
        } else {
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            padded.recycle()
            cropped
        }
    }

    private fun Bitmap.rotateBy(degrees: Int): Bitmap {
        if (degrees % 360 == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    companion object {
        const val MODEL_ASSET = "face_landmarker.task"

        private const val MIN_FRAME_INTERVAL_MILLIS = 200L

        // 关键点索引（MediaPipe Face Mesh 标准拓扑）
        private const val LEFT_CHEEK = 234
        private const val RIGHT_CHEEK = 454
        private const val LEFT_EYE_OUTER = 263
        private const val RIGHT_EYE_OUTER = 33
        private const val LEFT_EYE_INNER = 362
        private const val RIGHT_EYE_INNER = 133
        private const val REQUIRED_LANDMARK_COUNT = 478

        /** 归一化宽度低于此值视为没看到人 */
        private const val MIN_VALID_WIDTH = 0.03f

        /** 脸宽超过画面这个比例，判断不了远近，也算不准角度 */
        private const val FACE_TOO_LARGE_RATIO = 0.85f

        /** 头偏超过这个角度就不做眼神判断，避免把头转当斜眼 */
        private const val MAX_TILT_FOR_LOOK_CHECK = 25f

        private const val SMOOTHING_ALPHA = 0.35f
        private const val LOOK_SMOOTHING_ALPHA = 0.25f
    }
}
