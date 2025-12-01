# Zigent - AI智能手机助手

## 项目概述

Zigent是一个原生Android应用，通过悬浮球交互界面，结合无障碍服务和WIFI ADB技术，实现AI驱动的手机自动化操作助手。用户只需通过语音描述任务，AI Agent将自动控制手机完成多轮操作。

---

## 核心功能

### 1. 悬浮球界面
- 类iOS风格的悬浮球设计
- 可拖拽移动、自动贴边
- 点击触发/结束语音输入
- 展示任务执行状态

### 2. 语音交互
- 语音识别（语音转文字）
- 语音合成（任务反馈）
- 支持长按说话模式

### 3. 屏幕数据抓取
- **无障碍服务模式**：获取UI层级、控件信息、文本内容
- **WIFI ADB模式**：屏幕截图、UI Automator数据、更深层操作

### 4. AI Agent引擎
- 任务理解与规划
- 多轮对话与操作
- 屏幕理解与元素定位
- 操作执行与结果验证

### 5. 自动化操作
- 点击、滑动、输入文本
- 启动应用、返回、Home键
- 复杂操作序列编排

---

## 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                        用户交互层                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   悬浮球    │  │  语音输入   │  │  状态展示   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                        AI Agent层                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │  任务规划   │  │  屏幕理解   │  │  操作决策   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                        数据采集层                           │
│  ┌─────────────────────┐  ┌─────────────────────┐          │
│  │   无障碍服务        │  │    WIFI ADB         │          │
│  │ - UI层级树          │  │ - 屏幕截图          │          │
│  │ - 控件属性          │  │ - UI Dump           │          │
│  │ - 事件监听          │  │ - Shell命令         │          │
│  └─────────────────────┘  └─────────────────────┘          │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                        操作执行层                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │  手势操作   │  │  输入操作   │  │  系统操作   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

---

## 开发阶段规划

### 第一阶段：基础框架搭建（预计3天）

#### 1.1 项目初始化
- [ ] 创建Android项目（Kotlin）
- [ ] 配置Gradle依赖
- [ ] 设置最低SDK版本（API 24+）
- [ ] 配置必要权限

#### 1.2 悬浮球实现
- [ ] 创建悬浮球Service
- [ ] 实现悬浮窗权限申请
- [ ] 悬浮球UI设计（圆形、半透明、动画）
- [ ] 拖拽移动功能
- [ ] 自动贴边吸附
- [ ] 点击事件处理

#### 1.3 基础UI
- [ ] 主Activity（设置界面）
- [ ] 权限引导页面
- [ ] 状态展示组件

---

### 第二阶段：无障碍服务（预计2天）

#### 2.1 无障碍服务配置
- [ ] 创建AccessibilityService
- [ ] 配置accessibility-service XML
- [ ] 实现服务启用引导

#### 2.2 屏幕数据采集
- [ ] 获取当前窗口信息
- [ ] 遍历UI节点树
- [ ] 提取控件属性（文本、类型、位置、可点击性）
- [ ] 构建结构化数据

#### 2.3 基础操作执行
- [ ] 通过无障碍执行点击
- [ ] 执行滑动手势
- [ ] 执行文本输入
- [ ] 全局操作（返回、Home、最近任务）

---

### 第三阶段：WIFI ADB模块（预计3天）

#### 3.1 ADB连接管理
- [ ] WIFI ADB连接检测
- [ ] 自动连接/重连机制
- [ ] 连接状态监控

#### 3.2 ADB数据采集
- [ ] 屏幕截图（screencap）
- [ ] UI层级导出（uiautomator dump）
- [ ] 获取当前Activity信息

#### 3.3 ADB操作执行
- [ ] input tap 点击
- [ ] input swipe 滑动
- [ ] input text 文本输入
- [ ] am start 启动应用
- [ ] 按键事件（keyevent）

---

### 第四阶段：语音交互模块（预计2天）

#### 4.1 语音识别
- [ ] 集成语音识别SDK（科大讯飞/百度/Google）
- [ ] 实现录音功能
- [ ] 语音转文字处理
- [ ] 错误处理与重试

#### 4.2 语音合成
- [ ] 集成TTS引擎
- [ ] 任务状态语音播报
- [ ] 结果语音反馈

#### 4.3 悬浮球语音交互
- [ ] 点击开始录音
- [ ] 再次点击结束录音
- [ ] 录音状态动画
- [ ] 语音波形展示

---

### 第五阶段：AI Agent核心（预计5天）

#### 5.1 AI接口集成
- [ ] 配置AI API（OpenAI/Claude/国产大模型）
- [ ] 设计Prompt模板
- [ ] 实现流式响应处理

