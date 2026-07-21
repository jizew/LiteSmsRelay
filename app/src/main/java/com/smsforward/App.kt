package com.smsforward

import android.app.Application
import android.util.Log

/**
 * Minimal Application — no heavy initialization, no DI frameworks.
 * Keeps process startup instant for low-power operation.
 *
 * On every process creation we ensure the foreground service is running
 * (if the user has enabled forwarding).  This covers the case where the
 * system kills the process and later re-creates it just to deliver an
 * SMS broadcast — without this, the service would be dead and the
 * process would have no foreground protection.
 */
class App : Application() {
    companion object {
        private const val TAG = "SmsForward"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "App created")
        if (AppConfig.isReady(this)) {
            try {
                SmsForwardService.start(this)
                Log.i(TAG, "Foreground service ensured on app start")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service on app start: ${e.message}")
            }
        }
    }
}
