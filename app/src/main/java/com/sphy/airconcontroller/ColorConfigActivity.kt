package com.sphy.airconcontroller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.lighting.LightingPeriod
import com.sphy.airconcontroller.lighting.LightingScheduler
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.ColorSettingCard
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.launch

class ColorConfigActivity : OpenDiKeyActivity() {
    private lateinit var session: DiKeySession
    private lateinit var settings: AppSettings
    private lateinit var connStatus: android.widget.TextView
    private lateinit var periodToggle: MaterialButtonToggleGroup

    private lateinit var colorLeft: ColorSettingCard
    private lateinit var colorMiddle: ColorSettingCard
    private lateinit var colorRight: ColorSettingCard
    private lateinit var colorKeys: ColorSettingCard

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applyBarRunnables = Array<Runnable?>(4) { null }
    private var suppressUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_color_config)

        session = OpenDiKeyApp.from(this).dikey
        settings = session.settings
        connStatus = findViewById(R.id.colorConnStatus)
        periodToggle = findViewById(R.id.colorPeriodToggle)

        findViewById<android.widget.ImageButton>(R.id.colorBackButton).setOnClickListener {
            finish()
        }

        colorLeft = findViewById(R.id.colorLeft)
        colorMiddle = findViewById(R.id.colorMiddle)
        colorRight = findViewById(R.id.colorRight)
        colorKeys = findViewById(R.id.colorKeys)

        colorLeft.setTitle(getString(R.string.color_left))
        colorMiddle.setTitle(getString(R.string.color_middle))
        colorRight.setTitle(getString(R.string.color_right))
        colorKeys.setTitle(getString(R.string.color_keys))
        colorKeys.setModeVisible(false)

        setupPeriodControls()
        reloadCardsFromEditingProfile()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.status.collect { connStatus.text = it }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LightingScheduler.sync(this, forceApply = false)
    }

    override fun onDestroy() {
        applyBarRunnables.forEach { it?.let(mainHandler::removeCallbacks) }
        super.onDestroy()
    }

    private fun setupPeriodControls() {
        suppressUi = true
        periodToggle.check(
            if (settings.editingLightingPeriod == LightingPeriod.NIGHT) {
                R.id.colorPeriodNight
            } else {
                R.id.colorPeriodDay
            }
        )
        suppressUi = false

        periodToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressUi) return@addOnButtonCheckedListener
            settings.editingLightingPeriod =
                if (checkedId == R.id.colorPeriodNight) LightingPeriod.NIGHT else LightingPeriod.DAY
            reloadCardsFromEditingProfile()
        }
    }

    private fun reloadCardsFromEditingProfile() {
        bindBar(colorLeft, 1)
        bindBar(colorMiddle, 2)
        bindBar(colorRight, 3)
        bindBacklight(colorKeys)
    }

    private fun bindBar(card: ColorSettingCard, bar: Int) {
        val color = settings.colorForBar(bar)
        val mode = LedMode.entries.find { it.code == settings.modeForBar(bar) } ?: LedMode.ON
        card.setModeVisible(true)
        card.setMode(mode)
        card.setRgb(color.red, color.green, color.blue)
        card.onModeChanged = { next ->
            session.applyBarColor(bar, card.red, card.green, card.blue, next.code)
        }
        card.onColorChanged = { r, g, b -> scheduleApply(bar, r, g, b, card.mode().code) }
        card.onColorCommitted = { r, g, b ->
            cancelSchedule(bar)
            session.applyBarColor(bar, r, g, b, card.mode().code)
        }
    }

    private fun bindBacklight(card: ColorSettingCard) {
        val color = settings.colorForBacklight()
        card.setRgb(color.red, color.green, color.blue)
        card.onColorChanged = { r, g, b -> scheduleApply(BACKLIGHT_SLOT, r, g, b, null) }
        card.onColorCommitted = { r, g, b ->
            cancelSchedule(BACKLIGHT_SLOT)
            session.applyBacklight(r, g, b)
        }
    }

    private fun scheduleApply(slot: Int, r: Int, g: Int, b: Int, mode: Int?) {
        cancelSchedule(slot)
        val task = Runnable {
            if (slot == BACKLIGHT_SLOT) {
                session.applyBacklight(r, g, b)
            } else {
                session.applyBarColor(slot, r, g, b, mode)
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
