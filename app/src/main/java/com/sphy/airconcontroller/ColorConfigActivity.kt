package com.sphy.airconcontroller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.ColorSettingCard
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.launch

class ColorConfigActivity : OpenDiKeyActivity() {
    private lateinit var session: DiKeySession
    private lateinit var settings: AppSettings
    private lateinit var connStatus: android.widget.TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applyBarRunnables = Array<Runnable?>(4) { null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_color_config)

        session = OpenDiKeyApp.from(this).dikey
        settings = session.settings
        connStatus = findViewById(R.id.colorConnStatus)

        findViewById<android.widget.ImageButton>(R.id.colorBackButton).setOnClickListener {
            finish()
        }

        val colorLeft = findViewById<ColorSettingCard>(R.id.colorLeft)
        val colorMiddle = findViewById<ColorSettingCard>(R.id.colorMiddle)
        val colorRight = findViewById<ColorSettingCard>(R.id.colorRight)
        val colorKeys = findViewById<ColorSettingCard>(R.id.colorKeys)

        colorLeft.setTitle(getString(R.string.color_left))
        colorMiddle.setTitle(getString(R.string.color_middle))
        colorRight.setTitle(getString(R.string.color_right))
        colorKeys.setTitle(getString(R.string.color_keys))

        bindBar(colorLeft, 1)
        bindBar(colorMiddle, 2)
        bindBar(colorRight, 3)
        bindBacklight(colorKeys)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.status.collect { connStatus.text = it }
            }
        }
    }

    override fun onDestroy() {
        applyBarRunnables.forEach { it?.let(mainHandler::removeCallbacks) }
        super.onDestroy()
    }

    private fun bindBar(card: ColorSettingCard, bar: Int) {
        val color = settings.colorForBar(bar)
        card.setRgb(color.red, color.green, color.blue)
        card.onColorChanged = { r, g, b -> scheduleApply(bar, r, g, b) }
        card.onColorCommitted = { r, g, b ->
            cancelSchedule(bar)
            session.applyBarColor(bar, r, g, b)
        }
    }

    private fun bindBacklight(card: ColorSettingCard) {
        val color = settings.colorForBacklight()
        card.setRgb(color.red, color.green, color.blue)
        card.onColorChanged = { r, g, b -> scheduleApply(BACKLIGHT_SLOT, r, g, b) }
        card.onColorCommitted = { r, g, b ->
            cancelSchedule(BACKLIGHT_SLOT)
            session.applyBacklight(r, g, b)
        }
    }

    private fun scheduleApply(slot: Int, r: Int, g: Int, b: Int) {
        cancelSchedule(slot)
        val task = Runnable {
            if (slot == BACKLIGHT_SLOT) {
                session.applyBacklight(r, g, b)
            } else {
                session.applyBarColor(slot, r, g, b)
            }
        }
        applyBarRunnables[slotIndex(slot)] = task
        mainHandler.postDelayed(task, APPLY_DEBOUNCE_MS)
    }

    private fun cancelSchedule(slot: Int) {
        applyBarRunnables[slotIndex(slot)]?.let(mainHandler::removeCallbacks)
        applyBarRunnables[slotIndex(slot)] = null
    }

    private fun slotIndex(slot: Int) = if (slot == BACKLIGHT_SLOT) 3 else slot - 1

    companion object {
        private const val APPLY_DEBOUNCE_MS = 80L
        private const val BACKLIGHT_SLOT = 0
    }
}
