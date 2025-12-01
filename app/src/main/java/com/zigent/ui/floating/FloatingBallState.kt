package com.zigent.ui.floating

/**
 * 悬浮球状态枚举
 * 用于表示悬浮球的不同工作状态
 */
enum class FloatingBallState {
    /** 空闲状态 - 等待用户交互 */
    IDLE,
    
    /** 聆听状态 - 正在录音 */
    LISTENING,
    
    /** 处理状态 - AI正在分析任务 */
    PROCESSING,
    
    /** 执行状态 - Agent正在执行操作 */
    EXECUTING,
    
    /** 成功状态 - 任务完成 */
    SUCCESS,
    
    /** 错误状态 - 任务失败 */
    ERROR
}

