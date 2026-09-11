package com.sphy.airconcontroller

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sphy.airconcontroller.dikey.ButtonMapSlot
import com.sphy.airconcontroller.dikey.DiKeyButtonCatalog
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.dikey.DialMapSlot
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.launch

class ButtonMappingActivity : OpenDiKeyActivity() {
    private lateinit var session: DiKeySession
    private lateinit var settings: AppSettings
    private lateinit var connStatus: TextView
    private lateinit var dialLeftClick: TextView
    private lateinit var dialLeftLong: TextView
    private lateinit var dialRightClick: TextView
    private lateinit var dialRightLong: TextView
    private val rows = arrayOfNulls<RowViews>(BUTTON_COUNT + 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_button_mapping)

        session = OpenDiKeyApp.from(this).dikey
        settings = session.settings
        connStatus = findViewById(R.id.mappingConnStatus)
        dialLeftClick = findViewById(R.id.mappingDialLeftClick)
        dialLeftLong = findViewById(R.id.mappingDialLeftLong)
        dialRightClick = findViewById(R.id.mappingDialRightClick)
        dialRightLong = findViewById(R.id.mappingDialRightLong)

        findViewById<android.widget.ImageButton>(R.id.mappingBackButton).setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.mappingDialLeft).setOnClickListener { openDialLongMap("LEFT") }
        findViewById<View>(R.id.mappingDialRight).setOnClickListener { openDialLongMap("RIGHT") }

        inflateRows()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.status.collect { connStatus.text = it }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bindDials()
        DiKeyButtonCatalog.ids.forEach { bindRow(it) }
    }

    private fun inflateRows() {
        val host = findViewById<LinearLayout>(R.id.mappingRows)
        val gap = (12 * resources.displayMetrics.density).toInt()
        val ids = DiKeyButtonCatalog.ids.toList()
        for (i in ids.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = gap }
            }
            val left = inflateRow(row, ids[i])
            left.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i + 1 < ids.size) gap else 0
            }
            row.addView(left)
            if (i + 1 < ids.size) {
                val right = inflateRow(row, ids[i + 1])
                right.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(right)
            }
            host.addView(row)
        }
    }

    private fun inflateRow(parent: LinearLayout, buttonId: Int): View {
        val view = layoutInflater.inflate(R.layout.item_button_mapping, parent, false)
        val views = RowViews(
            title = view.findViewById(R.id.mappingButtonTitle),
            down = view.findViewById(R.id.mappingDownLabel),
            downLong = view.findViewById(R.id.mappingDownLongLabel),
            up = view.findViewById(R.id.mappingUpLabel),
            upLong = view.findViewById(R.id.mappingUpLongLabel)
        )
        views.title.text = getString(R.string.button_n_fmt, buttonId)
        view.setOnClickListener { openEventMenu(buttonId) }
        rows[buttonId] = views
        bindRow(buttonId)
        return view
    }

    private fun bindRow(buttonId: Int) {
        val views = rows[buttonId] ?: return
        views.down.text = slotSummary(buttonId, ButtonMapSlot.DOWN_CLICK)
        views.downLong.text = slotSummary(buttonId, ButtonMapSlot.DOWN_LONG)
        views.up.text = slotSummary(buttonId, ButtonMapSlot.UP_CLICK)
        views.upLong.text = slotSummary(buttonId, ButtonMapSlot.UP_LONG)
    }

    private fun slotSummary(buttonId: Int, slot: ButtonMapSlot): String {
        val title = getString(slot.titleRes)
        if (slot == ButtonMapSlot.DOWN_CLICK) {
            return getString(
                R.string.button_mapping_slot_fmt,
                title,
                getString(
                    R.string.map_slot_climate_fmt,
                    getString(DiKeyButtonCatalog.downLabelRes(buttonId))
                )
            )
        }
        val mapping = settings.buttonAction(buttonId, slot)
        if (mapping != null) {
            val action = formatButtonActionSummary(
                packageManager = packageManager,
                action = mapping,
                openAppFmt = { getString(R.string.map_slot_open_app_fmt, it) },
                openAppMissingFmt = { getString(R.string.map_slot_open_app_missing_fmt, it) },
                intentFmt = { getString(R.string.map_slot_intent_fmt, it) },
                broadcastFmt = { getString(R.string.map_slot_broadcast_fmt, it) },
                seatFmt = { getString(R.string.map_slot_seat_fmt, it) }
            )
            return getString(R.string.button_mapping_slot_fmt, title, action)
        }
        return getString(R.string.button_mapping_slot_fmt, title, getString(R.string.map_slot_none))
    }

    private fun openEventMenu(buttonId: Int) {
        startActivity(
            Intent(this, MapEventActivity::class.java)
                .putExtra(MapEventActivity.EXTRA_BUTTON_ID, buttonId)
        )
    }

    private fun openDialLongMap(side: String) {
        val slot = DialMapSlot.longForSide(side)
        startActivity(
            Intent(this, MapActionActivity::class.java)
                .putExtra(MapActionActivity.EXTRA_DIAL_SLOT, slot.storageKey)
        )
    }

    private fun bindDials() {
        dialLeftClick.text = dialSlotSummary(DialMapSlot.LEFT_CLICK)
        dialLeftLong.text = dialSlotSummary(DialMapSlot.LEFT_LONG)
        dialRightClick.text = dialSlotSummary(DialMapSlot.RIGHT_CLICK)
        dialRightLong.text = dialSlotSummary(DialMapSlot.RIGHT_LONG)
    }

    private fun dialSlotSummary(slot: DialMapSlot): String {
        val title = getString(slot.titleRes)
        if (!slot.remappable) {
            return getString(
                R.string.button_mapping_slot_fmt,
                title,
                getString(
                    R.string.map_slot_climate_fmt,
                    getString(R.string.map_slot_dial_climate_default)
                )
            )
        }
        val mapping = settings.dialAction(slot)
        if (mapping != null) {
            val action = formatButtonActionSummary(
                packageManager = packageManager,
                action = mapping,
                openAppFmt = { getString(R.string.map_slot_open_app_fmt, it) },
                openAppMissingFmt = { getString(R.string.map_slot_open_app_missing_fmt, it) },
                intentFmt = { getString(R.string.map_slot_intent_fmt, it) },
                broadcastFmt = { getString(R.string.map_slot_broadcast_fmt, it) },
                seatFmt = { getString(R.string.map_slot_seat_fmt, it) }
            )
            return getString(R.string.button_mapping_slot_fmt, title, action)
        }
        return getString(R.string.button_mapping_slot_fmt, title, getString(R.string.map_slot_none))
    }

    private class RowViews(
        val title: TextView,
        val down: TextView,
        val downLong: TextView,
        val up: TextView,
        val upLong: TextView
    )

    companion object {
        private const val BUTTON_COUNT = 10
    }
}
