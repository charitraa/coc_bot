package com.example.coc_upgrade

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.Calendar

class MainActivity : FlutterActivity() {

    companion object {
        const val METHOD_CHANNEL = "com.cocbot/bot"
        const val EVENT_CHANNEL  = "com.cocbot/logs"
        const val NOTIF_CHANNEL  = "coc_bot_channel"
    }

    private var logEventSink: EventChannel.EventSink? = null

    // Receives log broadcasts from the Accessibility Service
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(CocBotAccessibilityService.EXTRA_MESSAGE) ?: return
            runOnUiThread {
                logEventSink?.success(msg)
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        createNotificationChannel()

        // Register log receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logReceiver,
            IntentFilter(CocBotAccessibilityService.ACTION_LOG)
        )

        // ── Method Channel (Flutter → Android) ──────────────────────────────
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {

                    "isAccessibilityEnabled" -> {
                        result.success(isAccessibilityEnabled())
                    }

                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(null)
                    }

                    "isCocInstalled" -> {
                        val installed = try {
                            packageManager.getPackageInfo("com.supercell.clashofclans", 0)
                            true
                        } catch (e: Exception) { false }
                        result.success(installed)
                    }

                    "startBot" -> {
                        if (!isAccessibilityEnabled()) {
                            result.error("NO_ACCESSIBILITY", "Enable Accessibility Service first", null)
                            return@setMethodCallHandler
                        }
                        sendBotCommand(CocBotAccessibilityService.ACTION_START_BOT)
                        result.success(true)
                    }

                    "stopBot" -> {
                        sendBotCommand(CocBotAccessibilityService.ACTION_STOP_BOT)
                        result.success(true)
                    }

                    "scheduleBot" -> {
                        val hour   = call.argument<Int>("hour") ?: 0
                        val minute = call.argument<Int>("minute") ?: 0
                        scheduleDailyBot(hour, minute)
                        result.success(true)
                    }

                    "cancelSchedule" -> {
                        cancelSchedule()
                        result.success(true)
                    }

                    "getBotStatus" -> {
                        result.success(mapOf(
                            "running"       to CocBotAccessibilityService.isRunning,
                            "freeBuilders"  to CocBotAccessibilityService.freeBuilders,
                            "upgradesStarted" to CocBotAccessibilityService.upgradesStarted,
                        ))
                    }

                    else -> result.notImplemented()
                }
            }

        // ── Event Channel (Android → Flutter, log stream) ───────────────────
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    logEventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    logEventSink = null
                }
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("com.example.coc_upgrade/com.example.coc_upgrade.CocBotAccessibilityService")
    }

    private fun sendBotCommand(action: String) {
        val intent = Intent(this, CocBotAccessibilityService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    // Schedule the bot using AlarmManager to fire at the given time daily
    private fun scheduleDailyBot(hour: Int, minute: Int) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, BotAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    private fun cancelSchedule() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, BotAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL,
                "CoC Bot",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "CoC upgrade bot status" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}