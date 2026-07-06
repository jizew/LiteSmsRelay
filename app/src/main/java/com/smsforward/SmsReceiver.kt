package com.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import kotlin.concurrent.thread

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AppConfig.isEnabled(context)) return

        val target = AppConfig.getTarget(context)
        if (target.isBlank()) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.messageBody }

        val keyword = AppConfig.getKeyword(context)
        if (keyword.isNotBlank() && !body.contains(keyword, ignoreCase = true)) {
            Log.d(TAG, "SMS skipped (keyword filter): $keyword")
            return
        }

        val messageToSend = if (AppConfig.isIncludeSender(context)) {
            "[$sender] $body"
        } else {
            body
        }

        val pendingResult = goAsync()
        val wakeLock = acquireWakeLock(context)

        thread(start = true) {
            try {
                SmsForwarder.forward(context, target, messageToSend)
                Log.i(TAG, "Forwarded SMS from $sender to $target")
            } catch (e: Exception) {
                Log.e(TAG, "Forward failed: ${e.message}", e)
            } finally {
                wakeLock?.release()
                pendingResult.finish()
            }
        }
    }

    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsForward:Receiver").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }
}

object SmsForwarder {

    private const val TAG = "SmsForwarder"

    fun forward(context: Context, target: String, message: String) {
        val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(android.telephony.SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            android.telephony.SmsManager.getDefault()
        }

        try {
            val parts = sm.divideMessage(message)
            if (parts.size == 1) {
                sm.sendTextMessage(target, null, message, null, null)
            } else {
                sm.sendMultipartTextMessage(target, null, parts, null, null)
            }
            Log.i(TAG, "SMS sent to $target (${message.length} chars)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SEND_SMS permission denied: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SMS send error: ${e.message}")
            throw e
        }
    }
}