#### 5.2 屏幕理解模块
- [ ] 截图+UI层级数据融合
- [ ] 构建屏幕描述Prompt
- [ ] 元素定位与标注

#### 5.3 任务规划引擎
- [ ] 用户意图理解
- [ ] 任务分解与步骤规划
- [ ] 动态调整策略

#### 5.4 操作决策模块
- [ ] 基于屏幕状态生成操作
- [ ] 操作参数计算（坐标、文本）
- [ ] 操作可行性验证

#### 5.5 执行与反馈循环
- [ ] 执行操作
- [ ] 等待页面响应
- [ ] 采集新状态
- [ ] 判断任务完成/继续/失败
- [ ] 多轮循环直到完成

---

### 第六阶段：优化与完善（预计3天）

#### 6.1 性能优化
- [ ] 截图压缩与缓存
- [ ] UI树解析优化
- [ ] 网络请求优化

#### 6.2 用户体验
- [ ] 操作过程可视化
- [ ] 任务进度展示
- [ ] 操作历史记录
- [ ] 错误提示优化

#### 6.3 稳定性
- [ ] 异常处理完善
- [ ] 服务保活机制
- [ ] 日志与监控

---

## 目录结构规划

```
Zigent/
├── app/
│   ├── src/main/
│   │   ├── java/com/zigent/
│   │   │   ├── ZigentApp.kt                 # Application类
│   │   │   ├── MainActivity.kt              # 主界面
│   │   │   │
│   │   │   ├── ui/                          # UI层
│   │   │   │   ├── floating/                # 悬浮球
│   │   │   │   │   ├── FloatingService.kt
│   │   │   │   │   ├── FloatingView.kt
│   │   │   │   │   └── FloatingAnimator.kt
│   │   │   │   ├── settings/                # 设置界面
│   │   │   │   └── status/                  # 状态展示
│   │   │   │
│   │   │   ├── accessibility/               # 无障碍服务
│   │   │   │   ├── ZigentAccessibilityService.kt
│   │   │   │   ├── NodeParser.kt
│   │   │   │   └── GestureExecutor.kt
│   │   │   │
│   │   │   ├── adb/                         # ADB模块
│   │   │   │   ├── AdbManager.kt
│   │   │   │   ├── AdbCommands.kt
│   │   │   │   └── WifiAdbConnector.kt
│   │   │   │
│   │   │   ├── voice/                       # 语音模块
│   │   │   │   ├── VoiceRecognizer.kt
│   │   │   │   ├── TextToSpeech.kt
│   │   │   │   └── AudioRecorder.kt
│   │   │   │
│   │   │   ├── agent/                       # AI Agent
│   │   │   │   ├── AgentEngine.kt
│   │   │   │   ├── TaskPlanner.kt
│   │   │   │   ├── ScreenAnalyzer.kt
│   │   │   │   ├── ActionDecider.kt
│   │   │   │   └── ExecutionLoop.kt
│   │   │   │
│   │   │   ├── ai/                          # AI接口
│   │   │   │   ├── AiClient.kt
│   │   │   │   ├── PromptBuilder.kt
│   │   │   │   └── ResponseParser.kt
│   │   │   │
│   │   │   └── utils/                       # 工具类
│   │   │       ├── PermissionHelper.kt
│   │   │       ├── ImageUtils.kt
│   │   │       └── Logger.kt
│   │   │
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   ├── drawable/
│   │   │   ├── values/
│   │   │   └── xml/
│   │   │       └── accessibility_service_config.xml
│   │   │
│   │   └── AndroidManifest.xml
│   │
│   └── build.gradle.kts
│
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 所需权限

```xml
<!-- 悬浮窗权限 -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>

<!-- 网络权限（AI API调用） -->
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>

<!-- 录音权限（语音输入） -->
<uses-permission android:name="android.permission.RECORD_AUDIO"/>

<!-- 前台服务 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>

