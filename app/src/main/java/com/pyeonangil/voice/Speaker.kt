package com.pyeonangil.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 짧은 음성 안내. Pleos TextToSpeech SDK(ON_DEVICE)를 먼저 쓰고, 초기화·재생에 실패하면 안드로이드 기본 TTS로 바꾼다.
 * 소리가 안 나는 환경(에뮬레이터 등)을 대비해 화면 자막은 뷰모델이 따로 띄운다.
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var pleos: TextToSpeech? = null
    private var pleosReady = false
    private var fallback: AndroidTts? = null
    private var fallbackReady = false
    private var pending: String? = null

    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "Pleos TTS 오류 $errorType → 기본 TTS")
            main.post { switchToFallback() }
        }
    }

    init {
        val tts = runCatching { TextToSpeech(appContext, Mode.ON_DEVICE) }.getOrNull()
        if (tts == null) {
            switchToFallback()
        } else {
            pleos = tts
            runCatching {
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        main.post {
                            runCatching {
                                tts.addEventListener(events)
                                tts.setLocale(Locale.KOREA)
                            }
                            pleosReady = true
                            pending?.let { pending = null; speak(it) }
                        }
                    }

                    override fun onFailure(errorType: ErrorType) {
                        Log.w(TAG, "Pleos TTS 초기화 실패 $errorType → 기본 TTS")
                        main.post { switchToFallback() }
                    }
                })
            }.onFailure { switchToFallback() }
            // 음성 서비스가 응답하지 않는 경우 대비.
            main.postDelayed({ if (!pleosReady) switchToFallback() }, 5_000)
        }
    }

    fun speak(text: String) {
        when {
            pleosReady -> runCatching { pleos?.speak(text, true) }.onFailure {
                pending = text
                switchToFallback()
            }
            fallback != null -> if (fallbackReady) {
                fallback?.speak(text, AndroidTts.QUEUE_FLUSH, null, "pyeonan-${System.nanoTime()}")
            } else {
                pending = text
            }
            else -> pending = text
        }
    }

    fun release() {
        pleos?.let { runCatching { it.removeEventListener(events); it.release() } }
        fallback?.shutdown()
    }

    private fun switchToFallback() {
        if (fallback != null) return
        pleosReady = false
        pleos?.let { runCatching { it.removeEventListener(events); it.release() } }
        pleos = null
        fallback = AndroidTts(appContext) { status ->
            main.post {
                fallbackReady = status == AndroidTts.SUCCESS
                if (fallbackReady) {
                    fallback?.setLanguage(Locale.KOREAN)
                    pending?.let { pending = null; speak(it) }
                } else {
                    Log.w(TAG, "기본 TTS도 없음: 자막만 표시")
                }
            }
        }
    }

    private companion object {
        const val TAG = "PyeonanVoice"
    }
}
