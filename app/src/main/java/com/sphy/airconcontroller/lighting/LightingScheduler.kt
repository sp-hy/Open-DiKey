package com.sphy.airconcontroller.lighting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.byd.AmbientLightProbe

/**
 * Process-wide ambient day/night switch. Started from [OpenDiKeyApp] / [DiKeyListenService]
 * so it keeps polling in any activity and while the listen service keeps the process alive.
 *
 * Color apply waits until [AmbientLightProbe] has a cabin reading (not clock-only), so cold
 * boot does not paint the stock day-red profile. Dial/temp restore is independent.
 */
object LightingScheduler {
    private const val TAG = "LightingScheduler"
    private const val POLL_MS = 30_000L
    private const val WAIT_POLL_MS = 2_000L
    private const val WAIT_TIMEOUT_MS = 90_000L

    @Volatile private var probe: AmbientLightProbe? = null
    @Volatile private var lastApplied: LightingPeriod? = null
    @Volatile private var pendingApply = false
    @Volatile private var waitingForCabin = false
    private var waitStartedElapsed = 0L
    private val main = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            evaluate(ctx, forceApply = false)
            main.postDelayed(this, POLL_MS)
        }
    }
    private val waitPollRunnable = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            if (!waitingForCabin && !pendingApply) return
            evaluate(ctx, forceApply = pendingApply || waitingForCabin)
            if (waitingForCabin || pendingApply) {
                main.postDelayed(this, WAIT_POLL_MS)
            }
        }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false

    fun currentPeriod(): LightingPeriod? = lastApplied

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (started) {
            evaluate(app, forceApply = pendingApply || waitingForCabin)
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
        main.removeCallbacks(waitPollRunnable)
        probe?.stopAndroidSensor()
        started = false
        waitingForCabin = false
    }

    /** @deprecated Alarms replaced by ambient-light polling; kept for receiver compatibility. */
    fun scheduleNext(context: Context, settings: com.sphy.airconcontroller.storage.AppSettings? = null) {
        start(context)
    }

    fun sync(context: Context, forceApply: Boolean = true) {
        appContext = context.applicationContext
        if (!started) {
            start(context)
            if (forceApply) evaluate(context.applicationContext, forceApply = true)
            return
        }
        evaluate(context.applicationContext, forceApply)
    }

    private fun evaluate(context: Context, forceApply: Boolean) {
        val p = probe ?: AmbientLightProbe(context).also {
            it.startAndroidSensor()
            probe = it
        }
        val resolution = p.resolvePeriod(lastApplied)
        if (!resolution.reliable) {
            if (!waitingForCabin) {
                waitingForCabin = true
                waitStartedElapsed = SystemClock.elapsedRealtime()
            }
            val waited = SystemClock.elapsedRealtime() - waitStartedElapsed
            if (waited < WAIT_TIMEOUT_MS) {
                pendingApply = true
                scheduleWaitPoll()
                Log.i(
                    TAG,
                    "Defer color apply — waiting for cabin ambient " +
                        "(${p.lastSource}: ${p.lastDetail}, ${waited}ms)",
                )
                return
            }
            Log.w(
                TAG,
                "Cabin ambient still unavailable after ${waited}ms — using clock fallback",
            )
            waitingForCabin = false
        } else if (waitingForCabin) {
            Log.i(TAG, "Cabin ambient ready via ${p.lastSource} (${p.lastDetail})")
            waitingForCabin = false
            main.removeCallbacks(waitPollRunnable)
        }

        val next = resolution.period
        val changed = next != lastApplied
        if (!forceApply && !changed && !pendingApply) return

        // Publish period before apply so liveLightingPeriod() / reconnect snapshots match.
        lastApplied = next
        val ok = OpenDiKeyApp.from(context).dikey.applyLightingPeriod(next)
        pendingApply = !ok
        if (pendingApply) scheduleWaitPoll()
        Log.i(
            TAG,
            "Apply $next via ${p.lastSource} (${p.lastDetail}) " +
                "changed=$changed force=$forceApply ok=$ok",
        )
    }

    private fun scheduleWaitPoll() {
        main.removeCallbacks(waitPollRunnable)
        main.postDelayed(waitPollRunnable, WAIT_POLL_MS)
    }
}

/** Legacy alarm receiver — re-evaluate ambient light if an old alarm still fires. */
class LightingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        LightingScheduler.sync(context, forceApply = true)
    }
}
