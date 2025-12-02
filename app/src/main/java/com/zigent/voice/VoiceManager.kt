package com.zigent.voice

import android.content.Context
import com.zigent.utils.Logger
import com.zigent.voice.siliconflow.SiliconFlowRecognitionCallback
import com.zigent.voice.siliconflow.SiliconFlowRecognitionState
import com.zigent.voice.siliconflow.SiliconFlowSpeechRecognizer
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
    val errorMessage: String = "",
    val isPartial: Boolean = false
)

/**
 * 语音识别服务类型
 */
enum class SpeechRecognizerType {
    ANDROID_NATIVE, // Android原生
    SILICONFLOW     // 硅基流动
}

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

    // 语音识别服务类型（默认使用硅基流动）
    var recognizerType: SpeechRecognizerType = SpeechRecognizerType.SILICONFLOW
    
    // 硅基流动 API Key (从 AI 设置中获取)
    private var siliconFlowApiKey: String = ""
    
    // Android原生语音识别器
    private val nativeRecognizer = VoiceRecognizer(context)
    
    // 硅基流动语音识别器
    private val siliconFlowRecognizer = SiliconFlowSpeechRecognizer(context)
    
    // 语音合成器
    private val tts = TextToSpeechManager(context)
    
    // 交互状态
    private val _state = MutableStateFlow(VoiceInteractionState.IDLE)
    val state: StateFlow<VoiceInteractionState> = _state.asStateFlow()
    
    // 最后识别的文本
    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()
    
    // 录音时长
    private val _recordingDuration = MutableStateFlow(0)
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()
    
    // 识别结果回调
    var onRecognitionResult: ((VoiceInteractionResult) -> Unit)? = null
    
    // 是否已初始化
    private var isInitialized = false

    /**
     * 配置硅基流动 API Key
     */
    fun configureSiliconFlow(apiKey: String) {
        Logger.i("Configuring SiliconFlow API Key: ${apiKey.take(10)}...", TAG)
        siliconFlowApiKey = apiKey
        siliconFlowRecognizer.apiKey = apiKey
    }

    /**
     * 初始化语音服务
     */
    fun initialize(onComplete: ((Boolean) -> Unit)? = null) {
        if (isInitialized) {
            Logger.d("VoiceManager already initialized", TAG)
            onComplete?.invoke(true)
            return
        }
        
        Logger.i("Initializing VoiceManager with $recognizerType", TAG)
        
        // 初始化所有识别器的回调
        siliconFlowRecognizer.callback = createSiliconFlowCallback()
        nativeRecognizer.callback = createRecognitionCallback()
        
        when (recognizerType) {
            SpeechRecognizerType.SILICONFLOW -> {
                Logger.i("SiliconFlow recognizer configured", TAG)
            }
            SpeechRecognizerType.ANDROID_NATIVE -> {
                nativeRecognizer.initialize()
                Logger.i("Native recognizer initialized", TAG)
            }
        }
        
        // 初始化TTS
        tts.initialize { success ->
            if (success) {
                tts.callback = createTtsCallback()
                Logger.i("TTS initialized successfully", TAG)
            } else {
                Logger.w("TTS initialization failed, but recognition should still work", TAG)
            }
            isInitialized = true
            Logger.i("VoiceManager initialized (TTS: $success)", TAG)
            onComplete?.invoke(true)
        }
    }

    /**
     * 创建硅基流动语音识别回调
     */
    private fun createSiliconFlowCallback(): SiliconFlowRecognitionCallback {
        return object : SiliconFlowRecognitionCallback {
            override fun onStateChanged(state: SiliconFlowRecognitionState) {
                val newState = when (state) {
                    SiliconFlowRecognitionState.IDLE -> VoiceInteractionState.IDLE
                    SiliconFlowRecognitionState.RECORDING -> VoiceInteractionState.LISTENING
                    SiliconFlowRecognitionState.UPLOADING -> VoiceInteractionState.RECOGNIZING
                    SiliconFlowRecognitionState.SUCCESS -> VoiceInteractionState.IDLE
                    SiliconFlowRecognitionState.ERROR -> VoiceInteractionState.ERROR
                }
                Logger.d("SiliconFlow state: $state -> $newState", TAG)
                _state.value = newState
            }

            override fun onResult(text: String) {
                Logger.i("=== SiliconFlow result: '$text' ===", TAG)
                // 先更新文本，再更新状态，确保文本可用
                if (text.isNotBlank()) {
                    _lastRecognizedText.value = text
                    Logger.i("Updated _lastRecognizedText to: '$text'", TAG)
                    // 立即通知回调
                    onRecognitionResult?.invoke(VoiceInteractionResult(true, text))
                } else {
                    Logger.w("SiliconFlow returned empty text", TAG)
                    onRecognitionResult?.invoke(VoiceInteractionResult(false, errorMessage = "识别结果为空"))
                }
                _state.value = VoiceInteractionState.IDLE
            }

            override fun onError(message: String) {
                Logger.e("SiliconFlow error: $message", null, TAG)
                _state.value = VoiceInteractionState.ERROR
                onRecognitionResult?.invoke(VoiceInteractionResult(false, errorMessage = message))
                // 延迟重置状态，防止状态快速切换
                _state.value = VoiceInteractionState.IDLE
            }

            override fun onRecordingProgress(seconds: Int) {
                _recordingDuration.value = seconds
            }
        }
    }

    /**
     * 创建Android原生语音识别回调
     */
    private fun createRecognitionCallback(): VoiceRecognitionCallback {
        return object : VoiceRecognitionCallback {
            override fun onResult(text: String) {
                Logger.i("=== Native final result: '$text' ===", TAG)
                _lastRecognizedText.value = text
                _state.value = VoiceInteractionState.IDLE
                if (text.isNotBlank()) {
                    onRecognitionResult?.invoke(VoiceInteractionResult(true, text))
                }
            }

            override fun onPartialResult(text: String) {
                Logger.i("Native partial result: '$text'", TAG)
                _lastRecognizedText.value = text
                if (text.isNotBlank()) {
                    onRecognitionResult?.invoke(VoiceInteractionResult(true, text, isPartial = true))
                }
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                Logger.e("Native recognition error: $errorCode - $errorMessage", null, TAG)
                _state.value = VoiceInteractionState.ERROR
                _state.value = VoiceInteractionState.IDLE
            }

            override fun onStateChanged(state: RecognitionState) {
                val newState = when (state) {
                    RecognitionState.IDLE -> VoiceInteractionState.IDLE
                    RecognitionState.LISTENING -> VoiceInteractionState.LISTENING
                    RecognitionState.PROCESSING -> VoiceInteractionState.RECOGNIZING
                    RecognitionState.SUCCESS -> VoiceInteractionState.IDLE
                    RecognitionState.ERROR -> VoiceInteractionState.ERROR
                }
                Logger.d("Native state changed: $state -> $newState", TAG)
                _state.value = newState
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
        Logger.i("startListening called, isInitialized=$isInitialized, recognizerType=$recognizerType", TAG)
        
        if (!isInitialized) {
            Logger.w("VoiceManager not initialized, initializing now", TAG)
            initialize {
                Logger.i("VoiceManager initialization complete, starting listening", TAG)
                startListening()
            }
            return
        }
        
        // 如果正在播报，先停止
        if (tts.isSpeaking()) {
            Logger.d("Stopping TTS before listening", TAG)
            tts.stop()
        }
        
        _lastRecognizedText.value = ""
        _recordingDuration.value = 0
        _state.value = VoiceInteractionState.LISTENING
        
        when (recognizerType) {
            SpeechRecognizerType.SILICONFLOW -> {
                Logger.i("=== Starting SiliconFlow recording ===", TAG)
                if (siliconFlowApiKey.isBlank()) {
                    Logger.e("SiliconFlow API key not configured", TAG)
                    onRecognitionResult?.invoke(VoiceInteractionResult(false, errorMessage = "请先配置 AI API Key"))
                    _state.value = VoiceInteractionState.IDLE
                    return
                }
                siliconFlowRecognizer.startRecording()
            }
            SpeechRecognizerType.ANDROID_NATIVE -> {
                Logger.i("=== Starting native recognition ===", TAG)
                nativeRecognizer.startListening()
            }
        }
    }

    /**
     * 停止语音识别
     */
    fun stopListening() {
        Logger.i("stopListening called, recognizerType=$recognizerType", TAG)
        when (recognizerType) {
            SpeechRecognizerType.SILICONFLOW -> {
                Logger.i("Stopping SiliconFlow recording", TAG)
                siliconFlowRecognizer.stopRecording()
            }
            SpeechRecognizerType.ANDROID_NATIVE -> nativeRecognizer.stopListening()
        }
    }

    /**
     * 取消语音识别
     */
    fun cancelListening() {
        Logger.i("cancelListening called", TAG)
        when (recognizerType) {
            SpeechRecognizerType.SILICONFLOW -> siliconFlowRecognizer.cancel()
            SpeechRecognizerType.ANDROID_NATIVE -> nativeRecognizer.cancel()
        }
        _state.value = VoiceInteractionState.IDLE
    }

    /**
     * 播报文本
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized) {
            Logger.w("VoiceManager not initialized", TAG)
            onComplete?.invoke()
            return
        }
        
        if (isListening()) {
            cancelListening()
        }
        
        _state.value = VoiceInteractionState.SPEAKING
        
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
        return when (recognizerType) {
            SpeechRecognizerType.SILICONFLOW -> siliconFlowRecognizer.isRecording
            SpeechRecognizerType.ANDROID_NATIVE -> nativeRecognizer.isListening
        }
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
        return when (recognizerType) {
            SpeechRecognizerType.SILICONFLOW -> siliconFlowApiKey.isNotBlank()
            SpeechRecognizerType.ANDROID_NATIVE -> VoiceRecognizer.isRecognitionAvailable(context)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        nativeRecognizer.release()
        siliconFlowRecognizer.release()
        tts.release()
        isInitialized = false
        Logger.i("VoiceManager released", TAG)
    }
}
