package com.fg.child

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
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

        val debugLog = mutableListOf<String>()
        @JvmStatic
        fun log(msg: String) {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val line = t + " " + msg
            debugLog.add(line)
            if (debugLog.size > 60) debugLog.removeAt(0)
            Log.d("FG", msg)
        }
    }

    override fun onCreate() {
        super.onCreate()
        log("Service started")
        startForeground(NOTIF_ID, buildNotification())

        childId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ID, null)
        log("childId = " + (childId ?: "NULL"))

        if (childId == null) {
            log("No childId — cannot sync")
            return
        }

        scope.launch {
            doTestWrite()
            doHeartbeat(childId!!)
            doInstalledApps(childId!!)
        }

        startListener(childId!!)
    }

    private fun startListener(id: String) {
        log("Listening to children/" + id)
        reg = db.collection("children").document(id).addSnapshotListener { snap, err ->
            if (err != null) {
                log("Listen ERR: " + err.message)
                return@addSnapshotListener
            }
            val exists = snap?.exists() ?: false
            log("Snapshot: exists=" + exists)
            scope.launch { doHeartbeat(id) }

            val data = snap?.data ?: return@addSnapshotListener
            val locked = data["locked"] as? Boolean ?: false
            @Suppress("UNCHECKED_CAST")
            val blocked = (data["blockedApps"] as? List<String>) ?: emptyList()
            AppBlockerService.blockedPkgs.clear()
            AppBlockerService.blockedPkgs.addAll(blocked)
            AppBlockerService.screenLocked = locked
            if (locked) lockScreen()
        }
    }

    // 1) TEST WRITE — visible in Firestore regardless of pairing
    private suspend fun doTestWrite() {
        try {
            val data = hashMapOf<String, Any>(
                "time" to System.currentTimeMillis(),
                "model" to (Build.MANUFACTURER + " " + Build.MODEL),
                "android" to Build.VERSION.SDK_INT,
                "childId" to (childId ?: "none")
            )
            db.collection("debug").document("child_ping")
                .set(data, SetOptions.merge()).await()
            log("Test write OK ✓")
        } catch (e: Exception) {
            log("Test write FAIL: " + e.message)
        }
    }

    // 2) HEARTBEAT — creates/updates children/{id}
    private suspend fun doHeartbeat(id: String) {
        try {
            val data = hashMapOf<String, Any>(
                "name" to Build.MODEL,
                "model" to (Build.MANUFACTURER + " " + Build.MODEL),
                "battery" to batteryPercent(),
                "lastSeen" to System.currentTimeMillis()
            )
            db.collection("children").document(id)
                .set(data, SetOptions.merge()).await()
            log("Heartbeat OK ✓")
        } catch (e: Exception) {
            log("Heartbeat FAIL: " + e.message)
        }
    }

    // 3) INSTALLED APPS — one-time list
    private suspend fun doInstalledApps(id: String) {
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
                .set(mapOf("installedApps" to apps), SetOptions.merge()).await()
            log("Apps reported: " + apps.size)
        } catch (e: Exception) {
            log("Apps FAIL: " + e.message)
        }
    }

    private fun batteryPercent(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra("level", -1) ?: -1
        val s = i?.getIntExtra("scale", 100) ?: 100
        return if (l < 0) -1 else (l * 100 / s)
    }

    private fun lockScreen() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) dpm.lockNow()
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "System Service", NotificationManager.IMPORTANCE_MIN)
                )
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

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() {
        reg?.remove()
        scope.cancel()
        log("Service destroyed")
        super.onDestroy()
    }
}