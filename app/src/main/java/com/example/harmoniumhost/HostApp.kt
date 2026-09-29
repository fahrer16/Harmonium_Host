package com.example.harmoniumhost

import android.app.Application
import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Holds the few objects that both the activity and the service use. */
class HostApp : Application() {
    val prefs by lazy { HostPrefs(this) }
    val http: OkHttpClient by lazy { OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build() }
    val esp by lazy { EspServer(this, prefs) }

    private val pkg by lazy { packageManager.getPackageInfo(packageName, 0) }
    val versionName: String get() = pkg.versionName ?: "?"
    val installedAt: Long get() = pkg.lastUpdateTime

    companion object {
        fun of(context: Context) = context.applicationContext as HostApp
    }
}
