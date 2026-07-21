package com.smsforward

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlin.concurrent.thread

class SmsForwardService : Service() {

    companion object {
        private const val TAG = "SmsForwardService"
        private const val CHANNEL_ID = "sms_forward_persistent"
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) {
            val intent = Intent(ctx, SmsForwardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /**
         * Start the service with a forward action carrying SMS data.
         * This is called by SmsReceiver so the forwarding runs inside
         * the foreground-protected service instead of the transient
         * BroadcastReceiver.
         */
        fun startWithForward(ctx: Context, intent: Intent) {
            intent.setClass(ctx, SmsForwardService::class.java)
            intent.action = "ACTION_FORWARD_SMS"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SmsForwardService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
        Log.i(TAG, "Foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure foreground state on every onStartCommand (system may recreate)
        startForegroundCompat()

        if (intent?.action == "ACTION_FORWARD_SMS") {
            val sender = intent.getStringExtra(SmsReceiver.EXTRA_SENDER) ?: "unknown"
            val body = intent.getStringExtra(SmsReceiver.EXTRA_BODY) ?: ""

            if (body.isNotEmpty()) {
                // Acquire a wake lock so the CPU stays on during the send
                acquireWakeLock()
                thread(start = true) {
                    try {
                        val target = AppConfig.getTarget(this)
                        if (target.isBlank()) {
                            Log.w(TAG, "Target is blank, skipping forward")
                            return@thread
                        }
                        val messageToSend = if (AppConfig.isIncludeSender(this)) {
                            "[$sender] $body"
                        } else {
                            body
                        }
                        SmsForwarder.forward(this, target, messageToSend)
                        Log.i(TAG, "Forwarded SMS from $sender to $target")
                    } catch (e: Exception) {
                        Log.e(TAG, "Forward failed: ${e.message}", e)
                    } finally {
                        releaseWakeLock()
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        Log.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    // ── Foreground compat ──────────────────────────────────────────────

    /**
     * Android 14+ requires the foregroundServiceType parameter when the
     * manifest declares a foregroundServiceType.  Omitting it causes a
     * SecurityException and the service is killed — this was the root
     * cause of "cannot forward in background on Android 14+".
     */
    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    // ── Wake lock ──────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsForward:Service").apply {
            setReferenceCounted(false)
            acquire(30_000L) // 30s should be plenty for SmsManager
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ── Notification ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_sms)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
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
