package com.zigent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zigent.accessibility.ZigentAccessibilityService
import com.zigent.ai.AiConfig
import com.zigent.ai.AiProvider
import com.zigent.ai.AiSettings
import com.zigent.agent.AgentEngine
import com.zigent.core.ServiceManager
import com.zigent.data.SettingsRepository
import com.zigent.shizuku.ShizukuState
import com.zigent.ui.floating.FloatingService
import com.zigent.ui.settings.SettingsScreen
import com.zigent.ui.theme.ZigentTheme
import com.zigent.utils.Logger
import com.zigent.utils.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主界面Activity
 * 负责权限引导、服务控制和导航
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }
    
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var agentEngine: AgentEngine
    
    // 服务管理器
    private lateinit var serviceManager: ServiceManager

    // 麦克风权限请求
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Logger.d("Microphone permission: $isGranted", TAG)
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        serviceManager = ServiceManager.getInstance(this)
        
        setContent {
            ZigentTheme {
                MainApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
    
    private fun refreshStatus() {
        lifecycleScope.launch {
            val aiSettings = settingsRepository.aiSettingsFlow.first()
            serviceManager.refreshStatus(aiSettings.isAsrAvailable() && aiSettings.isAgentAvailable())
        }
    }

    @Composable
    private fun MainApp() {
        val navController = rememberNavController()
        val status by serviceManager.status.collectAsState()
        var currentAiSettings by remember { 
            mutableStateOf(AiSettings())
        }
        
        // 加载保存的AI设置
        LaunchedEffect(Unit) {
            settingsRepository.aiSettingsFlow.collect { settings ->
                currentAiSettings = settings
                serviceManager.refreshStatus(settings.isAsrAvailable() && settings.isAgentAvailable())
            }
        }
        
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    status = status,
                    aiSettings = currentAiSettings,
                    isServiceRunning = FloatingService.isRunning,
                    onToggleService = { toggleService() },
                    onNavigateToSettings = { navController.navigate("settings") },
                    onRequestOverlay = { requestOverlayPermission() },
                    onRequestAccessibility = { requestAccessibilityPermission() },
                    onRequestMicrophone = { requestMicrophonePermission() },
                    onRequestShizuku = { serviceManager.requestShizukuPermission() },
                    onOpenShizukuApp = { openShizukuApp() }
                )
            }
            composable("settings") {
                SettingsScreen(
                    currentSettings = currentAiSettings,
                    onSaveSettings = { newSettings ->
                        lifecycleScope.launch {
                            settingsRepository.saveAiSettings(newSettings)
                            currentAiSettings = newSettings
                            agentEngine.configureAi(newSettings)
                            // 更新 FloatingService 中的 Agent
                            FloatingService.instance?.getInteractionController()?.configureAi(newSettings)
                        }
                    },
                    onTestConnection = {
                        lifecycleScope.launch {
                            try {
                                agentEngine.configureAi(currentAiSettings)
                                agentEngine.testAiConnection()
                            } catch (e: Exception) {
                                // 忽略错误
                            }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    private fun toggleService() {
        if (FloatingService.isRunning) {
            FloatingService.stop(this)
        } else {
            if (serviceManager.hasOverlayPermission()) {
                FloatingService.start(this)
            }
        }
    }

    private fun requestOverlayPermission() {
        PermissionHelper.requestOverlayPermission(this)
    }

    private fun requestAccessibilityPermission() {
        PermissionHelper.openAccessibilitySettings(this)
    }

    private fun requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    
    private fun openShizukuApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                startActivity(intent)
            } else {
                // 打开 Google Play
                val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))
                startActivity(playIntent)
            }
        } catch (e: Exception) {
            Logger.e("Failed to open Shizuku", e, TAG)
        }
    }
}

