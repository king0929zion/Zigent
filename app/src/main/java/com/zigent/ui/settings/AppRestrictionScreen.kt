package com.zigent.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zigent.data.SettingsRepository.AppRestrictionMode
import com.zigent.data.SettingsRepository.AppRestrictionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用限制设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRestrictionScreen(
    currentSettings: AppRestrictionSettings,
    onSaveSettings: (AppRestrictionSettings) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(currentSettings.mode) }
    var selectedApps by remember { mutableStateOf(currentSettings.appList) }
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // 加载已安装的应用
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    // 只显示用户应用
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                }
                .map { app ->
                    AppInfo(
                        packageName = app.packageName,
                        appName = pm.getApplicationLabel(app).toString(),
                        isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedBy { it.appName }
            
            installedApps = apps
            isLoading = false
        }
    }
    
    // 过滤后的应用列表
    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            installedApps.filter { app ->
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF1E293B)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                
                Text(
                    text = "应用操作限制",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                // 保存按钮
                TextButton(
                    onClick = {
                        onSaveSettings(AppRestrictionSettings(mode, selectedApps))
                        onBack()
                    }
                ) {
                    Text("保存", color = Color(0xFF6366F1))
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 模式选择
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "限制模式",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 允许所有
                    ModeRadioButton(
                        text = "允许所有应用",
                        description = "Agent 可以操作任何应用",
                        selected = mode == AppRestrictionMode.ALLOW_ALL,
                        onClick = { mode = AppRestrictionMode.ALLOW_ALL }
                    )
                    
                    // 白名单
                    ModeRadioButton(
                        text = "仅允许选中的应用",
                        description = "Agent 只能操作下方勾选的应用",
                        selected = mode == AppRestrictionMode.WHITELIST,
                        onClick = { mode = AppRestrictionMode.WHITELIST }
                    )
                    
                    // 黑名单
                    ModeRadioButton(
                        text = "禁止选中的应用",
                        description = "Agent 不能操作下方勾选的应用",
                        selected = mode == AppRestrictionMode.BLACKLIST,
                        onClick = { mode = AppRestrictionMode.BLACKLIST }
                    )
                }
            }
            
            // 如果不是"允许所有"模式，显示应用列表
            if (mode != AppRestrictionMode.ALLOW_ALL) {
                Spacer(modifier = Modifier.height(16.dp))
                
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索应用...", color = Color(0xFF64748B)) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color(0xFF94A3B8)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清除",
                                    tint = Color(0xFF94A3B8)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color(0xFF475569),
                        cursorColor = Color(0xFF6366F1)
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 已选数量
                Text(
                    text = "已选择 ${selectedApps.size} 个应用",
                    fontSize = 14.sp,
                    color = Color(0xFF94A3B8)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 应用列表
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF6366F1))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredApps) { app ->
                            AppItem(
                                app = app,
                                isSelected = app.packageName in selectedApps,
                                onClick = {
                                    selectedApps = if (app.packageName in selectedApps) {
                                        selectedApps - app.packageName
                                    } else {
                                        selectedApps + app.packageName
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 模式单选按钮
 */
@Composable
private fun ModeRadioButton(
    text: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFF6366F1),
                unselectedColor = Color(0xFF64748B)
            )
        )
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                fontSize = 15.sp,
                color = Color.White
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color(0xFF64748B)
            )
        }
    }
}

/**
 * 应用列表项
 */
@Composable
private fun AppItem(
    app: AppInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF2D3A4F) else Color(0xFF1E293B)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标占位
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF334155)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.appName.firstOrNull()?.toString() ?: "?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    maxLines = 1
                )
            }
            
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF6366F1),
                    uncheckedColor = Color(0xFF64748B)
                )
            )
        }
    }
}

/**
 * 应用信息
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)

