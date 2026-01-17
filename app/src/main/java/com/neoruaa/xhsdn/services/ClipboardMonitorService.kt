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
        // 过滤无关事件，仅在窗口变化或内容变化时检查 (如点击复制)
        val eventType = event?.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (isAutoDownloadEnabled()) {
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
        if (!isAutoDownloadEnabled()) {
            return
        }

        // 节流保护：1秒内最多执行一次，防止通知弹窗触发无限循环
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCheckTime < 1000) {
            return
        }
        lastCheckTime = currentTime

        try {
            val clipboard = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                val text = clip?.getItemAt(0)?.text?.toString() ?: ""
                
                // 此时在后台状态反馈 (仅在状态变化时通知)
                val isForeground = XHSApplication.isAppInForeground
                if (lastMonitoringStateNotification != isForeground) {
                    lastMonitoringStateNotification = isForeground
                    if (isForeground) {
                        NotificationHelper.showDiagnosticNotification(this, "监控状态", "App 在前台，下载器保持静默")
                    } else {
                        NotificationHelper.showDiagnosticNotification(this, "监控状态", "App 在后台，自动下载待命")
                    }
                }

                // 移除 isForeground 拦截：即使在前后台切换的瞬间，只要复制成功，就应该触发
                // Log.d(TAG, "checkClipboard: isAppInForeground=$isForeground, text=$text")

                // 避免重复处理相同内容 (原子性检查)
                synchronized(this) {
                    if (text.isEmpty() || text == lastCheckedClipText) {
                        return
                    }
                    lastCheckedClipText = text
                }
                
                Log.d(TAG, "checkClipboard: Processing text=$text")

                val url = UrlUtils.extractFirstUrl(text)
                if (url == null) {
                    Log.d(TAG, "No URL found in text")
                    return
                }

                if (url == lastProcessedUrl) {
                    Log.d(TAG, "Same URL as last processed, skipping.")
                    return
                }
                lastProcessedUrl = url

                if (UrlUtils.isXhsLink(url)) {
                    Log.d(TAG, "Starting background download for valid XHS link: $url")
                    BackgroundDownloadManager.startDownload(this, url, text)
                } else {
                    Log.d(TAG, "Not an XHS link: $url")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkClipboard error", e)
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
