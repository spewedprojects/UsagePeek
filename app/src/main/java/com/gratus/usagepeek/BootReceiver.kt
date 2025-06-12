package com.gratus.usagepeek

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("overlay_active", false)) {
                context.startForegroundService(Intent(context, OverlayService::class.java))
            }
        }
    }
}
