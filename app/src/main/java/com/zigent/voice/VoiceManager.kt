package com.zigent.voice

import android.content.Context
import com.zigent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音交互状态
 */
enum class VoiceInteractionState {
    IDLE,           // 空闲
    LISTENING,      // 正在聆听
    RECOGNIZING,    // 正在识别
    SPEAKING,       // 正在播报
    ERROR           // 错误
}

/**
 * 语音交互结果
 */
data class VoiceInteractionResult(
    val success: Boolean,
    val text: String = "",
    val errorMessage: String = ""
)

/**
 * 语音交互管理器
 * 统一管理语音识别和语音合成
 */
@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VoiceManager"
    }

    // 语音识别器
    private val recognizer = VoiceRecognizer(context)
    
    // 语音合成器
    private val tts = TextToSpeechManager(context)
    
    // 交互状态
    private val _state = MutableStateFlow(VoiceInteractionState.IDLE)
    val state: StateFlow<VoiceInteractionState> = _state.asStateFlow()
    
    // 最后识别的文本
    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()
    
    // 识别结果回调
    var onRecognitionResult: ((VoiceInteractionResult) -> Unit)? = null
    
    // 是否已初始化
    private var isInitialized = false

    /**
     * 初始化语音服务
     */
    fun initialize(onComplete: ((Boolean) -> Unit)? = null) {
        if (isInitialized) {
            onComplete?.invoke(true)
            return
        }
        
        Logger.i("Initializing VoiceManager", TAG)
        
        // 初始化语音识别
        recognizer.initialize()
        recognizer.callback = createRecognitionCallback()
        
        // 初始化TTS
        tts.initialize { success ->
            isInitialized = success
            if (success) {
                tts.callback = createTtsCallback()
                Logger.i("VoiceManager initialized", TAG)
            } else {
                Logger.e("Failed to initialize VoiceManager", TAG)
            }
            onComplete?.invoke(success)
        }
    }

    /**
     * 创建语音识别回调
     */
    private fun createRecognitionCallback(): VoiceRecognitionCallback {
        return object : VoiceRecognitionCallback {
            override fun onResult(text: String) {
                Logger.d("Recognition result: $text", TAG)
                _lastRecognizedText.value = text
                _state.value = VoiceInteractionState.IDLE
                onRecognitionResult?.invoke(VoiceInteractionResult(true, text))
            }

            override fun onPartialResult(text: String) {
                Logger.d("Partial result: $text", TAG)
                _lastRecognizedText.value = text
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                Logger.e("Recognition error: $errorMessage", TAG)
                _state.value = VoiceInteractionState.ERROR
                onRecognitionResult?.invoke(VoiceInteractionResult(false, errorMessage = errorMessage))
                
                // 恢复空闲状态
                _state.value = VoiceInteractionState.IDLE
            }

            override fun onStateChanged(state: RecognitionState) {
                _state.value = when (state) {
                    RecognitionState.IDLE -> VoiceInteractionState.IDLE
                    RecognitionState.LISTENING -> VoiceInteractionState.LISTENING
                    RecognitionState.PROCESSING -> VoiceInteractionState.RECOGNIZING
                    RecognitionState.SUCCESS -> VoiceInteractionState.IDLE
                    RecognitionState.ERROR -> VoiceInteractionState.ERROR
                }
            }
        }
    }

    /**
     * 创建TTS回调
     */
    private fun createTtsCallback(): TtsCallback {
        return object : TtsCallback {
            override fun onStart(utteranceId: String) {
                _state.value = VoiceInteractionState.SPEAKING
            }

            override fun onDone(utteranceId: String) {
                _state.value = VoiceInteractionState.IDLE
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                _state.value = VoiceInteractionState.ERROR
            }
        }
    }

    /**
     * 开始语音识别
     */
    fun startListening() {
        if (!isInitialized) {
            Logger.w("VoiceManager not initialized, initializing now", TAG)
            initialize {
                if (it) startListening()
            }
            return
        }
        
        // 如果正在播报，先停止
        if (tts.isSpeaking()) {
            tts.stop()
        }
        
        _lastRecognizedText.value = ""
        _state.value = VoiceInteractionState.LISTENING
        recognizer.startListening()
    }

    /**
     * 停止语音识别
     */
    fun stopListening() {
        recognizer.stopListening()
    }

    /**
     * 取消语音识别
     */
    fun cancelListening() {
        recognizer.cancel()
        _state.value = VoiceInteractionState.IDLE
    }

    /**
     * 播报文本
     * @param text 要播报的文本
     * @param onComplete 播报完成回调
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized) {
            Logger.w("VoiceManager not initialized", TAG)
            onComplete?.invoke()
            return
        }
        
        // 如果正在识别，先取消
        if (recognizer.isListening) {
            recognizer.cancel()
        }
        
        _state.value = VoiceInteractionState.SPEAKING
        
        // 设置完成回调
        val originalCallback = tts.callback
        tts.callback = object : TtsCallback {
            override fun onStart(utteranceId: String) {
                originalCallback?.onStart(utteranceId)
            }

            override fun onDone(utteranceId: String) {
                originalCallback?.onDone(utteranceId)
                tts.callback = originalCallback
                onComplete?.invoke()
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                originalCallback?.onError(utteranceId, errorCode)
                tts.callback = originalCallback
                onComplete?.invoke()
            }
        }
        
        tts.speak(text)
    }

    /**
     * 停止播报
     */
    fun stopSpeaking() {
        tts.stop()
        _state.value = VoiceInteractionState.IDLE
    }

    /**
     * 检查是否正在聆听
     */
    fun isListening(): Boolean {
        return recognizer.isListening
    }

    /**
     * 检查是否正在播报
     */
    fun isSpeaking(): Boolean {
        return tts.isSpeaking()
    }

    /**
     * 设置语速
     */
    fun setSpeechRate(rate: Float) {
        tts.speechRate = rate
    }

    /**
     * 设置音调
     */
    fun setPitch(pitch: Float) {
        tts.pitch = pitch
    }

    /**
     * 检查语音识别是否可用
     */
    fun isRecognitionAvailable(): Boolean {
        return VoiceRecognizer.isRecognitionAvailable(context)
    }

    /**
     * 释放资源
     */
    fun release() {
        recognizer.release()
        tts.release()
        isInitialized = false
        Logger.i("VoiceManager released", TAG)
    }
}

