package com.family.tvtimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 电视重启或 App 更新后，如果之前开着守护，就自动恢复。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.role == "tv" && prefs.guardRunning) GuardService.start(context)
    }
}
