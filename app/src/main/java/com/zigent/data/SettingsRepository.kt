package com.zigent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.zigent.ai.AiConfig
import com.zigent.ai.AiProvider
import com.zigent.ai.AiSettings
import com.zigent.voice.xunfei.XunfeiConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// DataStore实例
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zigent_settings")

/**
 * 讯飞语音设置
 */
data class XunfeiSettings(
    val appId: String = "",
    val apiKey: String = "",
    val apiSecret: String = ""
) {
    fun isConfigured(): Boolean = appId.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()
}

/**
 * 设置仓库
 * 负责保存和读取用户设置
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // AI设置键
        private val AI_PROVIDER = stringPreferencesKey("ai_provider")
        private val AI_API_KEY = stringPreferencesKey("ai_api_key")
        private val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        private val AI_MODEL = stringPreferencesKey("ai_model")
        private val AI_MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        private val AI_TEMPERATURE = floatPreferencesKey("ai_temperature")
        
        // 讯飞语音设置键
        private val XUNFEI_APP_ID = stringPreferencesKey("xunfei_app_id")
        private val XUNFEI_API_KEY = stringPreferencesKey("xunfei_api_key")
        private val XUNFEI_API_SECRET = stringPreferencesKey("xunfei_api_secret")
        
        // Agent设置键
        private val USE_VISION_MODE = booleanPreferencesKey("use_vision_mode")
        private val MAX_STEPS = intPreferencesKey("max_steps")
        
        // 通用设置键
        private val SPEECH_RATE = floatPreferencesKey("speech_rate")
        private val AUTO_START_SERVICE = booleanPreferencesKey("auto_start_service")
    }

    /**
     * 获取AI设置Flow
     */
    val aiSettingsFlow: Flow<AiSettings> = context.dataStore.data.map { prefs ->
        AiSettings(
            provider = try {
                AiProvider.valueOf(prefs[AI_PROVIDER] ?: AiProvider.OPENAI.name)
            } catch (e: Exception) {
                AiProvider.OPENAI
            },
            apiKey = prefs[AI_API_KEY] ?: "",
            baseUrl = prefs[AI_BASE_URL] ?: "",
            model = prefs[AI_MODEL] ?: "",
            maxTokens = prefs[AI_MAX_TOKENS] ?: AiConfig.MAX_TOKENS,
            temperature = prefs[AI_TEMPERATURE] ?: AiConfig.TEMPERATURE
        )
    }

    /**
     * 保存AI设置
     */
    suspend fun saveAiSettings(settings: AiSettings) {
        context.dataStore.edit { prefs ->
            prefs[AI_PROVIDER] = settings.provider.name
            prefs[AI_API_KEY] = settings.apiKey
            prefs[AI_BASE_URL] = settings.baseUrl
            prefs[AI_MODEL] = settings.model
            prefs[AI_MAX_TOKENS] = settings.maxTokens
            prefs[AI_TEMPERATURE] = settings.temperature
        }
    }
    
    /**
     * 获取讯飞语音设置Flow
     */
    val xunfeiSettingsFlow: Flow<XunfeiSettings> = context.dataStore.data.map { prefs ->
        XunfeiSettings(
            appId = prefs[XUNFEI_APP_ID] ?: XunfeiConfig.APPID,
            apiKey = prefs[XUNFEI_API_KEY] ?: XunfeiConfig.API_KEY,
            apiSecret = prefs[XUNFEI_API_SECRET] ?: XunfeiConfig.API_SECRET
        )
    }
    
    /**
     * 保存讯飞语音设置
     */
    suspend fun saveXunfeiSettings(settings: XunfeiSettings) {
        context.dataStore.edit { prefs ->
            prefs[XUNFEI_APP_ID] = settings.appId
            prefs[XUNFEI_API_KEY] = settings.apiKey
            prefs[XUNFEI_API_SECRET] = settings.apiSecret
        }
        // 同步更新XunfeiConfig
        XunfeiConfig.configure(settings.appId, settings.apiKey, settings.apiSecret)
    }

    /**
     * 获取是否使用视觉模式
     */
    val useVisionModeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_VISION_MODE] ?: true
    }

    /**
     * 设置是否使用视觉模式
     */
    suspend fun setUseVisionMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[USE_VISION_MODE] = enabled
        }
    }

    /**
     * 获取语音速率
     */
    val speechRateFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SPEECH_RATE] ?: 1.0f
    }

    /**
     * 设置语音速率
     */
    suspend fun setSpeechRate(rate: Float) {
        context.dataStore.edit { prefs ->
            prefs[SPEECH_RATE] = rate
        }
    }

    /**
     * 获取是否自动启动服务
     */
    val autoStartServiceFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_START_SERVICE] ?: false
    }

    /**
     * 设置是否自动启动服务
     */
    suspend fun setAutoStartService(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_START_SERVICE] = enabled
        }
    }

    /**
     * 清除所有设置
     */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}

