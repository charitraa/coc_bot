package com.example.coc_upgrade

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * Receives the daily alarm and triggers the bot service, then re-arms
 * itself for the next day. Also handles BOOT_COMPLETED to re-register the
 * alarm after reboot (AlarmManager alarms don't survive a reboot).
 */
class BotAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_FIRE = "com.cocbot.ALARM_FIRE"
        private const val PREFS_NAME = "CocBotPrefs"
        private const val PREF_SCHEDULED = "scheduled"
        private const val PREF_HOUR = "sched_hour"
        private const val PREF_MINUTE = "sched_minute"
        private const val REQUEST_CODE = 0

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, BotAlarmReceiver::class.java).apply {
                action = ACTION_FIRE
            }
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Arms a one-shot exact alarm for the next occurrence of hour:minute
        // (today if it hasn't passed yet, else tomorrow) and persists the
        // chosen time so the alarm survives reboots and can re-arm itself
        // daily. setRepeating(RTC_WAKEUP, ...) is inexact and gets deferred
        // by minutes/hours under Doze and OEM battery management, so each
        // firing schedules the next one explicitly instead of relying on a
        // repeating alarm.
        fun scheduleNext(context: Context, hour: Int, minute: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_SCHEDULED, true)
                .putInt(PREF_HOUR, hour)
                .putInt(PREF_MINUTE, minute)
                .apply()

            val now = Calendar.getInstance()
            Log.d("CocBot", "🕐 Scheduling $hour:$minute — device now is ${now.time}")
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent(context))
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent(context))
            }
            Log.d("CocBot", "⏰ Next run scheduled for ${cal.time}")
        }

        fun cancel(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_SCHEDULED, false)
                .apply()
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_SCHEDULED, false)) return

        val hour = prefs.getInt(PREF_HOUR, 0)
        val minute = prefs.getInt(PREF_MINUTE, 0)

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("CocBot", "🔁 Re-arming schedule after boot ($hour:$minute)")
            scheduleNext(context, hour, minute)
            return
        }

        Log.d("CocBot", "⏰ Alarm fired — starting bot")
        val serviceIntent = Intent(context, CocBotAccessibilityService::class.java).apply {
            action = CocBotAccessibilityService.ACTION_START_BOT
        }
        context.startService(serviceIntent)

        // setExactAndAllowWhileIdle is one-shot — arm tomorrow's run now.
        scheduleNext(context, hour, minute)
    }
}
