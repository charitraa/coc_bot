package com.example.coc_upgrade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the daily alarm and triggers the bot service.
 * Also handles BOOT_COMPLETED to re-register the alarm after reboot.
 */
class BotAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("CocBot", "⏰ Alarm fired — starting bot")
        val serviceIntent = Intent(context, CocBotAccessibilityService::class.java).apply {
            action = CocBotAccessibilityService.ACTION_START_BOT
        }
        context.startService(serviceIntent)
    }
}