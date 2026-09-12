package com.sphy.airconcontroller.boot

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Translucent one-shot entry used by [com.sphy.airconcontroller.daemon.DiKeyAccDaemon]
 * to clear Android's `stopped=true` after DiLink force-stops the app, then bring up
 * [DiKeyListenService] without leaving a visible UI.
 */
class DiKeyWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "wake activity — starting listener")
        DiKeyListenService.start(this)
        DiKeyAutostart.scheduleRestarts(this)
        finish()
    }

    companion object {
        private const val TAG = "DiKeyWake"
    }
}
