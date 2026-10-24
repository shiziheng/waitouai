package com.huyan.tvguard.data

import android.content.Context
import com.huyan.tvguard.domain.IssueKind
import com.huyan.tvguard.domain.IssueRule
import com.huyan.tvguard.domain.MonitorEngine

/**
 * 本地配置存储。
 *
 * 全部存在本机，不上传。用 SharedPreferences 足够——都是标量配置，没必要上 DataStore/Room。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------- 校准 ----------

    /** 校准时记录的脸宽基准（归一化值）；0 表示还没校准 */
    var baselineFaceWidth: Float
        get() = prefs.getFloat(KEY_BASELINE_WIDTH, 0f)
        set(value) = prefs.edit().putFloat(KEY_BASELINE_WIDTH, value).apply()

    /**
     * 校准时记录的「画面倾斜基线」。
     *
     * 手机侧着放或倾斜摆放时，画面里的两眼连线天然是斜的。
     * 记下这个值，之后每次减掉它，得到的才是孩子真正多歪的角度。
     */
    var baselineTilt: Float
        get() = prefs.getFloat(KEY_BASELINE_TILT, 0f)
        set(value) = prefs.edit().putFloat(KEY_BASELINE_TILT, value).apply()

    val calibrated: Boolean
        get() = baselineFaceWidth > 0f

    fun clearCalibration() {
        prefs.edit().remove(KEY_BASELINE_WIDTH).remove(KEY_BASELINE_TILT).apply()
    }

    // ---------- 三类规则 ----------

    fun rules(): Map<IssueKind, IssueRule> = mapOf(
        IssueKind.TOO_CLOSE to IssueRule(
            kind = IssueKind.TOO_CLOSE,
            enabled = nearEnabled,
            threshold = nearRatio,
            sustainMillis = nearSustainMillis,
            cooldownMillis = nearCooldownMillis,
        ),
        IssueKind.HEAD_TILT to IssueRule(
            kind = IssueKind.HEAD_TILT,
            enabled = tiltEnabled,
            threshold = tiltThresholdDeg,
            sustainMillis = tiltSustainMillis,
            cooldownMillis = tiltCooldownMillis,
        ),
        IssueKind.LOOK_ASIDE to IssueRule(
            kind = IssueKind.LOOK_ASIDE,
            enabled = lookEnabled,
            threshold = lookThresholdDeg,
            sustainMillis = lookSustainMillis,
            cooldownMillis = lookCooldownMillis,
        ),
    )

    var nearEnabled: Boolean
        get() = prefs.getBoolean(KEY_NEAR_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NEAR_ENABLED, value).apply()

    var nearRatio: Float
        get() = prefs.getFloat(KEY_NEAR_RATIO, MonitorEngine.DEFAULT_NEAR_RATIO)
        set(value) = prefs.edit().putFloat(KEY_NEAR_RATIO, value).apply()

    var nearSustainMillis: Long
        get() = prefs.getLong(KEY_NEAR_SUSTAIN, 3_000L)
        set(value) = prefs.edit().putLong(KEY_NEAR_SUSTAIN, value).apply()

    var nearCooldownMillis: Long
        get() = prefs.getLong(KEY_NEAR_COOLDOWN, 15_000L)
        set(value) = prefs.edit().putLong(KEY_NEAR_COOLDOWN, value).apply()

    var tiltEnabled: Boolean
        get() = prefs.getBoolean(KEY_TILT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TILT_ENABLED, value).apply()

    var tiltThresholdDeg: Float
        get() = prefs.getFloat(KEY_TILT_THRESHOLD, MonitorEngine.DEFAULT_TILT_DEG)
        set(value) = prefs.edit().putFloat(KEY_TILT_THRESHOLD, value).apply()

    var tiltSustainMillis: Long
        get() = prefs.getLong(KEY_TILT_SUSTAIN, 2_000L)
        set(value) = prefs.edit().putLong(KEY_TILT_SUSTAIN, value).apply()

    var tiltCooldownMillis: Long
        get() = prefs.getLong(KEY_TILT_COOLDOWN, 20_000L)
        set(value) = prefs.edit().putLong(KEY_TILT_COOLDOWN, value).apply()

    var lookEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOOK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LOOK_ENABLED, value).apply()

    var lookThresholdDeg: Float
        get() = prefs.getFloat(KEY_LOOK_THRESHOLD, MonitorEngine.DEFAULT_LOOK_DEG)
        set(value) = prefs.edit().putFloat(KEY_LOOK_THRESHOLD, value).apply()

    var lookSustainMillis: Long
        get() = prefs.getLong(KEY_LOOK_SUSTAIN, 3_000L)
        set(value) = prefs.edit().putLong(KEY_LOOK_SUSTAIN, value).apply()

    var lookCooldownMillis: Long
        get() = prefs.getLong(KEY_LOOK_COOLDOWN, 30_000L)
        set(value) = prefs.edit().putLong(KEY_LOOK_COOLDOWN, value).apply()

    // ---------- 提醒方式 ----------

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    var boostVolume: Boolean
        get() = prefs.getBoolean(KEY_BOOST, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOST, value).apply()

    /** 出声前先响一声提示音，比纯语音更容易抓住注意力 */
    var alertSound: Boolean
        get() = prefs.getBoolean(KEY_ALERT_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERT_SOUND, value).apply()

    private companion object {
        const val FILE_NAME = "tv_eye_guardian"
        const val KEY_BASELINE_WIDTH = "baseline_face_width"
        const val KEY_BASELINE_TILT = "baseline_tilt"
        const val KEY_NEAR_ENABLED = "near_enabled"
        const val KEY_NEAR_RATIO = "near_ratio"
        const val KEY_NEAR_SUSTAIN = "near_sustain_millis"
        const val KEY_NEAR_COOLDOWN = "near_cooldown_millis"
        const val KEY_TILT_ENABLED = "tilt_enabled"
        const val KEY_TILT_THRESHOLD = "tilt_threshold_deg"
        const val KEY_TILT_SUSTAIN = "tilt_sustain_millis"
        const val KEY_TILT_COOLDOWN = "tilt_cooldown_millis"
        const val KEY_LOOK_ENABLED = "look_enabled"
        const val KEY_LOOK_THRESHOLD = "look_threshold_deg"
        const val KEY_LOOK_SUSTAIN = "look_sustain_millis"
        const val KEY_LOOK_COOLDOWN = "look_cooldown_millis"
        const val KEY_VOICE = "voice_enabled"
        const val KEY_BOOST = "boost_volume"
        const val KEY_ALERT_SOUND = "alert_sound"
    }
}
