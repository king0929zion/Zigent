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
     * 根据应用名查找包名
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
        
        // 精确匹配
        apps.find { it.name.lowercase() == nameLower }?.let { return it.packageName }
        
        // 包含匹配
        apps.find { 
            it.name.lowercase().contains(nameLower) || 
            nameLower.contains(it.name.lowercase()) 
        }?.let { return it.packageName }
        
        return null
    }
    
    /**
     * 生成应用列表的文本描述（用于 AI 上下文）
     * 直接提供应用名，AI 使用应用名即可打开
     */
    fun generateAppsContext(context: Context): String {
        val apps = getInstalledUserApps(context)
        
        if (apps.isEmpty()) {
            return "【已安装应用】无法获取应用列表"
        }
        
        val sb = StringBuilder()
        sb.appendLine("## 已安装应用列表（共 ${apps.size} 个）")
        sb.appendLine("调用 open_app 时直接使用应用名即可，无需包名")
        sb.appendLine()
        
        // 只显示应用名，按拼音排序
        apps.forEachIndexed { index, app ->
            sb.append(app.name)
            if (index < apps.size - 1) {
                sb.append("、")
            }
            // 每10个应用换行
            if ((index + 1) % 10 == 0) {
                sb.appendLine()
            }
        }
        if (apps.size % 10 != 0) {
            sb.appendLine()
        }
        
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

