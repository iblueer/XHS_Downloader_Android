package com.neoruaa.xhsdn.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.neoruaa.xhsdn.MainActivity
import com.neoruaa.xhsdn.R

object NotificationHelper {
    private const val CHANNEL_ID = "xhs_download_channel_v2"
    private const val DIAGNOSTIC_CHANNEL_ID = "xhs_diagnostic_channel"
    private const val DOWNLOAD_GROUP = "com.neoruaa.xhsdn.DOWNLOAD_GROUP"

    fun showDiagnosticNotification(context: Context, title: String, content: String) {
        val appContext = context.applicationContext
        createDiagnosticChannel(appContext)
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(appContext, DIAGNOSTIC_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Diagnostic is low priority to not disturb too much
            .setAutoCancel(true)
            .setGroup("diagnostic")

        notificationManager.notify(title.hashCode(), builder.build())
    }

    fun showDownloadNotification(context: Context, id: Int, title: String, content: String, ongoing: Boolean, showProgress: Boolean = true) {
        val appContext = context.applicationContext
        createDownloadChannel(appContext)
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setGroup(DOWNLOAD_GROUP)

        if (ongoing && showProgress) {
            builder.setProgress(0, 0, true)
        }

        notificationManager.notify(id, builder.build())
    }

    fun cancelNotification(context: Context, id: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(id)
    }

    private fun createDownloadChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "下载状态"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createDiagnosticChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "诊断调试"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(DIAGNOSTIC_CHANNEL_ID, name, importance)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
