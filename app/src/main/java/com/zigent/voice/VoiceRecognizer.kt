package com.zigent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.zigent.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音识别状态
 */
enum class RecognitionState {
    IDLE,           // 空闲
    LISTENING,      // 正在听
    PROCESSING,     // 处理中
    SUCCESS,        // 识别成功
    ERROR           // 识别失败
}

/**
 * 语音识别结果回调
 */
interface VoiceRecognitionCallback {
    fun onResult(text: String)
    fun onPartialResult(text: String)
    fun onError(errorCode: Int, errorMessage: String)
    fun onStateChanged(state: RecognitionState)
}

/**
 * 语音识别管理器
 * 使用Android原生SpeechRecognizer进行语音识别
 */
class VoiceRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecognizer"
        
        /**
         * 检查设备是否支持语音识别
         */
        fun isRecognitionAvailable(context: Context): Boolean {
            return SpeechRecognizer.isRecognitionAvailable(context)
        }

        /**
         * 获取错误描述
         */
        fun getErrorMessage(errorCode: Int): String {
            return when (errorCode) {
                SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_NO_MATCH -> "无法识别"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
                SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                else -> "未知错误 ($errorCode)"
            }
        }
    }

    // 语音识别器
    private var speechRecognizer: SpeechRecognizer? = null
    
    // 识别状态
    private val _state = MutableStateFlow(RecognitionState.IDLE)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()
    
    // 回调
    var callback: VoiceRecognitionCallback? = null
    
    // 是否正在识别
    val isListening: Boolean
        get() = _state.value == RecognitionState.LISTENING

    /**
     * 初始化语音识别器
     */
    fun initialize() {
        if (speechRecognizer != null) {
            Logger.w("SpeechRecognizer already initialized", TAG)
            return
        }
        
        if (!isRecognitionAvailable(context)) {
            Logger.e("Speech recognition not available", TAG)
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createRecognitionListener())
        }
        
        Logger.i("VoiceRecognizer initialized", TAG)
    }

    /**
     * 创建识别监听器
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Logger.i("=== Ready for speech ===", TAG)
                updateState(RecognitionState.LISTENING)
            }

            override fun onBeginningOfSpeech() {
                Logger.i("Beginning of speech detected", TAG)
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 音量变化，可用于显示波形
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // 收到音频数据
            }

            override fun onEndOfSpeech() {
                Logger.i("End of speech detected", TAG)
                updateState(RecognitionState.PROCESSING)
            }

            override fun onError(error: Int) {
                val errorMessage = getErrorMessage(error)
                Logger.e("=== Recognition error: $error - $errorMessage ===", TAG)
                updateState(RecognitionState.ERROR)
                
                // 对于 NO_MATCH 错误，不视为严重错误
                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    Logger.w("No speech detected, but this is not a fatal error", TAG)
                }
                
                callback?.onError(error, errorMessage)
                
                // 某些错误后恢复空闲状态
                if (error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    updateState(RecognitionState.IDLE)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                
                Logger.i("=== Final recognition result: '$text' ===", TAG)
                if (matches != null && matches.size > 1) {
                    Logger.d("Alternative results: ${matches.drop(1)}", TAG)
                }
                
                updateState(RecognitionState.SUCCESS)
                callback?.onResult(text)
                
                // 恢复空闲状态
                updateState(RecognitionState.IDLE)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                
                if (text.isNotEmpty()) {
                    Logger.i("Partial result: '$text'", TAG)
                    callback?.onPartialResult(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Logger.d("Recognition event: $eventType", TAG)
            }
        }
    }

    /**
     * 开始语音识别
     * @param language 识别语言，默认中文
     */
    fun startListening(language: String = "zh-CN") {
        Logger.i("=== VoiceRecognizer.startListening() ===", TAG)
        
        if (speechRecognizer == null) {
            Logger.w("SpeechRecognizer not initialized, initializing now", TAG)
            initialize()
        }
        
        if (isListening) {
            Logger.w("Already listening, ignoring", TAG)
            return
        }
        
        // 检查语音识别是否可用
        if (!isRecognitionAvailable(context)) {
            Logger.e("Speech recognition not available on this device!", TAG)
            callback?.onError(-1, "此设备不支持语音识别，请安装Google应用")
            return
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // 延长静音检测时间，让用户有更多时间说话
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        
        try {
            speechRecognizer?.startListening(intent)
            Logger.i("=== Native STT started listening ===", TAG)
        } catch (e: Exception) {
            Logger.e("Failed to start listening", e, TAG)
            updateState(RecognitionState.ERROR)
            callback?.onError(-1, "启动识别失败: ${e.message}")
        }
    }

    /**
     * 停止语音识别
     */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            Logger.i("Stopped listening", TAG)
        } catch (e: Exception) {
            Logger.e("Failed to stop listening", e, TAG)
        }
    }

    /**
     * 取消语音识别
     */
    fun cancel() {
        try {
            speechRecognizer?.cancel()
            updateState(RecognitionState.IDLE)
            Logger.i("Recognition cancelled", TAG)
        } catch (e: Exception) {
            Logger.e("Failed to cancel recognition", e, TAG)
        }
    }

    /**
     * 更新状态
     */
    private fun updateState(newState: RecognitionState) {
        _state.value = newState
        callback?.onStateChanged(newState)
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            Logger.i("VoiceRecognizer released", TAG)
        } catch (e: Exception) {
            Logger.e("Failed to release VoiceRecognizer", e, TAG)
        }
    }
}

