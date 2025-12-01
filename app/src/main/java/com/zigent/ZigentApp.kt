package com.zigent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * Zigent应用程序入口类
 * 负责全局初始化配置
 */
@HiltAndroidApp
class ZigentApp : Application() {

    companion object {
        const val TAG = "Zigent"
        
        // 通知渠道ID
        const val NOTIFICATION_CHANNEL_ID = "zigent_service_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        
        // 创建通知渠道
        createNotificationChannel()
    }

    /**
     * 创建前台服务所需的通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}

