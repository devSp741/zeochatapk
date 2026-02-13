package com.example.zeochat

import android.app.Application
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel



class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Verbose Logging set to help debug issues, remove before releasing your app.
        OneSignal.Debug.logLevel = LogLevel.VERBOSE

        // OneSignal Initialization
        val sharedPref = getSharedPreferences("ZeoChatPrefs", MODE_PRIVATE)
        val savedAppId = sharedPref.getString("onesignal_app_id", "")

        if (!savedAppId.isNullOrEmpty()) {
            OneSignal.initWithContext(this, savedAppId)
        }
    }
}
