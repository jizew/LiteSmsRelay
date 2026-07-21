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
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_BODY = "extra_body"
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

        // ── Strategy: try foreground service first, fall back to goAsync ──
        //
        // Android 12+ (API 31) restricts starting foreground services from
        // the background.  SMS_RECEIVED is NOT on the exemption list, so
        // startForegroundService() throws
        // ForegroundServiceStartNotAllowedException when the app is not
        // visible.
        //
        // However, if our persistent foreground service is already running
        // (started by App.onCreate / BootReceiver / MainActivity), the
        // process counts as "foreground" and the call succeeds — this is
        // the happy path.
        //
        // When the service has been killed (aggressive OEM, Force Stop,
        // etc.), we fall back to goAsync() + WakeLock and forward directly
        // in the BroadcastReceiver's short-lived window.  This is less
        // reliable but still works for SmsManager.sendTextMessage() which
        // is a fast synchronous call to the radio layer.

        val serviceIntent = Intent(context, SmsForwardService::class.java).apply {
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_BODY, body)
            action = "ACTION_FORWARD_SMS"
        }

        val serviceStarted = tryStartForegroundService(context, serviceIntent)

        if (serviceStarted) {
            Log.i(TAG, "Delegated SMS from $sender to SmsForwardService")
        } else {
            // Fallback: forward directly in the BroadcastReceiver via goAsync
            Log.w(TAG, "Foreground service unavailable, forwarding via goAsync fallback")
            forwardViaGoAsync(context, target, messageToSend, sender)
        }
    }

    /**
     * Attempt to start the foreground service.  Returns true on success.
     * Catches ForegroundServiceStartNotAllowedException (Android 12+)
     * and generic IllegalStateException on older versions.
     */
    private fun tryStartForegroundService(context: Context, intent: Intent): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: IllegalStateException) {
            // Android 12+: ForegroundServiceStartNotAllowedException
            // Android 8-11: IllegalStateException if not in foreground
            Log.w(TAG, "Cannot start foreground service from background: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting foreground service: ${e.message}")
            false
        }
    }

    /**
     * Fallback path: use goAsync() + WakeLock to forward the SMS directly
     * inside the BroadcastReceiver.  The system grants the Receiver a
     * ~10 s window; SmsManager.sendTextMessage() is a fast synchronous
     * call to the radio layer and typically completes well within that.
     */
    private fun forwardViaGoAsync(
        context: Context,
        target: String,
        message: String,
        sender: String
    ) {
        val pendingResult = goAsync()
        val wakeLock = acquireWakeLock(context)

        thread(start = true) {
            try {
                SmsForwarder.forward(context, target, message)
                Log.i(TAG, "Forwarded SMS from $sender to $target (goAsync fallback)")
            } catch (e: Exception) {
                Log.e(TAG, "Forward failed (goAsync fallback): ${e.message}", e)
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
