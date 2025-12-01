package com.zigent.voice.xunfei

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.zigent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞语音识别状态
 */
enum class XunfeiRecognitionState {
    IDLE,           // 空闲
    CONNECTING,     // 连接中
    LISTENING,      // 正在听
    PROCESSING,     // 处理中
    SUCCESS,        // 识别成功
    ERROR           // 识别失败
}

/**
 * 讯飞语音识别回调
 */
interface XunfeiRecognitionCallback {
    fun onResult(text: String, isLast: Boolean)
    fun onPartialResult(text: String)
    fun onError(errorCode: Int, errorMessage: String)
    fun onStateChanged(state: XunfeiRecognitionState)
}

/**
 * 讯飞实时语音识别客户端
 * 使用WebSocket连接讯飞IAT API
 */
class XunfeiSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "XunfeiRecognizer"
        private const val STATUS_FIRST_FRAME = 0
        private const val STATUS_CONTINUE_FRAME = 1
        private const val STATUS_LAST_FRAME = 2
    }

    // OkHttp客户端
    private val okHttpClient = OkHttpClient.Builder()
        .build()
    
    // WebSocket连接
    private var webSocket: WebSocket? = null
    
    // 音频录制
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 识别状态
    private val _state = MutableStateFlow(XunfeiRecognitionState.IDLE)
    val state: StateFlow<XunfeiRecognitionState> = _state.asStateFlow()
    
    // 回调
    var callback: XunfeiRecognitionCallback? = null
    
    // 最终识别结果
    private val resultBuilder = StringBuilder()
    
    // Gson实例
    private val gson = Gson()
    
    // 是否正在识别
    val isListening: Boolean
        get() = _state.value == XunfeiRecognitionState.LISTENING ||
                _state.value == XunfeiRecognitionState.CONNECTING
    
    /**
     * 开始语音识别
     */
    fun startListening() {
        if (isListening) {
            Logger.w("Already listening", TAG)
            return
        }
        
        if (!XunfeiConfig.isConfigured()) {
            Logger.e("Xunfei not configured", null, TAG)
            updateState(XunfeiRecognitionState.ERROR)
            callback?.onError(-1, "讯飞语音服务未配置")
            return
        }
        
        // 检查麦克风权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Logger.e("No microphone permission", null, TAG)
            updateState(XunfeiRecognitionState.ERROR)
            callback?.onError(-2, "没有麦克风权限")
            return
        }
        
        resultBuilder.clear()
        updateState(XunfeiRecognitionState.CONNECTING)
        
        // 生成鉴权URL并连接WebSocket
        val authUrl = generateAuthUrl()
        Logger.d("Connecting to: $authUrl", TAG)
        
        val request = Request.Builder()
            .url(authUrl)
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
    }
    
    /**
     * 停止语音识别
     */
    fun stopListening() {
        Logger.i("Stopping listening", TAG)
        
        // 停止录音
        stopRecording()
        
        // 发送结束帧
        sendEndFrame()
    }
    
    /**
     * 取消语音识别
     */
    fun cancel() {
        Logger.i("Cancelling recognition", TAG)
        
        stopRecording()
        closeWebSocket()
        resultBuilder.clear()
        updateState(XunfeiRecognitionState.IDLE)
    }
    
    /**
     * 生成鉴权URL
     * 基于讯飞中英识别大模型文档的鉴权方式
     * https://www.xfyun.cn/doc/spark/spark_zh_iat.html#四、接口鉴权
     */
    private fun generateAuthUrl(): String {
        // 生成RFC1123格式的GMT时间戳
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")
        val date = dateFormat.format(Date())
        
        val host = XunfeiConfig.IAT_HOST
        val path = XunfeiConfig.IAT_PATH
        
        // 构建签名原文: host date request-line
        // request-line格式: GET /v1 HTTP/1.1
        val signatureOrigin = "host: $host\ndate: $date\nGET $path HTTP/1.1"
        
        Logger.d("Signature origin:\n$signatureOrigin", TAG)
        
        // 使用HMAC-SHA256计算签名
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(XunfeiConfig.API_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val signatureSha = mac.doFinal(signatureOrigin.toByteArray(StandardCharsets.UTF_8))
        val signature = Base64.encodeToString(signatureSha, Base64.NO_WRAP)
        
        // 构建authorization_origin
        // 格式: api_key="$api_key",algorithm="hmac-sha256",headers="host date request-line",signature="$signature"
        val authorizationOrigin = "api_key=\"${XunfeiConfig.API_KEY}\",algorithm=\"hmac-sha256\",headers=\"host date request-line\",signature=\"$signature\""
        
        // Base64编码authorization
        val authorization = Base64.encodeToString(authorizationOrigin.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        
        // URL编码各参数
        val encodedDate = URLEncoder.encode(date, "UTF-8")
        val encodedHost = URLEncoder.encode(host, "UTF-8")
        val encodedAuthorization = URLEncoder.encode(authorization, "UTF-8")
        
        // 构建最终鉴权URL
        val authUrl = "${XunfeiConfig.IAT_HOST_URL}?authorization=$encodedAuthorization&date=$encodedDate&host=$encodedHost"
        
        Logger.d("Auth URL: $authUrl", TAG)
        
        return authUrl
    }
    
    /**
     * 创建WebSocket监听器
     */
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.i("WebSocket connected", TAG)
                updateState(XunfeiRecognitionState.LISTENING)
                startRecording()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleResponse(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.e("WebSocket error: ${t.message}", t, TAG)
                stopRecording()
                updateState(XunfeiRecognitionState.ERROR)
                callback?.onError(-3, "连接失败: ${t.message}")
                updateState(XunfeiRecognitionState.IDLE)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.i("WebSocket closed: $code $reason", TAG)
                stopRecording()
                if (_state.value != XunfeiRecognitionState.SUCCESS) {
                    updateState(XunfeiRecognitionState.IDLE)
                }
            }
        }
    }
    
    /**
     * 开始录音
     */
    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            XunfeiConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                XunfeiConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e("AudioRecord initialization failed", null, TAG)
                updateState(XunfeiRecognitionState.ERROR)
                callback?.onError(-4, "音频录制初始化失败")
                return
            }
            
            audioRecord?.startRecording()
            Logger.i("Recording started", TAG)
            
            // 启动发送音频数据的协程
            recordingJob = scope.launch {
                sendAudioData()
            }
            
        } catch (e: SecurityException) {
            Logger.e("No permission to record audio", e, TAG)
            updateState(XunfeiRecognitionState.ERROR)
            callback?.onError(-2, "没有麦克风权限")
        } catch (e: Exception) {
            Logger.e("Failed to start recording", e, TAG)
            updateState(XunfeiRecognitionState.ERROR)
            callback?.onError(-5, "启动录音失败: ${e.message}")
        }
    }
    
    /**
     * 发送音频数据
     */
    private suspend fun sendAudioData() {
        val buffer = ByteArray(XunfeiConfig.FRAME_SIZE)
        var status = STATUS_FIRST_FRAME
        
        while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            
            if (readSize > 0) {
                val audioData = if (readSize == buffer.size) {
                    buffer
                } else {
                    buffer.copyOf(readSize)
                }
                
                sendFrame(audioData, status)
                
                if (status == STATUS_FIRST_FRAME) {
                    status = STATUS_CONTINUE_FRAME
                }
            }
            
            delay(40) // 40ms间隔，与帧大小匹配
        }
    }
    
    /**
     * 发送音频帧
     * 根据讯飞文档构建请求参数
     */
    private fun sendFrame(audioData: ByteArray, status: Int) {
        val frame = JsonObject().apply {
            // common参数（仅首帧需要）
            if (status == STATUS_FIRST_FRAME) {
                add("common", JsonObject().apply {
                    addProperty("app_id", XunfeiConfig.APPID)
                })
                
                // business参数（仅首帧需要）
                add("business", JsonObject().apply {
                    addProperty("language", XunfeiConfig.LANGUAGE)  // 语种：zh_cn
                    addProperty("domain", XunfeiConfig.DOMAIN)      // 领域：iat
                    addProperty("accent", XunfeiConfig.ACCENT)      // 方言：mandarin
                    addProperty("vad_eos", XunfeiConfig.VAD_EOS)    // 静音检测
                    addProperty("dwa", XunfeiConfig.DWA)            // 动态修正：wpgs
                    addProperty("ptt", XunfeiConfig.PTT)            // 标点：1
                    addProperty("nunum", XunfeiConfig.NUF)          // 数字格式：1
                })
            }
            
            // data参数（每帧都需要）
            add("data", JsonObject().apply {
                addProperty("status", status)  // 0-首帧, 1-中间帧, 2-尾帧
                addProperty("format", "audio/L16;rate=16000")  // 音频格式
                addProperty("encoding", "raw")  // 编码：raw(pcm)
                addProperty("audio", Base64.encodeToString(audioData, Base64.NO_WRAP))
            })
        }
        
        val jsonStr = gson.toJson(frame)
        Logger.d("Sending frame, status=$status, size=${audioData.size}", TAG)
        webSocket?.send(jsonStr)
    }
    
    /**
     * 发送结束帧
     */
    private fun sendEndFrame() {
        val frame = JsonObject().apply {
            add("data", JsonObject().apply {
                addProperty("status", STATUS_LAST_FRAME)  // 2-尾帧
                addProperty("format", "audio/L16;rate=16000")
                addProperty("encoding", "raw")
                addProperty("audio", "")  // 尾帧音频可以为空
            })
        }
        
        val jsonStr = gson.toJson(frame)
        Logger.d("Sending end frame: $jsonStr", TAG)
        webSocket?.send(jsonStr)
    }
    
    /**
     * 停止录音
     */
    private fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Logger.i("Recording stopped", TAG)
        } catch (e: Exception) {
            Logger.e("Failed to stop recording", e, TAG)
        }
    }
    
    /**
     * 关闭WebSocket
     */
    private fun closeWebSocket() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }
    
    /**
     * 处理服务器响应
     * 响应格式参考讯飞文档
     */
    private fun handleResponse(text: String) {
        try {
            Logger.d("Received response: $text", TAG)
            
            val response = gson.fromJson(text, JsonObject::class.java)
            val code = response.get("code")?.asInt ?: -1
            
            if (code != 0) {
                val message = response.get("message")?.asString ?: "未知错误"
                Logger.e("Server error: $code - $message", null, TAG)
                updateState(XunfeiRecognitionState.ERROR)
                callback?.onError(code, message)
                closeWebSocket()
                updateState(XunfeiRecognitionState.IDLE)
                return
            }
            
            // 解析识别结果
            val data = response.getAsJsonObject("data")
            val result = data?.getAsJsonObject("result")
            val status = data?.get("status")?.asInt ?: 0
            
            if (result != null) {
                val ws = result.getAsJsonArray("ws")
                val sb = StringBuilder()
                
                // 遍历ws数组，提取识别文字
                ws?.forEach { wsItem ->
                    val cw = wsItem.asJsonObject.getAsJsonArray("cw")
                    cw?.forEach { cwItem ->
                        val w = cwItem.asJsonObject.get("w")?.asString ?: ""
                        sb.append(w)
                    }
                }
                
                val partialText = sb.toString()
                if (partialText.isNotEmpty()) {
                    // 检查pgs字段（动态修正）
                    val pgs = result.get("pgs")?.asString
                    
                    if (pgs == "rpl") {
                        // rpl表示替换，需要根据rg范围替换之前的结果
                        // 简化处理：清空之前的结果，使用新结果
                        val sn = result.get("sn")?.asInt ?: 0
                        Logger.d("Replace mode, sn=$sn, text=$partialText", TAG)
                        // 对于实时显示，直接使用当前帧的结果
                    } else if (pgs == "apd") {
                        // apd表示追加
                        Logger.d("Append mode, text=$partialText", TAG)
                    }
                    
                    // 更新结果（简化处理：直接累加）
                    if (pgs != "rpl") {
                        resultBuilder.append(partialText)
                    }
                    
                    // 回调部分结果
                    val currentResult = if (pgs == "rpl") partialText else resultBuilder.toString()
                    callback?.onPartialResult(currentResult)
                    Logger.d("Partial result: $currentResult", TAG)
                }
            }
            
            // 检查是否是最后一帧响应（status=2表示结束）
            if (status == 2) {
                val finalResult = resultBuilder.toString()
                Logger.i("Final result: $finalResult", TAG)
                updateState(XunfeiRecognitionState.SUCCESS)
                callback?.onResult(finalResult, true)
                closeWebSocket()
                updateState(XunfeiRecognitionState.IDLE)
            }
            
        } catch (e: Exception) {
            Logger.e("Failed to parse response: $text", e, TAG)
        }
    }
    
    /**
     * 更新状态
     */
    private fun updateState(newState: XunfeiRecognitionState) {
        _state.value = newState
        callback?.onStateChanged(newState)
    }
    
    /**
     * 释放资源
     */
    fun release() {
        cancel()
        scope.cancel()
        Logger.i("XunfeiSpeechRecognizer released", TAG)
    }
}

