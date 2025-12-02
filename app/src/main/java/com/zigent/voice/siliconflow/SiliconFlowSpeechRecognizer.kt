package com.zigent.voice.siliconflow

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.zigent.utils.Logger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 识别状态
 */
enum class SiliconFlowRecognitionState {
    IDLE,
    RECORDING,
    UPLOADING,
    SUCCESS,
    ERROR
}

/**
 * 识别回调
 */
interface SiliconFlowRecognitionCallback {
    fun onStateChanged(state: SiliconFlowRecognitionState)
    fun onResult(text: String)
    fun onError(message: String)
    fun onRecordingProgress(seconds: Int)
}

/**
 * 硅基流动语音识别器
 * 使用 TeleAI/TeleSpeechASR 模型
 * API文档: https://docs.siliconflow.cn/cn/api-reference/audio/create-audio-transcriptions
 */
class SiliconFlowSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "SiliconFlowRecognizer"
        
        // API 配置
        private const val API_URL = "https://api.siliconflow.cn/v1/audio/transcriptions"
        private const val MODEL = "TeleAI/TeleSpeechASR"
        
        // 录音配置
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // 最小录音时长（毫秒）
        private const val MIN_RECORDING_DURATION_MS = 500
    }

    // API Key (从 AI 设置中获取)
    var apiKey: String = ""
    
    // 回调
    var callback: SiliconFlowRecognitionCallback? = null
    
    // 状态
    @Volatile
    private var _state = SiliconFlowRecognitionState.IDLE
    val state: SiliconFlowRecognitionState get() = _state
    val isRecording: Boolean get() = _state == SiliconFlowRecognitionState.RECORDING
    
    // 录音相关
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var recordingFile: File? = null
    
    // 停止录音标志（使用原子变量确保线程安全）
    private val shouldStopRecording = AtomicBoolean(false)
    
    // 录音开始时间
    private var recordingStartTime = 0L
    
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // OkHttp 客户端
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 开始录音
     */
    @SuppressLint("MissingPermission")
    fun startRecording() {
        // 防止重复录音
        if (_state == SiliconFlowRecognitionState.RECORDING || 
            _state == SiliconFlowRecognitionState.UPLOADING) {
            Logger.w("Already recording or uploading, state: $_state", TAG)
            return
        }
        
        if (apiKey.isBlank()) {
            Logger.e("API key not configured", TAG)
            notifyError("请先配置 AI API Key")
            return
        }
        
        Logger.i("=== Starting recording ===", TAG)
        
        // 先清理之前的资源
        cleanup()
        
        try {
            // 重置停止标志
            shouldStopRecording.set(false)
            
            // 创建录音文件
            recordingFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
            
            // 初始化录音器
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Logger.e("Invalid buffer size: $bufferSize", TAG)
                notifyError("录音配置错误")
                return
            }
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e("AudioRecord initialization failed", TAG)
                notifyError("录音初始化失败，请检查麦克风权限")
                cleanup()
                return
            }
            
            // 记录开始时间
            recordingStartTime = System.currentTimeMillis()
            
            // 先更新状态
            _state = SiliconFlowRecognitionState.RECORDING
            notifyStateChanged(_state)
            
            // 开始录音
            try {
                audioRecord?.startRecording()
            } catch (e: IllegalStateException) {
                Logger.e("Failed to start AudioRecord", e, TAG)
                notifyError("录音启动失败")
                cleanup()
                return
            }
            
            // 启动录音协程
            recordingJob = scope.launch {
                try {
                    recordAudioLoop()
                } catch (e: CancellationException) {
                    Logger.d("Recording cancelled", TAG)
                } catch (e: Exception) {
                    Logger.e("Recording loop error", e, TAG)
                    notifyError("录音出错: ${e.message}")
                }
            }
            
            Logger.i("Recording started", TAG)
            
        } catch (e: SecurityException) {
            Logger.e("Missing RECORD_AUDIO permission", e, TAG)
            notifyError("缺少录音权限")
            cleanup()
        } catch (e: Exception) {
            Logger.e("Failed to start recording", e, TAG)
            notifyError("录音启动失败: ${e.message}")
            cleanup()
        }
    }

    /**
     * 停止录音并识别
     */
    fun stopRecording() {
        if (_state != SiliconFlowRecognitionState.RECORDING) {
            Logger.w("Not recording, current state: $_state", TAG)
            return
        }
        
        val recordingDuration = System.currentTimeMillis() - recordingStartTime
        Logger.i("=== Stopping recording (duration: ${recordingDuration}ms) ===", TAG)
        
        // 设置停止标志，让录音循环自然结束
        shouldStopRecording.set(true)
        
        // 检查是否有足够的录音
        if (recordingDuration < MIN_RECORDING_DURATION_MS) {
            Logger.w("Recording too short: ${recordingDuration}ms", TAG)
            notifyError("录音时间太短，请至少说0.5秒")
            cancel()
            return
        }
        
        // 等待录音协程完成并开始识别
        scope.launch {
            try {
                // 等待录音协程完成（最多等待2秒）
                withTimeoutOrNull(2000) {
                    recordingJob?.join()
                }
                
                // 停止录音器
                try {
                    audioRecord?.stop()
                } catch (e: Exception) {
                    Logger.w("Error stopping AudioRecord", e, TAG)
                }
                
                // 检查文件
                val file = recordingFile
                if (file == null || !file.exists() || file.length() < 1000) {
                    Logger.e("Recording file invalid: ${file?.length()} bytes", TAG)
                    notifyError("录音文件无效")
                    cleanup()
                    return@launch
                }
                
                Logger.i("Recording file ready: ${file.length()} bytes", TAG)
                
                // 开始识别
                _state = SiliconFlowRecognitionState.UPLOADING
                notifyStateChanged(_state)
                
                recognizeAudio()
                
            } catch (e: Exception) {
                Logger.e("Error in stop recording", e, TAG)
                notifyError("停止录音失败: ${e.message}")
                cleanup()
            }
        }
    }

    /**
     * 取消录音
     */
    fun cancel() {
        Logger.i("Cancelling recording", TAG)
        shouldStopRecording.set(true)
        recordingJob?.cancel()
        cleanup()
    }

    /**
     * 录音循环
     */
    private suspend fun recordAudioLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val buffer = ByteArray(bufferSize)
        val audioData = mutableListOf<Byte>()
        
        var lastProgressUpdate = 0
        
        Logger.d("Starting recording loop, buffer size: $bufferSize", TAG)
        
        while (!shouldStopRecording.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            
            if (readSize > 0) {
                // 添加到数据列表
                for (i in 0 until readSize) {
                    audioData.add(buffer[i])
                }
                
                // 计算录音时长并通知进度
                val seconds = audioData.size / (SAMPLE_RATE * 2) // 16bit = 2 bytes
                if (seconds > lastProgressUpdate) {
                    lastProgressUpdate = seconds
                    withContext(Dispatchers.Main) {
                        callback?.onRecordingProgress(seconds)
                    }
                    Logger.d("Recording progress: ${seconds}s, data size: ${audioData.size}", TAG)
                }
            } else if (readSize < 0) {
                Logger.w("AudioRecord read error: $readSize", TAG)
                break
            }
            
            // 短暂延迟，避免过度占用CPU
            delay(10)
        }
        
        Logger.i("Recording loop ended, total data: ${audioData.size} bytes", TAG)
        
        // 保存为 WAV 文件
        if (audioData.isNotEmpty()) {
            saveWavFile(audioData.toByteArray())
        } else {
            Logger.w("No audio data recorded", TAG)
        }
    }

    /**
     * 保存为 WAV 文件
     */
    private fun saveWavFile(audioData: ByteArray) {
        try {
            val file = recordingFile ?: return
            val totalDataLen = audioData.size + 36
            val totalAudioLen = audioData.size.toLong()
            val channels = 1
            val byteRate = SAMPLE_RATE * channels * 16 / 8
            
            FileOutputStream(file).use { out ->
                // WAV 文件头 (44 bytes)
                val header = ByteArray(44)
                
                // RIFF chunk descriptor
                header[0] = 'R'.code.toByte()
                header[1] = 'I'.code.toByte()
                header[2] = 'F'.code.toByte()
                header[3] = 'F'.code.toByte()
                writeInt(header, 4, totalDataLen)
                header[8] = 'W'.code.toByte()
                header[9] = 'A'.code.toByte()
                header[10] = 'V'.code.toByte()
                header[11] = 'E'.code.toByte()
                
                // fmt sub-chunk
                header[12] = 'f'.code.toByte()
                header[13] = 'm'.code.toByte()
                header[14] = 't'.code.toByte()
                header[15] = ' '.code.toByte()
                writeInt(header, 16, 16) // Subchunk1Size (16 for PCM)
                writeShort(header, 20, 1) // AudioFormat (1 = PCM)
                writeShort(header, 22, channels) // NumChannels
                writeInt(header, 24, SAMPLE_RATE) // SampleRate
                writeInt(header, 28, byteRate) // ByteRate
                writeShort(header, 32, channels * 16 / 8) // BlockAlign
                writeShort(header, 34, 16) // BitsPerSample
                
                // data sub-chunk
                header[36] = 'd'.code.toByte()
                header[37] = 'a'.code.toByte()
                header[38] = 't'.code.toByte()
                header[39] = 'a'.code.toByte()
                writeInt(header, 40, audioData.size)
                
                out.write(header)
                out.write(audioData)
            }
            
            Logger.i("WAV file saved: ${file.absolutePath}, size: ${file.length()}", TAG)
            
        } catch (e: Exception) {
            Logger.e("Failed to save WAV file", e, TAG)
        }
    }
    
    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xff).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xff).toByte()
    }
    
    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    /**
     * 调用 API 识别音频
     */
    private suspend fun recognizeAudio() {
        val file = recordingFile
        if (file == null || !file.exists()) {
            Logger.e("Recording file not found", TAG)
            notifyError("录音文件不存在")
            cleanup()
            return
        }
        
        Logger.i("=== Uploading audio for recognition ===", TAG)
        Logger.d("File: ${file.absolutePath}, size: ${file.length()} bytes", TAG)
        
        try {
            // 构建 multipart 请求
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", MODEL)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody("audio/wav".toMediaType())
                )
                .build()
            
            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
            
            Logger.d("Sending request to $API_URL with model $MODEL", TAG)
            
            // 执行请求
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            Logger.d("Response code: ${response.code}", TAG)
            Logger.d("Response body: $responseBody", TAG)
            
            if (response.isSuccessful && responseBody != null) {
                try {
                    val json = JSONObject(responseBody)
                    val text = json.optString("text", "").trim()
                    
                    if (text.isNotBlank()) {
                        Logger.i("=== Recognition SUCCESS: '$text' ===", TAG)
                        _state = SiliconFlowRecognitionState.SUCCESS
                        notifyStateChanged(_state)
                        withContext(Dispatchers.Main) {
                            callback?.onResult(text)
                        }
                    } else {
                        Logger.w("Empty recognition result", TAG)
                        _state = SiliconFlowRecognitionState.ERROR
                        notifyStateChanged(_state)
                        notifyError("没有检测到语音内容，请重试")
                    }
                } catch (e: Exception) {
                    Logger.e("Failed to parse response", e, TAG)
                    notifyError("解析响应失败")
                }
            } else {
                val errorMsg = when (response.code) {
                    400 -> "请求格式错误"
                    401 -> "API Key 无效"
                    403 -> "API 访问被拒绝"
                    429 -> "请求太频繁，请稍后重试"
                    500, 502, 503 -> "服务器暂时不可用"
                    else -> "识别失败 (${response.code})"
                }
                Logger.e("API error: ${response.code} - $responseBody", TAG)
                _state = SiliconFlowRecognitionState.ERROR
                notifyStateChanged(_state)
                notifyError(errorMsg)
            }
            
        } catch (e: Exception) {
            Logger.e("Recognition request failed", e, TAG)
            _state = SiliconFlowRecognitionState.ERROR
            notifyStateChanged(_state)
            notifyError("网络请求失败: ${e.message}")
        } finally {
            cleanup()
        }
    }
    
    /**
     * 通知状态变化（确保在主线程）
     */
    private fun notifyStateChanged(state: SiliconFlowRecognitionState) {
        scope.launch(Dispatchers.Main) {
            callback?.onStateChanged(state)
        }
    }
    
    /**
     * 通知错误（确保在主线程）
     */
    private fun notifyError(message: String) {
        scope.launch(Dispatchers.Main) {
            callback?.onError(message)
        }
    }

    /**
     * 清理资源
     */
    private fun cleanup() {
        Logger.d("Cleaning up resources", TAG)
        
        // 先取消录音任务
        try {
            recordingJob?.cancel()
        } catch (e: Exception) {
            Logger.w("Error cancelling recording job", e, TAG)
        }
        recordingJob = null
        
        // 安全释放 AudioRecord
        try {
            val record = audioRecord
            if (record != null) {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Logger.w("Error releasing AudioRecord", e, TAG)
        }
        audioRecord = null
        
        // 删除临时文件
        try {
            recordingFile?.delete()
        } catch (e: Exception) {
            Logger.w("Error deleting recording file", e, TAG)
        }
        recordingFile = null
        
        _state = SiliconFlowRecognitionState.IDLE
        notifyStateChanged(_state)
    }

    /**
     * 释放所有资源
     */
    fun release() {
        Logger.i("Releasing SiliconFlowSpeechRecognizer", TAG)
        cancel()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
