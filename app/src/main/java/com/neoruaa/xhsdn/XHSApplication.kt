package com.neoruaa.xhsdn

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import com.neoruaa.xhsdn.utils.NotificationHelper
import android.content.Context
import android.util.Log

class XHSApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var runningActivities = 0

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                runningActivities++
                Log.d("XHSApplication", "onActivityStarted: ${activity.javaClass.simpleName}, count=$runningActivities")
                if (runningActivities == 1) {
                    isAppInForeground = true
                    NotificationHelper.showDiagnosticNotification(
                        this@XHSApplication,
                        "监控状态", // 统一标题
                        "App 在前台，下载器保持静默" // 统一文案
                    )
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                runningActivities--
                Log.d("XHSApplication", "onActivityStopped: ${activity.javaClass.simpleName}, count=$runningActivities")
                if (runningActivities <= 0) {
                    runningActivities = 0
                    isAppInForeground = false
                    NotificationHelper.showDiagnosticNotification(
                        this@XHSApplication,
                        "监控状态", // 统一标题
                        "App 在后台，自动下载待命" // 统一文案
                    )
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        @Volatile
        var isAppInForeground: Boolean = false
            private set
    }
}
