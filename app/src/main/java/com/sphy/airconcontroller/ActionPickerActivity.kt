package com.sphy.airconcontroller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.sphy.airconcontroller.apps.DiscoveredAction
import com.sphy.airconcontroller.apps.DiscoveredActions
import com.sphy.airconcontroller.dikey.ButtonMapSlot
import com.sphy.airconcontroller.dikey.DiKeyButtonCatalog
import com.sphy.airconcontroller.dikey.DialMapSlot
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick a system / app action that actually resolves on this head unit.
 */
class ActionPickerActivity : OpenDiKeyActivity() {
    private lateinit var settings: AppSettings
    private val adapter = ActionPickAdapter()
    private var buttonId: Int = 0
    private var slot: ButtonMapSlot = ButtonMapSlot.UP_CLICK
    private var dialSlot: DialMapSlot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        settings = OpenDiKeyApp.from(this).dikey.settings
        dialSlot = DialMapSlot.fromStorageKey(intent.getStringExtra(EXTRA_DIAL_SLOT).orEmpty())
        buttonId = intent.getIntExtra(EXTRA_BUTTON_ID, 0)
        slot = ButtonMapSlot.fromStorageKey(intent.getStringExtra(EXTRA_SLOT).orEmpty())
            ?: ButtonMapSlot.UP_CLICK

        findViewById<TextView>(R.id.pickerTitle).setText(R.string.action_picker_title)
        findViewById<TextView>(R.id.pickerSubtitle).text = when {
            dialSlot != null -> getString(
                R.string.map_action_dial_subtitle_fmt,
                if (dialSlot!!.side == "LEFT") getString(R.string.dial_left) else getString(R.string.dial_right),
                getString(dialSlot!!.titleRes)
            )
            buttonId in DiKeyButtonCatalog.ids ->
                getString(R.string.map_action_subtitle_fmt, buttonId, getString(slot.titleRes))
            else -> getString(R.string.action_picker_hint)
        }

        findViewById<android.widget.ImageButton>(R.id.pickerBackButton).setOnClickListener {
            finish()
        }

        val list = findViewById<ListView>(R.id.pickerList)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            val dial = dialSlot
            if (dial != null) {
                settings.setDialAction(dial, item.mapping)
            } else {
                settings.setButtonAction(buttonId, slot, item.mapping)
            }
            setResult(RESULT_OK)
            finish()
        }

        lifecycleScope.launch {
            val actions = withContext(Dispatchers.IO) {
                DiscoveredActions.list(this@ActionPickerActivity)
            }
            adapter.submit(actions)
        }
    }

    companion object {
        const val EXTRA_BUTTON_ID = "button_id"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_DIAL_SLOT = "dial_slot"
    }
}

private class ActionPickAdapter : BaseAdapter() {
    private val items = mutableListOf<DiscoveredAction>()

    fun submit(next: List<DiscoveredAction>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): DiscoveredAction = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_pick, parent, false)
        val item = items[position]
        view.findViewById<ImageView>(R.id.appPickIcon).setImageDrawable(item.icon)
        view.findViewById<TextView>(R.id.appPickTitle).text = item.label
        view.findViewById<TextView>(R.id.appPickSubtitle).text = item.subtitle
        return view
    }
}
