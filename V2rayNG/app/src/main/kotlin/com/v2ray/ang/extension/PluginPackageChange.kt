package com.v2ray.ang.extension

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Listen for system package changes that can invalidate native plugin discovery.
 * Register once per application process; callbacks only invalidate the cached list.
 */
fun Context.listenForPackageChanges(onPackageChange: () -> Unit): BroadcastReceiver {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_CHANGED,
                Intent.ACTION_PACKAGE_REPLACED -> onPackageChange()
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(Intent.ACTION_PACKAGE_CHANGED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addDataScheme("package")
    }
    // ACTION_PACKAGE_* are protected broadcasts sent by the operating system.
    applicationContext.registerReceiver(receiver, filter)
    return receiver
}

/** PackageInfo lookup compatible with both modern and legacy Android SDK APIs. */
@SuppressLint("DeprecatedGetPackageInfo")
@Suppress("DEPRECATION")
fun Context.getPackageInfo(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        packageManager.getPackageInfo(packageName, 0)
    }
