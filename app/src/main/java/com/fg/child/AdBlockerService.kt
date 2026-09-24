package com.fg.child

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AppBlockerService : AccessibilityService() {

    companion object {
        // Populated by ControlService from Firestore
        @JvmStatic
        val blockedPkgs = mutableSetOf<String>()

        @JvmStatic
        var screenLocked = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Always allow our own app so the game stays usable
        if (pkg == packageName) return

        // If parent has banned this package, boot the child back home
        if (blockedPkgs.contains(pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // If the whole phone is locked, kick everything home except settings
        if (screenLocked && pkg != "com.android.settings") {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onInterrupt() {}
}