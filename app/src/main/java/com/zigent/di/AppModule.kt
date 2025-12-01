package com.zigent.di

import android.content.Context
import com.zigent.adb.AdbManager
import com.zigent.agent.ActionExecutor
import com.zigent.agent.AgentEngine
import com.zigent.agent.ScreenAnalyzer
import com.zigent.data.SettingsRepository
import com.zigent.voice.VoiceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt依赖注入模块
 * 提供全局单例对象
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 提供ADB管理器单例
     */
    @Provides
    @Singleton
    fun provideAdbManager(
        @ApplicationContext context: Context
    ): AdbManager {
        return AdbManager(context)
    }

    /**
     * 提供语音管理器单例
     */
    @Provides
    @Singleton
    fun provideVoiceManager(
        @ApplicationContext context: Context
    ): VoiceManager {
        return VoiceManager(context)
    }

    /**
     * 提供设置仓库单例
     */
    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository {
        return SettingsRepository(context)
    }

    /**
     * 提供屏幕分析器单例
     */
    @Provides
    @Singleton
    fun provideScreenAnalyzer(
        @ApplicationContext context: Context,
        adbManager: AdbManager
    ): ScreenAnalyzer {
        return ScreenAnalyzer(context, adbManager)
    }

    /**
     * 提供操作执行器单例
     */
    @Provides
    @Singleton
    fun provideActionExecutor(
        @ApplicationContext context: Context,
        adbManager: AdbManager
    ): ActionExecutor {
        return ActionExecutor(context, adbManager)
    }

    /**
     * 提供Agent引擎单例
     */
    @Provides
    @Singleton
    fun provideAgentEngine(
        @ApplicationContext context: Context,
        screenAnalyzer: ScreenAnalyzer,
        actionExecutor: ActionExecutor
    ): AgentEngine {
        return AgentEngine(context, screenAnalyzer, actionExecutor)
    }
}

