package com.neoruaa.xhsdn.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.neoruaa.xhsdn.DownloadCallback
import com.neoruaa.xhsdn.MainActivity
import com.neoruaa.xhsdn.R
import com.neoruaa.xhsdn.XHSDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException

object BackgroundDownloadManager {
    private const val TAG = "BackgroundDownload"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private const val CHANNEL_ID = "auto_download_channel_high"
    private const val NOTIFICATION_ID = 1001

    fun startDownload(context: Context, url: String, title: String? = null) {
        Log.d(TAG, "startDownload: $url, title: $title")
        val appContext = context.applicationContext
        
        // Ensure TaskManager is initialized
        TaskManager.init(appContext)

        // Check for duplicates
        if (TaskManager.hasRecentTask(url)) {
            Log.d(TAG, "startDownload: Task matches recent task, skipping duplicate download. URL: $url")
            return
        }

        scope.launch {
            var taskId: Long = -1 // Initialize with invalid ID
            try {
                showNotification(appContext, "正在准备下载...", url, true)

                // 1. Get info
                // We run this inside runCatching because getMediaCount might throw or do network ops
                val mediaCount = runCatching { XHSDownloader(appContext).getMediaCount(url) }.getOrElse { 0 }
                val noteType = if (mediaCount == 1) NoteType.VIDEO else NoteType.IMAGE
                
                // 2. Create Task
                taskId = TaskManager.createTask(url, title, noteType, if (mediaCount > 0) mediaCount else 1)
                
                // Store job for cancellation
                activeJobs[taskId] = coroutineContext[Job]!!
                
                TaskManager.startTask(taskId)
                
                showNotification(appContext, "正在下载...", "共 $mediaCount 个文件", true)

                val completedFiles = java.util.concurrent.atomic.AtomicInteger(0)
                val failedFiles = java.util.concurrent.atomic.AtomicInteger(0)

                // 3. Setup Downloader
                val downloader = XHSDownloader(appContext, object : DownloadCallback {
                    override fun onFileDownloaded(filePath: String) {
                         val completed = completedFiles.incrementAndGet()
                         TaskManager.updateProgress(taskId, completed, failedFiles.get())
                         TaskManager.addFilePath(taskId, filePath)
                         // Update notification if needed
                    }

                    override fun onDownloadError(status: String, originalUrl: String) {
                        // Individual file error? 
                         // Not explicitly counted in current MainViewModel logic as "failed file" usually
                         // unless we track total vs completed. 
                         // For simplicity, we just log it.
                    }

                    override fun onDownloadProgress(status: String) {}
                    override fun onDownloadProgressUpdate(downloaded: Long, total: Long) {}
                    override fun onVideoDetected() {
                         // Should not happen as we disable stopOnVideo
                    }
                })
                
                downloader.setShouldStopOnVideo(false)
                
                // 4. Download
                val success = downloader.downloadContent(url)
                
                // 5. Complete
                if (success) {
                    val completed = completedFiles.get()
                    // If mediaCount was 0 (unknown), use completed as total
                    val finalTotal = if (mediaCount == 0) completed else mediaCount
                    
                    if (completed > 0) {
                        TaskManager.completeTask(taskId, true)
                        showNotification(appContext, "下载完成", "成功下载 $completed 个文件", false)
                    } else {
                        TaskManager.completeTask(taskId, false, "未下载任何文件")
                        showNotification(appContext, "下载失败", "未能下载文件", false)
                    }
                } else {
                    TaskManager.completeTask(taskId, false, "下载过程出错")
                    showNotification(appContext, "下载失败", "请检查网络或链接", false)
                }

            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.d(TAG, "Download cancelled for task $taskId")
                    // If task was created, mark it as failed due to cancellation
                    if (taskId != -1L) {
                        TaskManager.completeTask(taskId, false, "下载已取消")
                    }
                    showNotification(appContext, "下载已取消", "任务已被用户停止", false)
                } else {
                    Log.e(TAG, "Download error for task $taskId", e)
                    // If task was created, fail it
                    if (taskId != -1L) {
                        TaskManager.completeTask(taskId, false, e.message ?: "未知错误")
                    }
                    showNotification(appContext, "下载出错", e.message ?: "未知错误", false)
                }
            } finally {
               // Remove job from activeJobs map regardless of success or failure
               if (taskId != -1L) {
                   activeJobs.remove(taskId)
               }
            }
        }
    }


    fun stopTask(taskId: Long) {
        val job = activeJobs.remove(taskId)
        if (job != null) {
            job.cancel()
            TaskManager.completeTask(taskId, false, "用户手动停止")
            Log.d(TAG, "Task $taskId stopped by user")
        }
    }

    private fun showNotification(context: Context, title: String, content: String, ongoing: Boolean) {
        Log.d(TAG, "showNotification: $title - $content")
        createNotificationChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Intent to open Main Activity and switch to History tab
        val intent = Intent(context, MainActivity::class.java).apply {
             flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
             putExtra("auto_download_url", "") // Empty trigger just to ensure onNewIntent is called if needed
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(if (ongoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            
        // If it's ongoing, we might want to use a progress bar, but indeterminate is fine for now
        if (ongoing) {
            builder.setProgress(0, 0, true)
        }
            
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "后台下载通知"
            val descriptionText = "显示自动下载的任务进度"
            val importance = NotificationManager.IMPORTANCE_HIGH 
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
