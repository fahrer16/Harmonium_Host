package com.example.harmoniumhost

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings

/** The Android-level grants this app can use, and the system screens that grant them. */
object SystemAccess {

    /** This app is the default home screen (Home returns here; it starts by itself after a reboot). */
    fun isHome(c: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return c.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == c.packageName
    }

    /** Android's "Home app" picker, or the closest screen this firmware has. */
    fun openHomeSettings(c: Context) = openFirst(c,
        Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)

    /** Usage access: which app is in front (the Foreground app diagnostic). */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(c: Context) = (c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) ==
        AppOpsManager.MODE_ALLOWED

    fun openUsageAccess(c: Context) = openFirst(c, Settings.ACTION_USAGE_ACCESS_SETTINGS, Settings.ACTION_SETTINGS)

    private fun openFirst(c: Context, vararg actions: String) {
        for (action in actions) {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(c.packageManager) != null) { c.startActivity(i); return }
        }
    }
}
