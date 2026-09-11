package com.sphy.airconcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.sphy.airconcontroller.dikey.ButtonMapSlot
import com.sphy.airconcontroller.dikey.DiKeyButtonCatalog
import com.sphy.airconcontroller.dikey.DialMapSlot
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.ButtonAction
import com.sphy.airconcontroller.ui.OpenDiKeyActivity

/**
 * Configure an Android Intent for a button or dial slot.
 * Apps declare intent-filters; we fire matching startActivity / sendBroadcast.
 */
class IntentEditorActivity : OpenDiKeyActivity() {
    private lateinit var settings: AppSettings
    private var buttonId: Int = 0
    private var slot: ButtonMapSlot = ButtonMapSlot.UP_CLICK
    private var dialSlot: DialMapSlot? = null

    private lateinit var presetSpinner: Spinner
    private lateinit var deliveryActivity: RadioButton
    private lateinit var deliveryBroadcast: RadioButton
    private lateinit var labelField: TextInputEditText
    private lateinit var actionField: TextInputEditText
    private lateinit var dataField: TextInputEditText
    private lateinit var mimeField: TextInputEditText
    private lateinit var packageField: TextInputEditText
    private lateinit var componentField: TextInputEditText

    private var applyingPreset = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intent_editor)

        settings = OpenDiKeyApp.from(this).dikey.settings
        dialSlot = DialMapSlot.fromStorageKey(intent.getStringExtra(EXTRA_DIAL_SLOT).orEmpty())
        buttonId = intent.getIntExtra(EXTRA_BUTTON_ID, 0)
        slot = ButtonMapSlot.fromStorageKey(intent.getStringExtra(EXTRA_SLOT).orEmpty())
            ?: ButtonMapSlot.UP_CLICK

        findViewById<TextView>(R.id.intentSubtitle).text = when {
            dialSlot != null -> getString(
                R.string.map_action_dial_subtitle_fmt,
                if (dialSlot!!.side == "LEFT") getString(R.string.dial_left) else getString(R.string.dial_right),
                getString(dialSlot!!.titleRes)
            )
            buttonId in DiKeyButtonCatalog.ids ->
                getString(R.string.map_action_subtitle_fmt, buttonId, getString(slot.titleRes))
            else -> getString(R.string.intent_editor_title)
        }

        findViewById<ImageButton>(R.id.intentBackButton).setOnClickListener { finish() }

        presetSpinner = findViewById(R.id.intentPresetSpinner)
        deliveryActivity = findViewById(R.id.intentDeliveryActivity)
        deliveryBroadcast = findViewById(R.id.intentDeliveryBroadcast)
        labelField = findViewById(R.id.intentLabel)
        actionField = findViewById(R.id.intentAction)
        dataField = findViewById(R.id.intentData)
        mimeField = findViewById(R.id.intentMime)
        packageField = findViewById(R.id.intentPackage)
        componentField = findViewById(R.id.intentComponent)

        val presets = IntentPreset.entries
        presetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            presets.map { getString(it.titleRes) }
        )
        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (applyingPreset) return
                applyPreset(presets[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        findViewById<Button>(R.id.intentSaveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.intentClearButton).setOnClickListener {
            clearMapping()
            setResult(RESULT_OK, resultIntent(cleared = true))
            finish()
        }

        loadExisting()
    }

    private fun loadExisting() {
        val existing = currentMapping() as? ButtonAction.RunIntent
        if (existing == null) {
            applyingPreset = true
            presetSpinner.setSelection(0)
            applyingPreset = false
            applyPreset(IntentPreset.CUSTOM)
            return
        }
        applyingPreset = true
        presetSpinner.setSelection(0)
        applyingPreset = false
        if (existing.delivery == ButtonAction.RunIntent.DELIVERY_BROADCAST) {
            deliveryBroadcast.isChecked = true
        } else {
            deliveryActivity.isChecked = true
        }
        labelField.setText(existing.label)
        actionField.setText(existing.action)
        dataField.setText(existing.dataUri)
        mimeField.setText(existing.mimeType)
        packageField.setText(existing.packageName)
        componentField.setText(existing.component)
    }

    private fun applyPreset(preset: IntentPreset) {
        when (preset) {
            IntentPreset.CUSTOM -> Unit
            IntentPreset.VIEW_URL -> {
                deliveryActivity.isChecked = true
                if (labelField.text.isNullOrBlank()) labelField.setText(getString(R.string.intent_preset_view_url))
                actionField.setText(Intent.ACTION_VIEW)
                if (dataField.text.isNullOrBlank()) dataField.setText("https://")
                mimeField.setText("")
            }
            IntentPreset.SETTINGS -> {
                deliveryActivity.isChecked = true
                labelField.setText(getString(R.string.intent_preset_settings))
                actionField.setText(android.provider.Settings.ACTION_SETTINGS)
                dataField.setText("")
                mimeField.setText("")
                packageField.setText("")
                componentField.setText("")
            }
            IntentPreset.DIAL -> {
                deliveryActivity.isChecked = true
                labelField.setText(getString(R.string.intent_preset_dial))
                actionField.setText(Intent.ACTION_DIAL)
                if (dataField.text.isNullOrBlank()) dataField.setText("tel:")
                mimeField.setText("")
            }
            IntentPreset.BROADCAST -> {
                deliveryBroadcast.isChecked = true
                if (labelField.text.isNullOrBlank()) {
                    labelField.setText(getString(R.string.intent_preset_broadcast))
                }
                if (actionField.text.isNullOrBlank()) {
                    actionField.setText("com.sphy.airconcontroller.DIKEY_BUTTON")
                }
            }
        }
    }

    private fun save() {
        val action = actionField.text?.toString()?.trim().orEmpty()
        if (action.isEmpty()) {
            Snackbar.make(actionField, R.string.intent_action_required, Snackbar.LENGTH_SHORT).show()
            return
        }
        val delivery = if (deliveryBroadcast.isChecked) {
            ButtonAction.RunIntent.DELIVERY_BROADCAST
        } else {
            ButtonAction.RunIntent.DELIVERY_ACTIVITY
        }
        val label = labelField.text?.toString()?.trim().orEmpty().ifBlank { action }
        val mapping = ButtonAction.RunIntent(
            label = label,
            delivery = delivery,
            action = action,
            dataUri = dataField.text?.toString()?.trim().orEmpty(),
            mimeType = mimeField.text?.toString()?.trim().orEmpty(),
            packageName = packageField.text?.toString()?.trim().orEmpty(),
            component = componentField.text?.toString()?.trim().orEmpty()
        )
        saveMapping(mapping)
        setResult(RESULT_OK, resultIntent(cleared = false))
        finish()
    }

    private fun currentMapping(): ButtonAction? {
        val dial = dialSlot
        return if (dial != null) settings.dialAction(dial) else settings.buttonAction(buttonId, slot)
    }

    private fun clearMapping() {
        val dial = dialSlot
        if (dial != null) settings.clearDialAction(dial) else settings.clearButtonAction(buttonId, slot)
    }

    private fun saveMapping(mapping: ButtonAction) {
        val dial = dialSlot
        if (dial != null) settings.setDialAction(dial, mapping) else settings.setButtonAction(buttonId, slot, mapping)
    }

    private fun resultIntent(cleared: Boolean): Intent =
        Intent()
            .putExtra(EXTRA_BUTTON_ID, buttonId)
            .putExtra(EXTRA_SLOT, slot.storageKey)
            .putExtra(EXTRA_DIAL_SLOT, dialSlot?.storageKey)
            .putExtra(EXTRA_CLEARED, cleared)

    companion object {
        const val EXTRA_BUTTON_ID = "button_id"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_DIAL_SLOT = "dial_slot"
        const val EXTRA_CLEARED = "cleared"
    }
}

private enum class IntentPreset(val titleRes: Int) {
    CUSTOM(R.string.intent_preset_custom),
    VIEW_URL(R.string.intent_preset_view_url),
    SETTINGS(R.string.intent_preset_settings),
    DIAL(R.string.intent_preset_dial),
    BROADCAST(R.string.intent_preset_broadcast)
}
