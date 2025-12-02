package com.zigent.utils

import android.util.Log
import com.zigent.ZigentApp

/**
 * 统一日志工具类
 * 方便调试和问题排查
 */
object Logger {
    
    private const val TAG = ZigentApp.TAG
    
    fun d(message: String, tag: String = TAG) {
        Log.d(tag, message)
    }
    
    fun i(message: String, tag: String = TAG) {
        Log.i(tag, message)
    }
    
    fun w(message: String, tag: String = TAG) {
        Log.w(tag, message)
    }
    
    fun w(message: String, throwable: Throwable?, tag: String = TAG) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }
    
    fun e(message: String, tag: String = TAG) {
        Log.e(tag, message)
    }
    
    fun e(message: String, throwable: Throwable?, tag: String = TAG) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
    
    fun v(message: String, tag: String = TAG) {
        Log.v(tag, message)
    }
}
