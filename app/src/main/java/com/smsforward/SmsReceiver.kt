package com.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_BODY = "extra_body"
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

        val keywords = AppConfig.getKeywords(context)
        if (keywords.isNotEmpty() && keywords.none { body.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "SMS skipped (keyword filter): $keywords")
            return
        }

        // Delegate forwarding to the foreground service instead of doing it here.
        // BroadcastReceiver is transient — the process can be killed before the
        // SMS is actually sent.  The service runs with foreground priority and
        // will reliably complete the forwarding.
        val serviceIntent = Intent(context, SmsForwardService::class.java).apply {
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_BODY, body)
        }
        try {
            SmsForwardService.startWithForward(context, serviceIntent)
            Log.i(TAG, "Delegated SMS from $sender to SmsForwardService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SmsForwardService: ${e.message}", e)
        }
    }
}
