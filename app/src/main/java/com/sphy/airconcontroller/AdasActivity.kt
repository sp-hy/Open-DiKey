package com.sphy.airconcontroller

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import com.sphy.airconcontroller.byd.BydAdasController
import com.sphy.airconcontroller.storage.AdasCustomProfile
import com.sphy.airconcontroller.storage.AdasEditMode
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ADAS settings that Shark typically clears after a power cycle. */
class AdasActivity : OpenDiKeyActivity() {
    private lateinit var adas: BydAdasController
    private lateinit var settings: AppSettings
    private lateinit var statusText: TextView
    private lateinit var modeToggle: MaterialButtonToggleGroup
    private lateinit var applyButton: Button

    private lateinit var elkaOff: Button
    private lateinit var elkaOn: Button
    private lateinit var ldaOff: Button
    private lateinit var ldaWarning: Button
    private lateinit var ldaPrevent: Button
    private lateinit var ldaBoth: Button
    private lateinit var aebOff: Button
    private lateinit var aebOn: Button
    private lateinit var dmsOff: Button
    private lateinit var dmsOn: Button

    private var bound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adas)

        adas = BydAdasController(this)
        settings = AppSettings(this)
        statusText = findViewById(R.id.adasStatusText)
        modeToggle = findViewById(R.id.adasModeToggle)
        applyButton = findViewById(R.id.adasApplyButton)
        elkaOff = findViewById(R.id.adasElkaOffButton)
        elkaOn = findViewById(R.id.adasElkaOnButton)
        ldaOff = findViewById(R.id.adasLdaOffButton)
        ldaWarning = findViewById(R.id.adasLdaWarningButton)
        ldaPrevent = findViewById(R.id.adasLdaPreventButton)
        ldaBoth = findViewById(R.id.adasLdaBothButton)
        aebOff = findViewById(R.id.adasAebOffButton)
        aebOn = findViewById(R.id.adasAebOnButton)
        dmsOff = findViewById(R.id.adasDmsOffButton)
        dmsOn = findViewById(R.id.adasDmsOnButton)

        findViewById<android.widget.ImageButton>(R.id.adasBackButton).setOnClickListener {
            finish()
        }

        modeToggle.check(
            if (settings.adasEditMode == AdasEditMode.CUSTOM) {
                R.id.adasModeCustom
            } else {
                R.id.adasModeDefault
            },
        )
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.adasEditMode =
                if (checkedId == R.id.adasModeCustom) AdasEditMode.CUSTOM else AdasEditMode.DEFAULT
            refresh()
        }

        elkaOff.setOnClickListener { onElka(false) }
        elkaOn.setOnClickListener { onElka(true) }
        ldaOff.setOnClickListener { onLda(BydAdasController.LaneDepartureMode.OFF) }
        ldaWarning.setOnClickListener { onLda(BydAdasController.LaneDepartureMode.WARNING) }
        ldaPrevent.setOnClickListener { onLda(BydAdasController.LaneDepartureMode.PREVENT) }
        ldaBoth.setOnClickListener { onLda(BydAdasController.LaneDepartureMode.BOTH) }
        aebOff.setOnClickListener { onAeb(false) }
        aebOn.setOnClickListener { onAeb(true) }
        dmsOff.setOnClickListener { onDms(false) }
        dmsOn.setOnClickListener { onDms(true) }
        applyButton.setOnClickListener { applyCustomProfile() }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    private fun isCustom(): Boolean = settings.adasEditMode == AdasEditMode.CUSTOM

    private fun onElka(enabled: Boolean) {
        if (isCustom()) {
            updateCustom { it.copy(elka = enabled) }
        } else {
            markOnOff(elkaOff, elkaOn, enabled)
            runLive(getString(R.string.adas_elka_title)) {
                adas.setEmergencyLaneKeepAssist(enabled)
            }
        }
    }

    private fun onLda(mode: BydAdasController.LaneDepartureMode) {
        if (isCustom()) {
            updateCustom { it.copy(laneDeparture = mode.name) }
        } else {
            markLane(mode)
            runLive(getString(R.string.adas_lda_title)) {
                adas.setLaneDepartureAssist(mode)
            }
        }
    }

    private fun onAeb(enabled: Boolean) {
        if (isCustom()) {
            updateCustom { it.copy(aeb = enabled) }
        } else {
            markOnOff(aebOff, aebOn, enabled)
            runLive(getString(R.string.adas_aeb_title)) {
                adas.setAutomaticEmergencyBraking(enabled)
            }
        }
    }

    private fun onDms(enabled: Boolean) {
        if (isCustom()) {
            updateCustom { it.copy(dms = enabled) }
        } else {
            markOnOff(dmsOff, dmsOn, enabled)
            runLive(getString(R.string.adas_dms_title)) {
                adas.setDriverMonitoringCamera(enabled)
            }
        }
    }

    private fun updateCustom(transform: (AdasCustomProfile) -> AdasCustomProfile) {
        settings.saveAdasCustomProfile(transform(settings.adasCustomProfile()))
        renderCustom(settings.adasCustomProfile())
    }

    private fun runLive(label: String, action: () -> BydAdasController.CommandResult) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            Snackbar.make(
                statusText,
                if (result.success) {
                    getString(R.string.command_ok, label)
                } else {
                    getString(R.string.command_fail, label, result.detail)
                },
                Snackbar.LENGTH_LONG,
            ).show()
            // Only re-sync from HAL on failure (optimistic UI already flipped on success).
            if (!result.success) refresh()
        }
    }

    private fun applyCustomProfile() {
        val profile = settings.adasCustomProfile()
        val lda = laneMode(profile.laneDeparture)
        lifecycleScope.launch {
            applyButton.isEnabled = false
            val results = withContext(Dispatchers.IO) {
                listOf(
                    getString(R.string.adas_elka_title) to adas.setEmergencyLaneKeepAssist(profile.elka),
                    getString(R.string.adas_lda_title) to adas.setLaneDepartureAssist(lda),
                    getString(R.string.adas_aeb_title) to adas.setAutomaticEmergencyBraking(profile.aeb),
                    getString(R.string.adas_dms_title) to adas.setDriverMonitoringCamera(profile.dms),
                )
            }
            applyButton.isEnabled = true
            val failed = results.filter { !it.second.success }
            val message = if (failed.isEmpty()) {
                getString(R.string.adas_apply_ok)
            } else {
                getString(
                    R.string.adas_apply_partial,
                    failed.joinToString { "${it.first}: ${it.second.detail}" },
                )
            }
            Snackbar.make(statusText, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        val custom = isCustom()
        applyButton.visibility = if (custom) View.VISIBLE else View.GONE

        if (custom) {
            statusText.text = getString(R.string.adas_subtitle_custom)
            setControlsEnabled(true)
            renderCustom(settings.adasCustomProfile())
            // Still probe bind in background so Apply can work; ignore live values for UI.
            lifecycleScope.launch {
                val snap = withContext(Dispatchers.IO) { adas.snapshot() }
                bound = snap.diPilotBound || snap.sdkInjected
                if (!snap.sdkInjected) {
                    statusText.text = getString(R.string.adas_status_no_sdk)
                } else if (!snap.diPilotBound && snap.bindError != null) {
                    statusText.text = getString(R.string.adas_status_unbound, snap.bindError)
                }
                setControlsEnabled(bound)
                applyButton.isEnabled = bound
            }
            return
        }

        statusText.text = getString(R.string.adas_subtitle_default)
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { adas.snapshot() }
            bound = snap.diPilotBound || snap.sdkInjected
            statusText.text = when {
                !snap.sdkInjected -> getString(R.string.adas_status_no_sdk)
                !snap.diPilotBound && snap.aeb == null && snap.driverMonitor == null ->
                    getString(R.string.adas_status_unbound, snap.bindError ?: "—")
                else -> getString(R.string.adas_subtitle_default)
            }
            setControlsEnabled(bound)
            markOnOff(elkaOff, elkaOn, snap.elka)
            markOnOff(aebOff, aebOn, snap.aeb)
            markOnOff(dmsOff, dmsOn, snap.driverMonitor)
            markLane(snap.laneDeparture)
        }
    }

    private fun renderCustom(profile: AdasCustomProfile) {
        markOnOff(elkaOff, elkaOn, profile.elka)
        markOnOff(aebOff, aebOn, profile.aeb)
        markOnOff(dmsOff, dmsOn, profile.dms)
        markLane(laneMode(profile.laneDeparture))
    }

    private fun laneMode(name: String): BydAdasController.LaneDepartureMode =
        BydAdasController.LaneDepartureMode.entries.firstOrNull { it.name == name }
            ?: BydAdasController.LaneDepartureMode.BOTH

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(
            elkaOff, elkaOn, ldaOff, ldaWarning, ldaPrevent, ldaBoth,
            aebOff, aebOn, dmsOff, dmsOn,
        ).forEach { it.isEnabled = enabled }
    }

    private fun markOnOff(off: Button, on: Button, enabled: Boolean?) {
        off.alpha = if (enabled == false) 1f else 0.55f
        on.alpha = if (enabled == true) 1f else 0.55f
    }

    private fun markLane(mode: BydAdasController.LaneDepartureMode?) {
        val buttons = listOf(
            BydAdasController.LaneDepartureMode.OFF to ldaOff,
            BydAdasController.LaneDepartureMode.WARNING to ldaWarning,
            BydAdasController.LaneDepartureMode.PREVENT to ldaPrevent,
            BydAdasController.LaneDepartureMode.BOTH to ldaBoth,
        )
        buttons.forEach { (m, btn) ->
            btn.alpha = if (mode == m) 1f else 0.55f
        }
    }
}