<!-- 存储权限（截图保存） -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
```

---

## 技术选型

| 模块 | 技术方案 | 备选方案 |
|------|---------|---------|
| 开发语言 | Kotlin | Java |
| UI框架 | Jetpack Compose | 传统View |
| 网络请求 | OkHttp + Retrofit | Ktor |
| 协程 | Kotlin Coroutines | RxJava |
| 依赖注入 | Hilt | Koin |
| 语音识别 | Android SpeechRecognizer | 科大讯飞/百度语音 |
| 语音合成 | Android TTS | 科大讯飞TTS |
| **AI模型** | **硅基流动 Qwen3-VL (默认)** | OpenAI / Claude |
| ADB库 | Shell命令 | dadb |

---

## AI配置说明

### 默认配置（推荐）

| 配置项 | 值 |
|--------|-----|
| 提供商 | 硅基流动 (SiliconFlow) |
| API地址 | https://api.siliconflow.cn/v1 |
| 模型 | Qwen/Qwen3-VL-235B-A22B-Instruct |
| 用户需配置 | API Key |

### 获取API Key

1. 访问 [硅基流动官网](https://siliconflow.cn)
2. 注册账号
3. 在控制台获取 API Key
4. 在应用设置中填入 API Key

### 支持的AI提供商

| 提供商 | 视觉能力 | 国内访问 | 推荐度 |
|--------|---------|---------|-------|
| **硅基流动** | ✅ Qwen3-VL | ✅ 无需代理 | ⭐⭐⭐⭐⭐ |
| OpenAI | ✅ GPT-4o | ❌ 需代理 | ⭐⭐⭐⭐ |
| Claude | ✅ Claude 3.5 | ❌ 需代理 | ⭐⭐⭐⭐ |
| 自定义API | 取决于模型 | 取决于服务 | ⭐⭐⭐ |

---

## 工作流程示意

```
用户点击悬浮球 → 开始录音
        ↓
用户再次点击 → 结束录音
        ↓
语音识别 → 文字任务
        ↓
AI理解任务 → 生成执行计划
        ↓
┌─────────────────────────────┐
│       执行循环              │
│  ↓                          │
│  采集屏幕数据               │
│  ↓                          │
│  AI分析当前状态             │
│  ↓                          │
│  决策下一步操作             │
│  ↓                          │
│  执行操作（点击/滑动/输入） │
│  ↓                          │
│  检查是否完成 ──否──→ 继续循环│
│  ↓ 是                       │
└─────────────────────────────┘
        ↓
语音播报完成 → 悬浮球恢复待机
```

---

## 下一步行动

确认以上规划后，我将按照以下顺序开始开发：

1. **立即开始**：创建Android项目基础结构
2. **第一周**：完成悬浮球 + 无障碍服务
3. **第二周**：完成WIFI ADB + 语音模块
4. **第三周**：完成AI Agent核心功能
5. **第四周**：优化、测试、完善

---

## 风险与挑战

| 风险 | 影响 | 应对策略 |
|------|------|---------|
| 无障碍权限被系统限制 | 功能受限 | 结合ADB作为备选 |
| AI响应延迟 | 用户体验差 | 流式输出+本地缓存 |
| 屏幕理解不准确 | 操作失败 | 多模态融合+重试机制 |
| 不同手机兼容性 | 部分设备异常 | 多设备测试+适配层 |
| WIFI ADB连接不稳定 | 功能中断 | 自动重连+降级方案 |

---

## 更新日志

- **2024-12-01**：项目规划文档创建
- **2024-12-01**：**第一阶段完成**
  - 创建Android项目基础结构（Kotlin + Jetpack Compose）
  - 实现悬浮球Service和FloatingBallView
  - 实现悬浮球拖拽移动和贴边吸附功能
  - 实现悬浮球多状态动画（呼吸、脉冲、进度环）
  - 创建主Activity和权限引导界面
  - 实现无障碍服务基础框架
  - 配置Hilt依赖注入

- **2024-12-01**：**第二、三阶段完成**
  - 实现WIFI ADB连接管理器（AdbConnection）
  - 实现ADB命令执行器（截图、UI层级导出、点击、滑动、输入等）
  - 实现AdbManager统一管理
  - 实现语音识别模块（VoiceRecognizer）- 使用Android原生SpeechRecognizer
  - 实现语音合成模块（TextToSpeechManager）- 使用Android原生TTS
  - 实现VoiceManager统一管理语音交互
  - 实现FloatingInteractionController交互控制器
  - 集成悬浮球语音交互完整流程

- **2024-12-01**：**第五阶段完成 - AI Agent核心**
  - 实现AI客户端（AiClient）- 支持OpenAI和Claude API
  - 实现多模态支持（文本+图片）
  - 实现屏幕分析器（ScreenAnalyzer）- 采集UI元素和截图
  - 实现Prompt构建器 - 智能构建AI提示词
  - 实现操作决策器（ActionDecider）- AI决策下一步操作
  - 实现操作执行器（ActionExecutor）- 执行点击/滑动/输入等
  - 实现Agent引擎（AgentEngine）- 核心执行循环
  - 实现设置仓库（SettingsRepository）- DataStore持久化
  - 实现设置界面（SettingsScreen）- AI配置UI
  - 完成Agent与交互控制器集成