// ==================== Composable UI Components ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    status: com.zigent.core.ServiceStatus,
    aiSettings: AiSettings,
    isServiceRunning: Boolean,
    onToggleService: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenShizukuApp: () -> Unit
) {
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Zigent",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态卡片
            StatusCard(
                isReady = status.isReady && status.aiConfigured,
                isServiceRunning = isServiceRunning,
                statusMessage = if (status.aiConfigured) status.getReadinessMessage() else "需要配置AI设置"
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 启动按钮
            ServiceControlButton(
                isRunning = isServiceRunning,
                isEnabled = status.isReady,
                onClick = onToggleService
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 权限设置
            Text(
                "权限设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
            AssistChip(
                onClick = {
                    val missing = status.getMissingPermissions()
                    val message = if (missing.isEmpty()) "自检完成：全部就绪" else "缺少权限：" + missing.joinToString("、")
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(message, withDismissAction = true)
                    }
                },
                label = { Text("权限自检") },
                leadingIcon = {
                    Icon(Icons.Outlined.Security, contentDescription = null)
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface
                )
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 悬浮窗权限
            PermissionCard(
                icon = Icons.Outlined.Layers,
                title = "悬浮窗权限",
                description = "显示悬浮球",
                isGranted = status.overlayPermission,
                onClick = onRequestOverlay
            )
            
            // 无障碍服务
            PermissionCard(
                icon = Icons.Outlined.Accessibility,
                title = "无障碍服务",
                description = "控制手机操作",
                isGranted = status.accessibilityEnabled,
                onClick = onRequestAccessibility
            )
            
            // Shizuku
            ShizukuCard(
                state = status.shizukuState,
                onRequestPermission = onRequestShizuku,
                onOpenApp = onOpenShizukuApp
            )
            
            // 麦克风权限
            PermissionCard(
                icon = Icons.Outlined.Mic,
                title = "麦克风权限",
                description = "语音输入",
                isGranted = status.microphonePermission,
                onClick = onRequestMicrophone
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // AI配置
            Text(
                "AI配置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
            
            AiConfigCard(
                isConfigured = status.aiConfigured,
                provider = aiSettings.provider.name,
                onClick = onNavigateToSettings
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 使用说明
            UsageGuide()
        }
    }
}

@Composable
private fun StatusCard(
    isReady: Boolean,
    isServiceRunning: Boolean,
    statusMessage: String
) {
    val highlightColor = if (isReady) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val iconColor = if (isReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = highlightColor.copy(alpha = 0.8f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.PlayArrow 
                                 else if (isReady) Icons.Default.Check 
                                 else Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isServiceRunning -> "服务运行中"
                        isReady -> "已就绪"
                        else -> "需要配置"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                )
            }
        }
    }
}

@Composable
private fun ServiceControlButton(
    isRunning: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = isEnabled || isRunning,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isRunning) "停止服务" else "启动服务",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = !isGranted, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            
            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已授权",
                    tint = MaterialTheme.colorScheme.secondary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "去设置",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}


@Composable
private fun ShizukuCard(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    onOpenApp: () -> Unit
) {
    val (statusText, statusColor, action) = when (state) {
        ShizukuState.NOT_INSTALLED -> Triple("未安装", MaterialTheme.colorScheme.tertiary, onOpenApp)
        ShizukuState.NOT_RUNNING -> Triple("未启动", MaterialTheme.colorScheme.tertiary, onOpenApp)
        ShizukuState.NOT_AUTHORIZED -> Triple("未授权", MaterialTheme.colorScheme.tertiary, onRequestPermission)
        ShizukuState.READY -> Triple("已就绪", MaterialTheme.colorScheme.secondary, {})
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = state != ShizukuState.READY, onClick = action),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.AdminPanelSettings,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Shizuku (可选)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "截屏和高级操作· $statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            if (state == ShizukuState.READY) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已就绪",
                    tint = MaterialTheme.colorScheme.secondary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}



@Composable
private fun AiConfigCard(
    isConfigured: Boolean,
    provider: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isConfigured) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                           else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                tint = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI 模型配置",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isConfigured) "已配置 $provider" else "点击配置 API Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            if (isConfigured) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已配置",
                    tint = MaterialTheme.colorScheme.secondary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}



@Composable
private fun UsageGuide() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "📖 使用指南",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            GuideStep(number = "1", text = "授予必要权限并配置 AI")
            GuideStep(number = "2", text = "点击【启动服务】显示悬浮球")
            GuideStep(number = "3", text = "点击悬浮球开始语音输入")
            GuideStep(number = "4", text = "说完后再次点击悬浮球")
            GuideStep(number = "5", text = "AI 将自动执行您的指令")

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "💡 提示：安装 Shizuku 可获得截屏和更强的操作能力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}



@Composable
private fun GuideStep(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

