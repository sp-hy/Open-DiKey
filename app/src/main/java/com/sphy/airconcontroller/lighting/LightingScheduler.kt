package com.sphy.airconcontroller.lighting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.byd.AmbientLightProbe

/**
 * Polls ambient light (vehicle sensor preferred) and applies day/night lighting profiles.
 */
object LightingScheduler {
    private const val TAG = "LightingScheduler"
    private const val POLL_MS = 45_000L

    @Volatile private var probe: AmbientLightProbe? = null
    @Volatile private var lastApplied: LightingPeriod? = null
    private val main = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            evaluate(ctx, forceApply = false)
            main.postDelayed(this, POLL_MS)
        }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false

    fun currentPeriod(): LightingPeriod? = lastApplied

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (started) {
            evaluate(app, forceApply = true)
            return
        }
        started = true
        val p = AmbientLightProbe(app)
        p.startAndroidSensor()
        probe = p
        evaluate(app, forceApply = true)
        main.removeCallbacks(pollRunnable)
        main.postDelayed(pollRunnable, POLL_MS)
    }

    fun stop() {
        main.removeCallbacks(pollRunnable)
        probe?.stopAndroidSensor()
        started = false
    }

    /** @deprecated Alarms replaced by ambient-light polling; kept for receiver compatibility. */
    fun scheduleNext(context: Context, settings: com.sphy.airconcontroller.storage.AppSettings? = null) {
        start(context)
    }

    fun sync(context: Context, forceApply: Boolean = true) {
        start(context)
        evaluate(context.applicationContext, forceApply)
    }

    private fun evaluate(context: Context, forceApply: Boolean) {
        val p = probe ?: AmbientLightProbe(context).also {
            it.startAndroidSensor()
            probe = it
        }
        val next = p.resolvePeriod(lastApplied)
        if (forceApply || next != lastApplied) {
            Log.i(TAG, "Apply $next via ${p.lastSource} (${p.lastDetail})")
            OpenDiKeyApp.from(context).dikey.applyLightingPeriod(next)
            lastApplied = next
        }
    }
}

/** Legacy alarm receiver — re-evaluate ambient light if an old alarm still fires. */
class LightingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        LightingScheduler.sync(context, forceApply = true)
    }
}
