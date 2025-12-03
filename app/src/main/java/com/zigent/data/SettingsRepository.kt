package com.zigent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.zigent.ai.AiConfig
import com.zigent.ai.AiProvider
import com.zigent.ai.AiSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// DataStore实例
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zigent_settings")

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
        private val SILICONFLOW_API_KEY = stringPreferencesKey("siliconflow_api_key")  // 硅基流动API Key（语音识别）
        private val AI_PROVIDER = stringPreferencesKey("ai_provider")  // Agent提供商
        private val AI_API_KEY = stringPreferencesKey("ai_api_key")  // Agent API Key
        private val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        private val AI_MODEL = stringPreferencesKey("ai_model")
        private val AI_VISION_MODEL = stringPreferencesKey("ai_vision_model")  // VLM 模型
        private val AI_MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        private val AI_TEMPERATURE = floatPreferencesKey("ai_temperature")
        
        // Agent设置键
        private val USE_VISION_MODE = booleanPreferencesKey("use_vision_mode")
        private val MAX_STEPS = intPreferencesKey("max_steps")
        
        // 通用设置键
        private val SPEECH_RATE = floatPreferencesKey("speech_rate")
        private val AUTO_START_SERVICE = booleanPreferencesKey("auto_start_service")
        
        // 应用限制设置
        private val ALLOWED_APPS = stringPreferencesKey("allowed_apps")  // 允许操作的应用列表（JSON）
        private val APP_RESTRICTION_MODE = stringPreferencesKey("app_restriction_mode")  // 限制模式
    }

    /**
     * 获取AI设置Flow
     */
    val aiSettingsFlow: Flow<AiSettings> = context.dataStore.data.map { prefs ->
        AiSettings(
            siliconFlowApiKey = prefs[SILICONFLOW_API_KEY] ?: "",
            provider = try {
                AiProvider.valueOf(prefs[AI_PROVIDER] ?: AiProvider.SILICONFLOW.name)
            } catch (e: Exception) {
                AiProvider.SILICONFLOW
            },
            apiKey = prefs[AI_API_KEY] ?: "",
            baseUrl = prefs[AI_BASE_URL] ?: AiConfig.SILICONFLOW_BASE_URL,
            model = prefs[AI_MODEL] ?: AiConfig.SILICONFLOW_LLM_MODEL,
            visionModel = prefs[AI_VISION_MODEL] ?: AiConfig.SILICONFLOW_VLM_MODEL,
            maxTokens = prefs[AI_MAX_TOKENS] ?: AiConfig.MAX_TOKENS,
            temperature = prefs[AI_TEMPERATURE] ?: AiConfig.TEMPERATURE
        )
    }

    /**
     * 保存AI设置
     */
    suspend fun saveAiSettings(settings: AiSettings) {
        context.dataStore.edit { prefs ->
            prefs[SILICONFLOW_API_KEY] = settings.siliconFlowApiKey
            prefs[AI_PROVIDER] = settings.provider.name
            prefs[AI_API_KEY] = settings.apiKey
            prefs[AI_BASE_URL] = settings.baseUrl
            prefs[AI_MODEL] = settings.model
            prefs[AI_VISION_MODEL] = settings.visionModel
            prefs[AI_MAX_TOKENS] = settings.maxTokens
            prefs[AI_TEMPERATURE] = settings.temperature
        }
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
    
    // ==================== 应用限制设置 ====================
    
    /**
     * 应用限制模式
     */
    enum class AppRestrictionMode {
        ALLOW_ALL,    // 允许所有应用（默认）
        WHITELIST,    // 白名单模式（仅允许列表中的应用）
        BLACKLIST     // 黑名单模式（禁止列表中的应用）
    }
    
    /**
     * 应用限制设置
     */
    data class AppRestrictionSettings(
        val mode: AppRestrictionMode = AppRestrictionMode.ALLOW_ALL,
        val appList: Set<String> = emptySet()  // 包名列表
    )
    
    /**
     * 获取应用限制设置
     */
    val appRestrictionFlow: Flow<AppRestrictionSettings> = context.dataStore.data.map { prefs ->
        val mode = try {
            AppRestrictionMode.valueOf(prefs[APP_RESTRICTION_MODE] ?: AppRestrictionMode.ALLOW_ALL.name)
        } catch (e: Exception) {
            AppRestrictionMode.ALLOW_ALL
        }
        
        val appsJson = prefs[ALLOWED_APPS] ?: "[]"
        val appList = try {
            com.google.gson.Gson().fromJson(appsJson, Array<String>::class.java).toSet()
        } catch (e: Exception) {
            emptySet()
        }
        
        AppRestrictionSettings(mode, appList)
    }
    
    /**
     * 保存应用限制设置
     */
    suspend fun saveAppRestriction(settings: AppRestrictionSettings) {
        context.dataStore.edit { prefs ->
            prefs[APP_RESTRICTION_MODE] = settings.mode.name
            prefs[ALLOWED_APPS] = com.google.gson.Gson().toJson(settings.appList.toTypedArray())
        }
    }
    
    /**
     * 检查应用是否允许操作
     */
    suspend fun isAppAllowed(packageName: String): Boolean {
        val settings = context.dataStore.data.map { prefs ->
            val mode = try {
                AppRestrictionMode.valueOf(prefs[APP_RESTRICTION_MODE] ?: AppRestrictionMode.ALLOW_ALL.name)
            } catch (e: Exception) {
                AppRestrictionMode.ALLOW_ALL
            }
            
            val appsJson = prefs[ALLOWED_APPS] ?: "[]"
            val appList = try {
                com.google.gson.Gson().fromJson(appsJson, Array<String>::class.java).toSet()
            } catch (e: Exception) {
                emptySet<String>()
            }
            
            when (mode) {
                AppRestrictionMode.ALLOW_ALL -> true
                AppRestrictionMode.WHITELIST -> packageName in appList
                AppRestrictionMode.BLACKLIST -> packageName !in appList
            }
        }
        
        return settings.first()
    }
}
