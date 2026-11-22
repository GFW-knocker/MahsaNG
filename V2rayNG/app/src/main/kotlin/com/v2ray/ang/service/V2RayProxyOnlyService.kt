package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference

class V2RayProxyOnlyService : Service(), ServiceControl {
    override fun onCreate() {
        super.onCreate()
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy Android O+ startForegroundService() contract before any early return.
        V2RayServiceManager.ensureForeground(this)
        V2RayServiceManager.startV2rayPoint()
        return START_STICKY
    }

    override fun onDestroy() {
        // Atomic owner check prevents a stale proxy service from stopping
        // a newer VPN or proxy session during Android service handoff.
        V2RayServiceManager.stopV2rayPointIfOwner(this)
        super.onDestroy()
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        stopSelf()
    }

    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase,  Utils.getLocale(newBase))
        }
        super.attachBaseContext(context)
    }
}
