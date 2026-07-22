package com.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import kotlin.concurrent.thread

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AppConfig.isEnabled(context)) return

        val target = AppConfig.getTarget(context)
        if (target.isBlank()) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages[0].displayOriginatingAddress
            ?: messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.messageBody }

        val keywords = AppConfig.getKeywords(context)
        if (keywords.isNotEmpty() && keywords.none { body.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "SMS skipped (keyword filter): $keywords")
            return
        }

        val messageToSend = if (AppConfig.isIncludeSender(context)) {
            "[$sender] $body"
        } else {
            body
        }

        // Forward directly in the BroadcastReceiver via goAsync().
        //
        // SmsManager.sendTextMessage() is a fast synchronous call to the
        // radio layer (typically < 100 ms).  It does NOT need a foreground
        // service to complete.  The system grants BroadcastReceiver a ~10 s
        // window via goAsync(), which is more than enough.
        //
        // The persistent foreground service (SmsForwardService) keeps the
        // process alive so the Receiver can be triggered, but the actual
        // forwarding happens here — simple and reliable regardless of
        // whether the app is in the foreground or background.

        val pendingResult = goAsync()
        val wakeLock = acquireWakeLock(context)

        thread(start = true) {
            try {
                SmsForwarder.forward(context, target, messageToSend)
                Log.i(TAG, "Forwarded SMS from $sender to $target")
            } catch (e: Exception) {
                Log.e(TAG, "Forward failed: ${e.message}", e)
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
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
