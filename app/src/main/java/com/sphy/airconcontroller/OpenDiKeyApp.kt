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
        dikey = DiKeySession(this)
        dikey.start()
        DiKeyListenService.start(this)
    }

    companion object {
        fun from(context: Context): OpenDiKeyApp =
            context.applicationContext as OpenDiKeyApp
    }
}
