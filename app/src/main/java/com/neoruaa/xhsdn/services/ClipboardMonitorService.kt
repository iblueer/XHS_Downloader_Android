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
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.utils.UrlUtils
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
        // 不需要处理具体的无障碍事件，只需保活
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
        // 只有开启了自动下载才进行检查
        if (!isAutoDownloadEnabled()) {
            Log.d(TAG, "Auto download is disabled, skipping clipboard check.")
            return
        }
        
        // 如果 App 在前台，不进行自动下载，交给前台 Activity 处理
        if (MainActivity.isForeground) {
            Log.d(TAG, "App is in foreground, skipping background download.")
            return
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                val text = clip?.getItemAt(0)?.text?.toString()
                Log.d(TAG, "checkClipboard: text=$text")

                // 避免重复处理相同内容
                if (text == lastCheckedClipText) {
                    Log.d(TAG, "checkClipboard: Same content as last check, skipping.")
                    return
                }
                lastCheckedClipText = text

                val url = text?.let { UrlUtils.extractFirstUrl(it) }
                if (url == lastProcessedUrl) {
                    Log.d(TAG, "Same URL as last processed, skipping.")
                    return
                }
                lastProcessedUrl = url

                Log.d(TAG, "checkClipboard: extracted url=$url")
                if (UrlUtils.isXhsLink(url)) {
                    // 开始后台下载
                    Log.d(TAG, "Starting background download for valid XHS link: $url")
                    BackgroundDownloadManager.startDownload(this, url!!, text)
                } else {
                    Log.d(TAG, "checkClipboard: Not an XHS link or no URL found.")
                }
            } else {
                Log.d(TAG, "checkClipboard: No primary clip")
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
        private const val CHANNEL_ID = "auto_download_channel"
    }
}
