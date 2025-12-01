package com.zigent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zigent.ai.AiConfig
import com.zigent.ai.AiProvider
import com.zigent.ai.AiSettings

/**
 * 设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentSettings: AiSettings,
    onSaveSettings: (AiSettings) -> Unit,
    onTestConnection: () -> Unit,
    isTestingConnection: Boolean = false,
    testResult: String? = null,
    onBack: () -> Unit
) {
    var provider by remember { mutableStateOf(currentSettings.provider) }
    var apiKey by remember { mutableStateOf(currentSettings.apiKey) }
    var baseUrl by remember { mutableStateOf(currentSettings.baseUrl) }
    var model by remember { mutableStateOf(currentSettings.model) }
    var showApiKey by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    
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
                .verticalScroll(rememberScrollState())
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
                    text = "AI设置",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // AI提供商选择
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "AI提供商",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = when (provider) {
                                AiProvider.SILICONFLOW -> "硅基流动 (推荐)"
                                AiProvider.OPENAI -> "OpenAI"
                                AiProvider.CLAUDE -> "Claude (Anthropic)"
                                AiProvider.CUSTOM -> "自定义API"
                            },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF475569)
                            )
                        )
                        
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("硅基流动 (推荐)") },
                                onClick = {
                                    provider = AiProvider.SILICONFLOW
                                    baseUrl = AiConfig.SILICONFLOW_BASE_URL
                                    model = AiConfig.SILICONFLOW_MODEL
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("OpenAI") },
                                onClick = {
                                    provider = AiProvider.OPENAI
                                    baseUrl = ""
                                    model = AiConfig.DEFAULT_MODEL_OPENAI
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Claude (Anthropic)") },
                                onClick = {
                                    provider = AiProvider.CLAUDE
                                    baseUrl = ""
                                    model = AiConfig.DEFAULT_MODEL_CLAUDE
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自定义API") },
                                onClick = {
                                    provider = AiProvider.CUSTOM
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // API Key
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "API Key",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        placeholder = { Text("输入你的API Key", color = Color(0xFF64748B)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "隐藏" else "显示",
                                    tint = Color(0xFF94A3B8)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF6366F1),
                            unfocusedBorderColor = Color(0xFF475569),
                            cursorColor = Color(0xFF6366F1)
                        ),
                        singleLine = true
                    )
                }
            }
            
            // API URL（自定义时可编辑，硅基流动显示但不可编辑）
            if (provider == AiProvider.CUSTOM || provider == AiProvider.SILICONFLOW) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "API地址",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { if (provider == AiProvider.CUSTOM) baseUrl = it },
                            placeholder = { Text("https://api.example.com/v1", color = Color(0xFF64748B)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = provider == AiProvider.CUSTOM,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                disabledTextColor = Color(0xFF94A3B8),
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF475569),
                                disabledBorderColor = Color(0xFF334155),
                                cursorColor = Color(0xFF6366F1)
                            ),
                            singleLine = true
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 模型选择
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "模型",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = model.ifEmpty {
                            when (provider) {
                                AiProvider.SILICONFLOW -> AiConfig.SILICONFLOW_MODEL
                                AiProvider.OPENAI -> AiConfig.DEFAULT_MODEL_OPENAI
                                AiProvider.CLAUDE -> AiConfig.DEFAULT_MODEL_CLAUDE
                                AiProvider.CUSTOM -> ""
                            }
                        },
                        onValueChange = { model = it },
                        placeholder = { Text("模型名称", color = Color(0xFF64748B)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF6366F1),
                            unfocusedBorderColor = Color(0xFF475569),
                            cursorColor = Color(0xFF6366F1)
                        ),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = when (provider) {
                            AiProvider.SILICONFLOW -> "默认: Qwen3-VL-235B (视觉模型)"
                            AiProvider.OPENAI -> "推荐: gpt-4o, gpt-4o-mini"
                            AiProvider.CLAUDE -> "推荐: claude-3-5-sonnet-20241022"
                            AiProvider.CUSTOM -> "输入兼容OpenAI格式的模型名"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 测试连接按钮
            Button(
                onClick = {
                    onSaveSettings(
                        AiSettings(
                            provider = provider,
                            apiKey = apiKey,
                            baseUrl = when (provider) {
                                AiProvider.SILICONFLOW -> AiConfig.SILICONFLOW_BASE_URL
                                AiProvider.CUSTOM -> baseUrl
                                else -> ""
                            },
                            model = model.ifEmpty {
                                when (provider) {
                                    AiProvider.SILICONFLOW -> AiConfig.SILICONFLOW_MODEL
                                    AiProvider.OPENAI -> AiConfig.DEFAULT_MODEL_OPENAI
                                    AiProvider.CLAUDE -> AiConfig.DEFAULT_MODEL_CLAUDE
                                    AiProvider.CUSTOM -> ""
                                }
                            }
                        )
                    )
                    onTestConnection()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF334155)
                ),
                enabled = apiKey.isNotBlank() && !isTestingConnection
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isTestingConnection) "测试中..." else "测试连接",
                    fontSize = 16.sp
                )
            }
            
            // 测试结果
            testResult?.let { result ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = result,
                    fontSize = 14.sp,
                    color = if (result.contains("成功")) Color(0xFF10B981) else Color(0xFFEF4444)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 保存按钮
            Button(
                onClick = {
                    onSaveSettings(
                        AiSettings(
                            provider = provider,
                            apiKey = apiKey,
                            baseUrl = when (provider) {
                                AiProvider.SILICONFLOW -> AiConfig.SILICONFLOW_BASE_URL
                                AiProvider.CUSTOM -> baseUrl
                                else -> ""
                            },
                            model = model.ifEmpty {
                                when (provider) {
                                    AiProvider.SILICONFLOW -> AiConfig.SILICONFLOW_MODEL
                                    AiProvider.OPENAI -> AiConfig.DEFAULT_MODEL_OPENAI
                                    AiProvider.CLAUDE -> AiConfig.DEFAULT_MODEL_CLAUDE
                                    AiProvider.CUSTOM -> ""
                                }
                            }
                        )
                    )
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6366F1)
                ),
                enabled = apiKey.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "保存设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

