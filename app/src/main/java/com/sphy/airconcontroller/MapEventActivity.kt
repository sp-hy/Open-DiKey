package com.sphy.airconcontroller

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.sphy.airconcontroller.dikey.ButtonMapSlot
import com.sphy.airconcontroller.dikey.DiKeyButtonCatalog
import com.sphy.airconcontroller.dikey.DialMapSlot
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.OpenDiKeyActivity

/** First step: pick which remappable press to map. */
class MapEventActivity : OpenDiKeyActivity() {
    private lateinit var settings: AppSettings
    private var buttonId: Int = 0
    private var dialSide: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_event)

        settings = OpenDiKeyApp.from(this).dikey.settings
        buttonId = intent.getIntExtra(EXTRA_BUTTON_ID, 0)
        dialSide = intent.getStringExtra(EXTRA_DIAL_SIDE)?.takeIf { it == "LEFT" || it == "RIGHT" }

        findViewById<TextView>(R.id.mapEventTitle).setText(R.string.map_event_title)
        findViewById<TextView>(R.id.mapEventSubtitle).text = when {
            dialSide != null -> getString(
                R.string.map_event_pick_dial_fmt,
                dialSideLabel(dialSide!!)
            )
            buttonId in DiKeyButtonCatalog.ids ->
                getString(R.string.map_event_pick_slot_fmt, buttonId)
            else -> getString(R.string.map_event_subtitle)
        }

        findViewById<android.widget.ImageButton>(R.id.mapEventBackButton).setOnClickListener {
            finish()
        }

        val list = findViewById<ListView>(R.id.mapEventList)
        list.adapter = SlotAdapter(buildRows())
        list.setOnItemClickListener { _, _, position, _ ->
            val side = dialSide
            if (side != null) {
                val dialSlot = DialMapSlot.remappableForSide(side)[position]
                startActivity(
                    Intent(this, MapActionActivity::class.java)
                        .putExtra(MapActionActivity.EXTRA_DIAL_SLOT, dialSlot.storageKey)
                )
            } else {
                val slot = ButtonMapSlot.REMAPPABLE[position]
                startActivity(
                    Intent(this, MapActionActivity::class.java)
                        .putExtra(MapActionActivity.EXTRA_BUTTON_ID, buttonId)
                        .putExtra(MapActionActivity.EXTRA_SLOT, slot.storageKey)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (findViewById<ListView>(R.id.mapEventList).adapter as? SlotAdapter)?.submit(buildRows())
    }

    private fun buildRows(): List<SlotRow> {
        val side = dialSide
        if (side != null) {
            return DialMapSlot.remappableForSide(side).map { slot ->
                SlotRow(
                    title = getString(slot.titleRes),
                    subtitle = dialSlotSubtitle(slot)
                )
            }
        }
        return ButtonMapSlot.REMAPPABLE.map { slot ->
            SlotRow(
                title = getString(slot.titleRes),
                subtitle = buttonSlotSubtitle(slot)
            )
        }
    }

    private fun dialSlotSubtitle(slot: DialMapSlot): String {
        val mapping = settings.dialAction(slot)
        if (mapping != null) {
            return formatButtonActionSummary(
                packageManager = packageManager,
                action = mapping,
                openAppFmt = { getString(R.string.map_slot_open_app_fmt, it) },
                openAppMissingFmt = { getString(R.string.map_slot_open_app_missing_fmt, it) },
                intentFmt = { getString(R.string.map_slot_intent_fmt, it) },
                broadcastFmt = { getString(R.string.map_slot_broadcast_fmt, it) },
                seatFmt = { getString(R.string.map_slot_seat_fmt, it) }
            )
        }
        return getString(R.string.map_slot_none)
    }

    private fun buttonSlotSubtitle(slot: ButtonMapSlot): String {
        val mapping = settings.buttonAction(buttonId, slot)
        if (mapping != null) {
            return formatButtonActionSummary(
                packageManager = packageManager,
                action = mapping,
                openAppFmt = { getString(R.string.map_slot_open_app_fmt, it) },
                openAppMissingFmt = { getString(R.string.map_slot_open_app_missing_fmt, it) },
                intentFmt = { getString(R.string.map_slot_intent_fmt, it) },
                broadcastFmt = { getString(R.string.map_slot_broadcast_fmt, it) },
                seatFmt = { getString(R.string.map_slot_seat_fmt, it) }
            )
        }
        return getString(R.string.map_slot_none)
    }

    private fun dialSideLabel(side: String): String =
        if (side == "LEFT") getString(R.string.dial_left) else getString(R.string.dial_right)

    companion object {
        const val EXTRA_BUTTON_ID = "button_id"
        const val EXTRA_DIAL_SIDE = "dial_side"
    }
}

private data class SlotRow(val title: String, val subtitle: String)

private class SlotAdapter(
    private var items: List<SlotRow>
) : BaseAdapter() {
    fun submit(next: List<SlotRow>) {
        items = next
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): SlotRow = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_event, parent, false)
        val item = items[position]
        view.findViewById<TextView>(R.id.mapEventItemTitle).text = item.title
        val subtitle = view.findViewById<TextView>(R.id.mapEventItemSubtitle)
        subtitle.text = item.subtitle
        subtitle.visibility = View.VISIBLE
        return view
    }
}
