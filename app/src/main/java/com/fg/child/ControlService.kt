package com.fg.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ControlService : Service() {

    private val db = FirebaseFirestore.getInstance()
    private var reg: ListenerRegistration? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var childId: String? = null

    companion object {
        const val PREFS = "fg_prefs"
        const val KEY_ID = "childId"
        const val CHANNEL = "fg_channel"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        childId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ID, null)
        childId?.let { listen(it) }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(
                    CHANNEL, "System Service", NotificationManager.IMPORTANCE_MIN
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun listen(id: String) {
        reg = db.collection("children").document(id)
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                val data = snap?.data ?: return@addSnapshotListener

                val locked = data["locked"] as? Boolean ?: false
                @Suppress("UNCHECKED_CAST")
                val blocked = (data["blockedApps"] as? List<String>) ?: emptyList()

                // Update in-memory blocker state
                AppBlockerService.blockedPkgs.clear()
                AppBlockerService.blockedPkgs.addAll(blocked)
                AppBlockerService.screenLocked = locked

                // If parent pressed Lock → immediately lock the screen
                if (locked) lockScreenNow()

                // Report back: battery, model, lastSeen
                scope.launch { reportHeartbeat(id, locked) }

                // Report installed apps once (if empty in Firestore)
                val installed = data["installedApps"] as? List<*>
                if (installed.isNullOrEmpty()) {
                    scope.launch { reportInstalledApps(id) }
                }
            }
    }

    private suspend fun reportHeartbeat(id: String, locked: Boolean) {
        try {
            db.collection("children").document(id).update(
                mapOf(
                    "battery" to getBatteryPercent(),
                    "lastSeen" to System.currentTimeMillis(),
                    "model" to (Build.MANUFACTURER + " " + Build.MODEL),
                    "locked" to locked
                )
            ).await()
        } catch (_: Exception) {}
    }

    private suspend fun reportInstalledApps(id: String) {
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map {
                    mapOf(
                        "pkg" to it.packageName,
                        "name" to pm.getApplicationLabel(it).toString()
                    )
                }
            db.collection("children").document(id)
                .update("installedApps", apps).await()
        } catch (_: Exception) {}
    }

    private fun getBatteryPercent(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra("level", -1) ?: -1
        val scale = i?.getIntExtra("scale", 100) ?: 100
        return if (level < 0) -1 else (level * 100 / scale)
    }

    private fun lockScreenNow() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        reg?.remove()
        scope.cancel()
        super.onDestroy()
    }
}