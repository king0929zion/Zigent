package com.zigent.voice.siliconflow

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
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

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
    }

    // API Key (从 AI 设置中获取)
    var apiKey: String = ""
    
    // 回调
    var callback: SiliconFlowRecognitionCallback? = null
    
    // 状态
    private var _state = SiliconFlowRecognitionState.IDLE
    val state: SiliconFlowRecognitionState get() = _state
    val isRecording: Boolean get() = _state == SiliconFlowRecognitionState.RECORDING
    
    // 录音相关
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var recordingFile: File? = null
    
    // 录音时长（秒）
    private var recordingDuration = 0
    
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
    fun startRecording() {
        if (_state == SiliconFlowRecognitionState.RECORDING) {
            Logger.w("Already recording", TAG)
            return
        }
        
        if (apiKey.isBlank()) {
            Logger.e("API key not configured", TAG)
            callback?.onError("请先配置 AI API Key")
            return
        }
        
        Logger.i("=== Starting recording ===", TAG)
        
        try {
            // 创建录音文件
            recordingFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
            
            // 初始化录音器
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e("AudioRecord initialization failed", TAG)
                callback?.onError("录音初始化失败")
                return
            }
            
            // 开始录音
            audioRecord?.startRecording()
            _state = SiliconFlowRecognitionState.RECORDING
            callback?.onStateChanged(_state)
            recordingDuration = 0
            
            // 启动录音协程
            recordingJob = scope.launch {
                recordAudio()
            }
            
            Logger.i("Recording started", TAG)
            
        } catch (e: Exception) {
            Logger.e("Failed to start recording", e, TAG)
            callback?.onError("录音启动失败: ${e.message}")
            cleanup()
        }
    }

    /**
     * 停止录音并识别
     */
    fun stopRecording() {
        if (_state != SiliconFlowRecognitionState.RECORDING) {
            Logger.w("Not recording", TAG)
            return
        }
        
        Logger.i("=== Stopping recording ===", TAG)
        
        // 停止录音
        recordingJob?.cancel()
        audioRecord?.stop()
        
        // 检查是否有足够的录音
        if (recordingDuration < 1) {
            Logger.w("Recording too short: ${recordingDuration}s", TAG)
            callback?.onError("录音时间太短，请至少说1秒")
            cleanup()
            return
        }
        
        // 开始识别
        _state = SiliconFlowRecognitionState.UPLOADING
        callback?.onStateChanged(_state)
        
        scope.launch {
            recognizeAudio()
        }
    }

    /**
     * 取消录音
     */
    fun cancel() {
        Logger.i("Cancelling recording", TAG)
        recordingJob?.cancel()
        cleanup()
    }

    /**
     * 录音过程
     */
    private suspend fun recordAudio() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val buffer = ByteArray(bufferSize)
        val audioData = mutableListOf<Byte>()
        
        var lastProgressUpdate = 0
        
        try {
            while (coroutineContext.isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readSize > 0) {
                    audioData.addAll(buffer.take(readSize))
                    
                    // 计算录音时长
                    val seconds = audioData.size / (SAMPLE_RATE * 2) // 16bit = 2 bytes
                    if (seconds > lastProgressUpdate) {
                        lastProgressUpdate = seconds
                        recordingDuration = seconds
                        withContext(Dispatchers.Main) {
                            callback?.onRecordingProgress(seconds)
                        }
                    }
                }
                delay(50) // 避免过度占用 CPU
            }
        } catch (e: CancellationException) {
            Logger.d("Recording cancelled", TAG)
        }
        
        // 保存为 WAV 文件
        if (audioData.isNotEmpty()) {
            saveWavFile(audioData.toByteArray())
            Logger.i("Recording saved: ${recordingFile?.length()} bytes, ${recordingDuration}s", TAG)
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
                // WAV 文件头
                val header = ByteArray(44)
                
                // RIFF chunk
                header[0] = 'R'.code.toByte()
                header[1] = 'I'.code.toByte()
                header[2] = 'F'.code.toByte()
                header[3] = 'F'.code.toByte()
                header[4] = (totalDataLen and 0xff).toByte()
                header[5] = ((totalDataLen shr 8) and 0xff).toByte()
                header[6] = ((totalDataLen shr 16) and 0xff).toByte()
                header[7] = ((totalDataLen shr 24) and 0xff).toByte()
                header[8] = 'W'.code.toByte()
                header[9] = 'A'.code.toByte()
                header[10] = 'V'.code.toByte()
                header[11] = 'E'.code.toByte()
                
                // fmt chunk
                header[12] = 'f'.code.toByte()
                header[13] = 'm'.code.toByte()
                header[14] = 't'.code.toByte()
                header[15] = ' '.code.toByte()
                header[16] = 16 // Subchunk1Size
                header[17] = 0
                header[18] = 0
                header[19] = 0
                header[20] = 1 // AudioFormat (PCM)
                header[21] = 0
                header[22] = channels.toByte() // NumChannels
                header[23] = 0
                header[24] = (SAMPLE_RATE and 0xff).toByte()
                header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
                header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
                header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
                header[28] = (byteRate and 0xff).toByte()
                header[29] = ((byteRate shr 8) and 0xff).toByte()
                header[30] = ((byteRate shr 16) and 0xff).toByte()
                header[31] = ((byteRate shr 24) and 0xff).toByte()
                header[32] = (channels * 16 / 8).toByte() // BlockAlign
                header[33] = 0
                header[34] = 16 // BitsPerSample
                header[35] = 0
                
                // data chunk
                header[36] = 'd'.code.toByte()
                header[37] = 'a'.code.toByte()
                header[38] = 't'.code.toByte()
                header[39] = 'a'.code.toByte()
                header[40] = (totalAudioLen and 0xff).toByte()
                header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
                header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
                header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
                
                out.write(header)
                out.write(audioData)
            }
        } catch (e: Exception) {
            Logger.e("Failed to save WAV file", e, TAG)
        }
    }

    /**
     * 调用 API 识别音频
     */
    private suspend fun recognizeAudio() {
        val file = recordingFile
        if (file == null || !file.exists()) {
            Logger.e("Recording file not found", TAG)
            withContext(Dispatchers.Main) {
                callback?.onError("录音文件不存在")
            }
            cleanup()
            return
        }
        
        Logger.i("=== Uploading audio for recognition ===", TAG)
        Logger.d("File size: ${file.length()} bytes", TAG)
        
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
            
            Logger.d("Sending request to $API_URL", TAG)
            
            // 执行请求
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            Logger.d("Response code: ${response.code}", TAG)
            Logger.d("Response: $responseBody", TAG)
            
            if (response.isSuccessful && responseBody != null) {
                val json = JSONObject(responseBody)
                val text = json.optString("text", "")
                
                if (text.isNotBlank()) {
                    Logger.i("=== Recognition result: $text ===", TAG)
                    _state = SiliconFlowRecognitionState.SUCCESS
                    withContext(Dispatchers.Main) {
                        callback?.onStateChanged(_state)
                        callback?.onResult(text.trim())
                    }
                } else {
                    Logger.w("Empty recognition result", TAG)
                    _state = SiliconFlowRecognitionState.ERROR
                    withContext(Dispatchers.Main) {
                        callback?.onStateChanged(_state)
                        callback?.onError("没有检测到语音内容")
                    }
                }
            } else {
                val errorMsg = "API 请求失败: ${response.code} - $responseBody"
                Logger.e(errorMsg, TAG)
                _state = SiliconFlowRecognitionState.ERROR
                withContext(Dispatchers.Main) {
                    callback?.onStateChanged(_state)
                    callback?.onError("识别失败: ${response.code}")
                }
            }
            
        } catch (e: Exception) {
            Logger.e("Recognition failed", e, TAG)
            _state = SiliconFlowRecognitionState.ERROR
            withContext(Dispatchers.Main) {
                callback?.onStateChanged(_state)
                callback?.onError("识别出错: ${e.message}")
            }
        } finally {
            cleanup()
        }
    }

    /**
     * 清理资源
     */
    private fun cleanup() {
        audioRecord?.release()
        audioRecord = null
        recordingFile?.delete()
        recordingFile = null
        _state = SiliconFlowRecognitionState.IDLE
        callback?.onStateChanged(_state)
    }

    /**
     * 释放所有资源
     */
    fun release() {
        cancel()
        scope.cancel()
    }
}

