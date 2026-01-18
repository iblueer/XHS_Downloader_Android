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
                    // Log.d("XHSApplication", "App enters foreground")
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
                    // Log.d("XHSApplication", "App enters background")
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
