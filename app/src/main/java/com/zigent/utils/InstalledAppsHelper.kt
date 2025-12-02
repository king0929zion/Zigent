package com.zigent.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 已安装应用信息
 */
data class InstalledApp(
    val name: String,           // 应用显示名称
    val packageName: String,    // 包名
    val isSystemApp: Boolean    // 是否系统应用
)

/**
 * 已安装应用帮助类
 * 用于获取设备上安装的应用列表
 */
object InstalledAppsHelper {
    
    private const val TAG = "InstalledAppsHelper"
    
    // 缓存应用列表
    private var cachedApps: List<InstalledApp>? = null
    private var lastCacheTime: Long = 0
    private const val CACHE_DURATION = 60000L  // 1分钟缓存
    
    /**
     * 获取所有已安装的用户应用（非系统应用）
     */
    fun getInstalledUserApps(context: Context, forceRefresh: Boolean = false): List<InstalledApp> {
        val now = System.currentTimeMillis()
        
        // 使用缓存
        if (!forceRefresh && cachedApps != null && (now - lastCacheTime) < CACHE_DURATION) {
            return cachedApps!!
        }
        
        val apps = mutableListOf<InstalledApp>()
        
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            
            for (appInfo in packages) {
                // 跳过系统应用
                if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                    continue
                }
                
                // 跳过没有启动Activity的应用
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent == null) {
                    continue
                }
                
                val appName = pm.getApplicationLabel(appInfo).toString()
                
                apps.add(InstalledApp(
                    name = appName,
                    packageName = appInfo.packageName,
                    isSystemApp = false
                ))
            }
            
            // 按名称排序
            apps.sortBy { it.name }
            
            // 更新缓存
            cachedApps = apps
            lastCacheTime = now
            
            Logger.d("Found ${apps.size} user apps", TAG)
            
        } catch (e: Exception) {
            Logger.e("Failed to get installed apps", e, TAG)
        }
        
        return apps
    }
    
    /**
     * 获取所有已安装应用（包括系统应用）
     */
    fun getAllInstalledApps(context: Context): List<InstalledApp> {
        val apps = mutableListOf<InstalledApp>()
        
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            
            for (appInfo in packages) {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent == null) continue
                
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                
                apps.add(InstalledApp(
                    name = appName,
                    packageName = appInfo.packageName,
                    isSystemApp = isSystem
                ))
            }
            
            apps.sortBy { it.name }
            
        } catch (e: Exception) {
            Logger.e("Failed to get all apps", e, TAG)
        }
        
        return apps
    }
    
    /**
     * 根据应用名查找包名（智能匹配）
     */
    fun findPackageByName(context: Context, appName: String): String? {
        val apps = getInstalledUserApps(context)
        val nameLower = appName.lowercase().trim()
            .replace("打开", "")
            .replace("启动", "")
            .replace("应用", "")
            .replace("app", "")
            .replace("软件", "")
            .trim()
        
        // 1. 精确匹配（完全相同）
        apps.find { it.name.lowercase() == nameLower }?.let { return it.packageName }
        
        // 2. 别名匹配（最常见的别名）
        val aliasMap = mapOf(
            "谷歌笔记" to listOf("google keep", "keep", "keep记事本"),
            "浏览器" to listOf("chrome", "browser", "谷歌浏览器"),
            "油管" to listOf("youtube"),
            "邮箱" to listOf("gmail", "email"),
            "地图" to listOf("maps", "google maps", "谷歌地图"),
            "翻译" to listOf("translate", "google translate", "谷歌翻译"),
            "照相机" to listOf("camera", "相机"),
            "图库" to listOf("photos", "gallery", "相册"),
            "音乐" to listOf("music", "qqmusic", "网易云音乐"),
            "视频" to listOf("video", "腾讯视频", "爱奇艺")
        )
        
        for ((alias, targets) in aliasMap) {
            if (nameLower.contains(alias) || alias.contains(nameLower)) {
                for (target in targets) {
                    apps.find { it.name.lowercase().contains(target) }?.let { return it.packageName }
                }
            }
        }
        
        // 3. 包含匹配（应用名包含搜索词）
        apps.find { 
            it.name.lowercase().contains(nameLower) 
        }?.let { return it.packageName }
        
        // 4. 模糊匹配（搜索词包含应用名的任意部分）
        apps.find { 
            nameLower.contains(it.name.lowercase())
        }?.let { return it.packageName }
        
        return null
    }
    
    /**
     * 生成应用列表的文本描述（用于 AI 上下文）
     * 提供应用名和包名，帮助AI识别应用
     */
    fun generateAppsContext(context: Context): String {
        val apps = getInstalledUserApps(context)
        
        if (apps.isEmpty()) {
            Logger.w("Installed app list is empty. Package visibility may be restricted.", TAG)
            return "【已安装应用】无法获取应用列表，可能缺少应用可见性权限（QUERY_ALL_PACKAGES）。请检查权限后重试。"
        }
        
        val sb = StringBuilder()
        sb.appendLine("## 已安装应用列表（共 ${apps.size} 个）")
        sb.appendLine("重要：调用 open_app 时必须使用下面列出的完整应用名称（第一列），包名仅供参考")
        sb.appendLine("格式：应用名 | 包名")
        sb.appendLine()
        
        // 提供应用名和包名，便于AI识别
        apps.forEach { app ->
            sb.appendLine("${app.name} | ${app.packageName}")
        }
        
        sb.appendLine()
        sb.appendLine("提示：")
        sb.appendLine("- Google Keep = Keep记事本/Google Keep/记事本")
        sb.appendLine("- Chrome = 谷歌浏览器/Chrome浏览器/Chrome")
        sb.appendLine("- YouTube = 油管/YouTube")
        sb.appendLine("- 使用列表中显示的确切应用名，如显示'Google Keep'就用'Google Keep'")
        
        return sb.toString()
    }
    
    /**
     * 生成应用映射表（用于快速查找）
     */
    fun getAppNameToPackageMap(context: Context): Map<String, String> {
        val apps = getInstalledUserApps(context)
        val map = mutableMapOf<String, String>()
        
        apps.forEach { app ->
            // 原始名称
            map[app.name.lowercase()] = app.packageName
            
            // 简化名称（去掉空格和特殊字符）
            val simpleName = app.name.lowercase()
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")
            map[simpleName] = app.packageName
        }
        
        return map
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        cachedApps = null
        lastCacheTime = 0
    }
}

