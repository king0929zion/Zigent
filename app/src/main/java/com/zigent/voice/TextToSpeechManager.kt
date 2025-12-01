package com.zigent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.zigent.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

/**
 * TTS状态
 */
enum class TtsState {
    IDLE,           // 空闲
    SPEAKING,       // 正在播报
    ERROR           // 错误
}

/**
 * TTS回调接口
 */
interface TtsCallback {
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String, errorCode: Int)
}

/**
 * 文字转语音管理器
 * 使用Android原生TTS引擎
 */
class TextToSpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    // TTS引擎
    private var tts: TextToSpeech? = null
    
    // 初始化状态
    private var isInitialized = false
    
    // 播报状态
    private val _state = MutableStateFlow(TtsState.IDLE)
    val state: StateFlow<TtsState> = _state.asStateFlow()
    
    // 回调
    var callback: TtsCallback? = null
    
    // 语速（0.5-2.0，默认1.0）
    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setSpeechRate(field)
        }
    
    // 音调（0.5-2.0，默认1.0）
    var pitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    /**
     * 初始化TTS引擎
     * @param onInitialized 初始化完成回调
     */
    fun initialize(onInitialized: ((Boolean) -> Unit)? = null) {
        if (tts != null) {
            Logger.w("TTS already initialized", TAG)
            onInitialized?.invoke(isInitialized)
            return
        }
        
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 设置语言为中文
                val result = tts?.setLanguage(Locale.CHINESE)
                
                isInitialized = when (result) {
                    TextToSpeech.LANG_MISSING_DATA,
                    TextToSpeech.LANG_NOT_SUPPORTED -> {
                        Logger.w("Chinese not supported, falling back to default", TAG)
                        // 尝试使用默认语言
                        tts?.setLanguage(Locale.getDefault())
                        true
                    }
                    else -> true
                }
                
                if (isInitialized) {
                    // 设置语速和音调
                    tts?.setSpeechRate(speechRate)
                    tts?.setPitch(pitch)
                    
                    // 设置播报监听器
                    setupUtteranceListener()
                    
                    Logger.i("TTS initialized successfully", TAG)
                }
            } else {
                isInitialized = false
                Logger.e("TTS initialization failed: $status", TAG)
            }
            
            onInitialized?.invoke(isInitialized)
        }
    }

    /**
     * 设置播报进度监听器
     */
    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Logger.d("TTS started: $utteranceId", TAG)
                _state.value = TtsState.SPEAKING
                utteranceId?.let { callback?.onStart(it) }
            }

            override fun onDone(utteranceId: String?) {
                Logger.d("TTS done: $utteranceId", TAG)
                _state.value = TtsState.IDLE
                utteranceId?.let { callback?.onDone(it) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Logger.e("TTS error: $utteranceId", TAG)
                _state.value = TtsState.ERROR
                utteranceId?.let { callback?.onError(it, -1) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Logger.e("TTS error: $utteranceId, code: $errorCode", TAG)
                _state.value = TtsState.ERROR
                utteranceId?.let { callback?.onError(it, errorCode) }
            }
        })
    }

    /**
     * 播报文本
     * @param text 要播报的文本
     * @param utteranceId 播报ID，用于追踪
     * @param queueMode 队列模式，QUEUE_FLUSH清空队列，QUEUE_ADD添加到队列
     */
    fun speak(
        text: String,
        utteranceId: String = UUID.randomUUID().toString(),
        queueMode: Int = TextToSpeech.QUEUE_FLUSH
    ): Boolean {
        if (!isInitialized) {
            Logger.e("TTS not initialized", TAG)
            return false
        }
        
        if (text.isBlank()) {
            Logger.w("Empty text to speak", TAG)
            return false
        }
        
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        
        val result = tts?.speak(text, queueMode, params, utteranceId)
        
        return if (result == TextToSpeech.SUCCESS) {
            Logger.d("Speaking: ${text.take(50)}...", TAG)
            true
        } else {
            Logger.e("Failed to speak: $result", TAG)
            false
        }
    }

    /**
     * 停止播报
     */
    fun stop() {
        tts?.stop()
        _state.value = TtsState.IDLE
        Logger.d("TTS stopped", TAG)
    }

    /**
     * 检查是否正在播报
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    /**
     * 添加到播报队列
     */
    fun addToQueue(text: String, utteranceId: String = UUID.randomUUID().toString()): Boolean {
        return speak(text, utteranceId, TextToSpeech.QUEUE_ADD)
    }

    /**
     * 获取可用的TTS引擎列表
     */
    fun getAvailableEngines(): List<TextToSpeech.EngineInfo>? {
        return tts?.engines
    }

    /**
     * 设置TTS引擎
     */
    fun setEngine(enginePackageName: String) {
        tts?.shutdown()
        tts = TextToSpeech(context, { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                tts?.setLanguage(Locale.CHINESE)
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(pitch)
                setupUtteranceListener()
            }
        }, enginePackageName)
    }

    /**
     * 释放资源
     */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Logger.i("TTS released", TAG)
    }
}

