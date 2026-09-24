package com.fg.child

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        val i = Intent(context, ControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        val i = Intent(context, ControlService::class.java)
        context.stopService(i)
    }
}