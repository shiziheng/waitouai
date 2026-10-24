package com.huyan.tvguard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地中文语音提醒。
 *
 * 全部走系统 TTS，不联网合成、不录音。手机放在电视旁边，靠扬声器直接喊孩子。
 */
class ReminderSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ready = AtomicBoolean(false)

    private var engine: TextToSpeech? = null
    private var pendingRestoreVolume = -1
    private var utteranceSeq = 0
    private var tone: android.media.ToneGenerator? = null

    /** 引擎异常、缺中文语音包时回调，界面据此提示家长 */
    var onStatus: ((String) -> Unit)? = null

    fun initialize() {
        if (engine != null) return

        val created = runCatching {
            TextToSpeech(appContext) { status ->
                // 回调可能早于 engine 赋值完成，投递到主线程队列尾部再读 engine
                mainHandler.post {
                    if (status != TextToSpeech.SUCCESS) {
                        onStatus?.invoke("语音引擎初始化失败，提醒不会出声。可以在系统设置里检查文字转语音引擎。")
                        return@post
                    }
                    val tts = engine ?: return@post

                    val language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    if (language == TextToSpeech.LANG_MISSING_DATA ||
                        language == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        onStatus?.invoke("手机没有中文语音包，提醒可能不会出声。请到系统设置安装中文「文字转语音」数据。")
                    }

                    tts.setSpeechRate(0.95f)
                    tts.setPitch(1.05f)
                    tts.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            restoreVolume()
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            restoreVolume()
                        }
                    })

                    ready.set(true)
                }
            }
        }.getOrNull()

        if (created == null) {
            onStatus?.invoke("这台手机没有可用的语音引擎，提醒不会出声。界面上的状态提示仍然有效。")
            return
        }
        engine = created
    }

    /** @return true 表示已经交给语音引擎；false 表示引擎还没好 */
    fun speak(text: String, boostVolume: Boolean): Boolean {
        val tts = engine ?: return false
        if (!ready.get()) return false

        if (boostVolume) boostVolume()

        val utteranceId = "alert-${++utteranceSeq}"
        return runCatching {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }.isSuccess
    }

    /**
     * 先响一声提示音。
     *
     * 孩子看电视时注意力全在屏幕上，纯语音经常被忽略。一声短促的「叮」能先把注意力拉回来，
     * 后面那句话才听得进去。
     */
    fun alertTone() {
        runCatching {
            if (tone == null) {
                tone = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
            }
            // 两短一长，区别于普通通知音
            tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 180)
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
        restoreVolume()
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        restoreVolume()
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        runCatching { tone?.release() }
        tone = null
        ready.set(false)
    }

    /**
     * 手机常常被人调成低音量，喊了等于没喊。
     * 播报前把媒体音量临时抬到 70%，播完（或 6 秒后兜底）再恢复原值。
     */
    private fun boostVolume() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (max * BOOST_TARGET_RATIO).toInt().coerceAtLeast(1)

        if (current >= target) return

        val applied = runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }.isSuccess
        if (!applied) return

        pendingRestoreVolume = current
        mainHandler.removeCallbacks(restoreRunnable)
        mainHandler.postDelayed(restoreRunnable, RESTORE_TIMEOUT_MILLIS)
    }

    private val restoreRunnable = Runnable { restoreVolume() }

    private fun restoreVolume() {
        val previous = pendingRestoreVolume
        if (previous < 0) return
        pendingRestoreVolume = -1
        mainHandler.removeCallbacks(restoreRunnable)
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0) }
    }

    companion object {
        private const val BOOST_TARGET_RATIO = 0.7f
        private const val RESTORE_TIMEOUT_MILLIS = 6_000L
        private const val TONE_VOLUME = 90

        /** 轮换着说，别让提醒听着像防空警报 */
        val ALERT_LINES = listOf(
            "小朋友，离电视远一点哦",
            "太近啦，往后退一点点",
            "往后退一步，眼睛要休息",
            "离远一点，这样对眼睛更好",
        )
    }
}
