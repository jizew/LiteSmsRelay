package com.smsforward

import android.app.Application
import android.util.Log

/**
 * Minimal Application — no heavy initialization, no DI frameworks.
 * Keeps process startup instant for low-power operation.
 */
class App : Application() {
    companion object {
        private const val TAG = "SmsForward"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "App created")
    }
}
