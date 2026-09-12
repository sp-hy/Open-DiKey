package com.sphy.airconcontroller

import android.app.Application
import android.content.Context
import com.sphy.airconcontroller.boot.DiKeyListenService
import com.sphy.airconcontroller.dikey.DiKeySession

class OpenDiKeyApp : Application() {
    lateinit var dikey: DiKeySession
        private set

    override fun onCreate() {
        super.onCreate()
        // Pin ADB on as early as possible (Settings.Global; no adbd required).
        com.sphy.airconcontroller.adb.AdbKeepAlive.ensure(this, "app-create", viaShell = false)
        dikey = DiKeySession(this)
        dikey.start()
        com.sphy.airconcontroller.lighting.LightingScheduler.scheduleNext(this)
        DiKeyListenService.start(this)
    }

    companion object {
        fun from(context: Context): OpenDiKeyApp =
            context.applicationContext as OpenDiKeyApp
    }
}
