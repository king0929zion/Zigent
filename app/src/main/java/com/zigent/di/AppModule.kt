package com.zigent.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt依赖注入模块
 * 
 * 注意：以下类使用 @Inject 构造器自动注入，不需要手动提供：
 * - AdbManager
 * - VoiceManager
 * - SettingsRepository
 * - ScreenAnalyzer
 * - ActionExecutor
 * - AgentEngine
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // 所有依赖都使用 @Inject 构造器注入
    // 如需添加第三方库或需要特殊配置的依赖，在此处用 @Provides 提供
}

