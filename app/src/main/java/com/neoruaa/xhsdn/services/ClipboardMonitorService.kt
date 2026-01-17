package com.neoruaa.xhsdn.services

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.neoruaa.xhsdn.MainActivity
import com.neoruaa.xhsdn.XHSApplication
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.utils.UrlUtils
import com.neoruaa.xhsdn.utils.NotificationHelper
import com.neoruaa.xhsdn.data.BackgroundDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.util.Log
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ClipboardMonitorService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val TAG = "ClipboardMonitor"
    private var clipboardManager: ClipboardManager? = null
    private var lastCheckedClipText: String? = null
    private var lastProcessedUrl: String? = null
    private var lastCheckTime: Long = 0
    private var lastMonitoringStateNotification: Boolean? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected: Monitoring started")
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        // 监听剪贴板变化
        clipboardManager?.addPrimaryClipChangedListener {
            Log.d(TAG, "Clipboard changed listener triggered")
            if (isAutoDownloadEnabled()) {
                checkClipboard()
            }
        }
        
        // 启动轮询以作为备用方案 (每3秒)
        scope.launch {
            while (isActive) {
                checkClipboard()
                delay(3000)
            }
        }
        
        createNotificationChannel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType
        val className = event?.className ?: "unknown"
        val packageName = event?.packageName ?: "unknown"
        
        // 记录所有事件，以便从日志中发现规律
        // Log.v(TAG, "onAccessibilityEvent: type=$eventType, package=$packageName, class=$className")
        
        // 依然保留主要过滤，但增加日志以便调试
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            
            if (isAutoDownloadEnabled()) {
                Log.d(TAG, "Event triggered check: $eventType from $packageName")
                checkClipboard()
            }
        }
    }

    override fun onInterrupt() {
        // 服务被中断
    }

    override fun onDestroy() {
        super.onDestroy()
        // serviceScope.cancel() // 一般 AccessibilityService 销毁即停止，无需手动清理 Scope 避免异常
    }

    private fun isAutoDownloadEnabled(): Boolean {
        val prefs = getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("auto_download_enabled", false)
    }

    private fun checkClipboard() {
        if (!isAutoDownloadEnabled()) return

        // 节流：防止短时间内被大量无意义事件淹没
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCheckTime < 800) return // 稍微调小一点

        scope.launch {
            try {
                // 关键点：延迟后再检查，增加重试机制解决系统同步延迟
                var text = ""
                var foundNew = false
                
                // 尝试最多 3 次，每次间隔 300ms
                for (attempt in 1..3) {
                    delay(300)
                    val clipboard = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    if (!clipboard.hasPrimaryClip()) continue
                    
                    val currentText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    if (currentText.isNotEmpty() && currentText != lastCheckedClipText) {
                        text = currentText
                        foundNew = true
                        break
                    }
                }

                if (!foundNew) return@launch
                
                // 此时更新最后检查时间
                lastCheckTime = System.currentTimeMillis()

                val isForeground = XHSApplication.isAppInForeground
                
                // 状态诊断通知逻辑
                if (lastMonitoringStateNotification != isForeground) {
                    lastMonitoringStateNotification = isForeground
                    if (isForeground) {
                        NotificationHelper.showDiagnosticNotification(this@ClipboardMonitorService, "监控状态", "App 在前台，下载器保持静默")
                    } else {
                        NotificationHelper.showDiagnosticNotification(this@ClipboardMonitorService, "监控状态", "App 在后台，自动下载待命")
                    }
                }

                if (isForeground) {
                    Log.d(TAG, "checkClipboard: App in foreground, updating text record: $text")
                    synchronized(this@ClipboardMonitorService) {
                        lastCheckedClipText = text
                    }
                    return@launch
                }

                Log.d(TAG, "checkClipboard: Detected new text in background: $text")
                synchronized(this@ClipboardMonitorService) {
                    lastCheckedClipText = text
                }

                val url = UrlUtils.extractFirstUrl(text)
                if (url == null) return@launch

                if (UrlUtils.isXhsLink(url)) {
                    Log.d(TAG, "checkClipboard: FOUND XHS LINK, starting download: $url")
                    BackgroundDownloadManager.startDownload(applicationContext, url, text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkClipboard error", e)
            }
        }
    }



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "自动下载通知"
            val descriptionText = "发现剪贴板中的小红书链接时通知"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "xhs_download_channel_v2"
    }
}
