package com.zigent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zigent.ai.AiConfig
import com.zigent.ai.AiProvider
import com.zigent.ai.AiSettings
import com.zigent.agent.AgentEngine
import com.zigent.data.SettingsRepository
import com.zigent.ui.floating.FloatingService
import com.zigent.ui.settings.SettingsScreen
import com.zigent.ui.theme.ZigentTheme
import com.zigent.utils.Logger
import com.zigent.utils.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主界面Activity
 * 负责权限引导和服务控制
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }
    
    @Inject
    lateinit var settingsRepository: SettingsRepository
    
    @Inject
    lateinit var agentEngine: AgentEngine

    // 权限状态
    private var hasOverlayPermission by mutableStateOf(false)
    private var hasAccessibilityPermission by mutableStateOf(false)
    private var hasMicrophonePermission by mutableStateOf(false)
    
    // 服务状态
    private var isServiceRunning by mutableStateOf(false)
    
    // UI导航状态
    private var showSettings by mutableStateOf(false)
    
    // AI设置状态
    private var currentAiSettings by mutableStateOf(
        AiSettings(
            provider = AiProvider.SILICONFLOW,
            apiKey = "",
            baseUrl = AiConfig.SILICONFLOW_BASE_URL,
            model = AiConfig.SILICONFLOW_MODEL
        )
    )
    private var isTestingConnection by mutableStateOf(false)
    private var testResult by mutableStateOf<String?>(null)

    // 麦克风权限请求
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicrophonePermission = isGranted
        Logger.d("Microphone permission: $isGranted", TAG)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 加载保存的AI设置
        lifecycleScope.launch {
            settingsRepository.aiSettingsFlow.collect { settings ->
                currentAiSettings = settings
                // 配置AgentEngine
                if (settings.apiKey.isNotBlank()) {
                    agentEngine.configureAi(settings)
                }
            }
        }
        
        // 定期刷新权限状态
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refreshPermissionStatus()
                    delay(1000)
                }
            }
        }
        
        setContent {
            ZigentTheme {
                if (showSettings) {
                    SettingsScreen(
                        currentSettings = currentAiSettings,
                        onSaveSettings = { settings -> saveAiSettings(settings) },
                        onTestConnection = { testAiConnection() },
                        isTestingConnection = isTestingConnection,
                        testResult = testResult,
                        onBack = { 
                            showSettings = false 
                            testResult = null
                        }
                    )
                } else {
                    MainScreen(
                        hasOverlayPermission = hasOverlayPermission,
                        hasAccessibilityPermission = hasAccessibilityPermission,
                        hasMicrophonePermission = hasMicrophonePermission,
                        isServiceRunning = isServiceRunning,
                        isAiConfigured = currentAiSettings.apiKey.isNotBlank(),
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onRequestAccessibilityPermission = { requestAccessibilityPermission() },
                        onRequestMicrophonePermission = { requestMicrophonePermission() },
                        onToggleService = { toggleService() },
                        onOpenSettings = { showSettings = true }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    /**
     * 刷新权限状态
     */
    private fun refreshPermissionStatus() {
        hasOverlayPermission = PermissionHelper.canDrawOverlays(this)
        hasAccessibilityPermission = PermissionHelper.isAccessibilityServiceEnabled(this)
        hasMicrophonePermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        isServiceRunning = FloatingService.isRunning
    }

    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        PermissionHelper.requestOverlayPermission(this)
    }

    /**
     * 请求无障碍权限
     */
    private fun requestAccessibilityPermission() {
        PermissionHelper.openAccessibilitySettings(this)
    }

    /**
     * 请求麦克风权限
     */
    private fun requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * 切换服务状态
     */
    private fun toggleService() {
        if (isServiceRunning) {
            FloatingService.stop(this)
        } else {
            FloatingService.start(this)
        }
        // 稍后刷新状态
        lifecycleScope.launch {
            delay(500)
            refreshPermissionStatus()
        }
    }
    
    /**
     * 保存AI设置
     */
    private fun saveAiSettings(settings: AiSettings) {
        currentAiSettings = settings
        lifecycleScope.launch {
            settingsRepository.saveAiSettings(settings)
            // 配置AgentEngine
            if (settings.apiKey.isNotBlank()) {
                agentEngine.configureAi(settings)
            }
        }
    }
    
    /**
     * 测试AI连接
     */
    private fun testAiConnection() {
        isTestingConnection = true
        testResult = null
        lifecycleScope.launch {
            try {
                val success = agentEngine.testAiConnection()
                testResult = if (success) "✓ 连接成功！" else "✗ 连接失败，请检查设置"
            } catch (e: Exception) {
                testResult = "✗ 连接失败: ${e.message}"
            } finally {
                isTestingConnection = false
            }
        }
    }
}

/**
 * 主界面Composable
 */
@Composable
fun MainScreen(
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    hasMicrophonePermission: Boolean,
    isServiceRunning: Boolean,
    isAiConfigured: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    onToggleService: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val allPermissionsGranted = hasOverlayPermission && hasAccessibilityPermission && hasMicrophonePermission
    
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            
            // Logo和标题
            AppHeader()
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // AI设置卡片
            AiSettingsCard(
                isConfigured = isAiConfigured,
                onOpenSettings = onOpenSettings
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 权限卡片
            PermissionSection(
                hasOverlayPermission = hasOverlayPermission,
                hasAccessibilityPermission = hasAccessibilityPermission,
                hasMicrophonePermission = hasMicrophonePermission,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onRequestAccessibilityPermission = onRequestAccessibilityPermission,
                onRequestMicrophonePermission = onRequestMicrophonePermission
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 启动按钮
            ServiceControlButton(
                enabled = allPermissionsGranted && isAiConfigured,
                isRunning = isServiceRunning,
                onClick = onToggleService,
                needsAiConfig = !isAiConfigured
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 使用说明
            UsageInstructions()
            
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/**
 * 应用头部
 */
@Composable
fun AppHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF6366F1),
                            Color(0xFF8B5CF6)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Z",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Zigent",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Text(
            text = "AI智能手机助手",
            fontSize = 14.sp,
            color = Color(0xFF94A3B8)
        )
    }
}

/**
 * 权限配置区域
 */
@Composable
fun PermissionSection(
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    hasMicrophonePermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "权限配置",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            PermissionItem(
                icon = Icons.Default.Layers,
                title = "悬浮窗权限",
                description = "显示悬浮球界面",
                isGranted = hasOverlayPermission,
                onRequest = onRequestOverlayPermission
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            PermissionItem(
                icon = Icons.Default.Accessibility,
                title = "无障碍服务",
                description = "读取屏幕内容和执行操作",
                isGranted = hasAccessibilityPermission,
                onRequest = onRequestAccessibilityPermission
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            PermissionItem(
                icon = Icons.Default.Mic,
                title = "麦克风权限",
                description = "语音输入功能",
                isGranted = hasMicrophonePermission,
                onRequest = onRequestMicrophonePermission
            )
        }
    }
}

/**
 * 权限项
 */
@Composable
fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF334155))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (isGranted) Color(0xFF10B981).copy(alpha = 0.2f)
                    else Color(0xFF6366F1).copy(alpha = 0.2f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF10B981) else Color(0xFF6366F1),
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // 文字
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )
        }
        
        // 状态/按钮
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "已授权",
                tint = Color(0xFF10B981),
                modifier = Modifier.size(24.dp)
            )
        } else {
            TextButton(
                onClick = onRequest,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF6366F1)
                )
            ) {
                Text("授权")
            }
        }
    }
}

/**
 * AI设置卡片
 */
@Composable
fun AiSettingsCard(
    isConfigured: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpenSettings() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConfigured) Color(0xFF10B981).copy(alpha = 0.2f)
                        else Color(0xFFEF4444).copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = if (isConfigured) Color(0xFF10B981) else Color(0xFFEF4444),
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = if (isConfigured) "已配置 - 点击修改" else "未配置 - 点击设置API Key",
                    fontSize = 13.sp,
                    color = if (isConfigured) Color(0xFF10B981) else Color(0xFFEF4444)
                )
            }
            
            // 箭头
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "进入设置",
                tint = Color(0xFF64748B),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 服务控制按钮
 */
@Composable
fun ServiceControlButton(
    enabled: Boolean,
    isRunning: Boolean,
    onClick: () -> Unit,
    needsAiConfig: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) Color(0xFFEF4444) else Color(0xFF6366F1),
            disabledContainerColor = Color(0xFF475569)
        )
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
            fontWeight = FontWeight.SemiBold
        )
    }
    
    if (!enabled) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (needsAiConfig) "请先配置AI设置" else "请先授予所有权限",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 使用说明
 */
@Composable
fun UsageInstructions() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "使用说明",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            InstructionStep(
                number = "1",
                text = "点击悬浮球开始语音输入"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            InstructionStep(
                number = "2",
                text = "说出你想让AI完成的任务"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            InstructionStep(
                number = "3",
                text = "再次点击悬浮球结束语音"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            InstructionStep(
                number = "4",
                text = "AI将自动控制手机完成任务"
            )
        }
    }
}

/**
 * 使用步骤项
 */
@Composable
fun InstructionStep(
    number: String,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xFF6366F1).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF6366F1)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = text,
            fontSize = 14.sp,
            color = Color(0xFFE2E8F0)
        )
    }
}

