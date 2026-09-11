package com.sphy.airconcontroller

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.sphy.airconcontroller.dikey.ButtonMapSlot
import com.sphy.airconcontroller.dikey.DiKeyButtonCatalog
import com.sphy.airconcontroller.dikey.DialMapSlot
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.ButtonAction
import com.sphy.airconcontroller.ui.OpenDiKeyActivity

/** Second step: choose what a selected press does. */
class MapActionActivity : OpenDiKeyActivity() {
    private lateinit var settings: AppSettings
    private var buttonId: Int = 0
    private var buttonSlot: ButtonMapSlot = ButtonMapSlot.UP_CLICK
    private var dialSlot: DialMapSlot? = null

    private val pickApp = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val dial = DialMapSlot.fromStorageKey(data.getStringExtra(AppPickerActivity.EXTRA_DIAL_SLOT).orEmpty())
            ?: dialSlot
        if (dial != null) {
            if (data.getBooleanExtra(AppPickerActivity.EXTRA_CLEARED, false)) {
                settings.clearDialAction(dial)
            } else {
                val pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)?.takeIf { it.isNotBlank() }
                    ?: return@registerForActivityResult
                val label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: pkg
                settings.setDialAction(dial, ButtonAction.OpenApp(pkg, label))
            }
            finish()
            return@registerForActivityResult
        }
        val id = data.getIntExtra(AppPickerActivity.EXTRA_BUTTON_ID, buttonId)
        if (id !in DiKeyButtonCatalog.ids) return@registerForActivityResult
        val slotKey = data.getStringExtra(AppPickerActivity.EXTRA_SLOT) ?: buttonSlot.storageKey
        val chosen = ButtonMapSlot.fromStorageKey(slotKey) ?: buttonSlot
        if (data.getBooleanExtra(AppPickerActivity.EXTRA_CLEARED, false)) {
            settings.clearButtonAction(id, chosen)
        } else {
            val pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)?.takeIf { it.isNotBlank() }
                ?: return@registerForActivityResult
            val label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: pkg
            settings.setButtonAction(id, chosen, ButtonAction.OpenApp(pkg, label))
        }
        finish()
    }

    private val editIntent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) finish()
    }

    private val pickAction = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) finish()
    }

    private var showingSeatMenu = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_event)

        settings = OpenDiKeyApp.from(this).dikey.settings
        dialSlot = DialMapSlot.fromStorageKey(intent.getStringExtra(EXTRA_DIAL_SLOT).orEmpty())
        buttonId = intent.getIntExtra(EXTRA_BUTTON_ID, 0)
        buttonSlot = ButtonMapSlot.fromStorageKey(intent.getStringExtra(EXTRA_SLOT).orEmpty())
            ?: ButtonMapSlot.UP_CLICK
        if (dialSlot?.remappable == false || (dialSlot == null && !buttonSlot.remappable)) {
            finish()
            return
        }

        findViewById<android.widget.ImageButton>(R.id.mapEventBackButton).setOnClickListener {
            if (showingSeatMenu) showMainOptions() else finish()
        }

        showMainOptions()
    }

    private fun showMainOptions() {
        showingSeatMenu = false
        findViewById<TextView>(R.id.mapEventTitle).setText(R.string.map_action_title)
        findViewById<TextView>(R.id.mapEventSubtitle).text = subtitle()
        val options = buildList {
            if (currentMapping() != null) {
                add(
                    ActionOption(
                        id = ACTION_CLEAR,
                        title = getString(R.string.map_event_clear),
                        subtitle = getString(R.string.map_event_clear_hint)
                    )
                )
            }
            add(
                ActionOption(
                    id = ACTION_OPEN_APP,
                    title = getString(R.string.map_event_open_app),
                    subtitle = getString(R.string.map_event_open_app_hint)
                )
            )
            add(
                ActionOption(
                    id = ACTION_SYSTEM,
                    title = getString(R.string.map_event_system_action),
                    subtitle = getString(R.string.map_event_system_action_hint)
                )
            )
            add(
                ActionOption(
                    id = ACTION_INTENT,
                    title = getString(R.string.map_event_run_intent),
                    subtitle = getString(R.string.map_event_run_intent_hint)
                )
            )
            add(
                ActionOption(
                    id = ACTION_SEAT,
                    title = getString(R.string.map_event_seat),
                    subtitle = getString(R.string.map_event_seat_hint)
                )
            )
        }
        showOptions(options)
    }

    private fun showSeatOptions() {
        showingSeatMenu = true
        findViewById<TextView>(R.id.mapEventTitle).setText(R.string.map_event_seat)
        findViewById<TextView>(R.id.mapEventSubtitle).text = getString(R.string.map_event_seat_pick_hint)
        showOptions(
            listOf(
                ActionOption(
                    id = ACTION_SEAT_HEAT,
                    title = getString(R.string.map_event_seat_heat),
                    subtitle = getString(R.string.map_event_seat_heat_hint)
                ),
                ActionOption(
                    id = ACTION_SEAT_VENT,
                    title = getString(R.string.map_event_seat_vent),
                    subtitle = getString(R.string.map_event_seat_vent_hint)
                ),
                ActionOption(
                    id = ACTION_SEAT_HEAT_PASS,
                    title = getString(R.string.map_event_seat_heat_pass),
                    subtitle = getString(R.string.map_event_seat_heat_pass_hint)
                ),
                ActionOption(
                    id = ACTION_SEAT_VENT_PASS,
                    title = getString(R.string.map_event_seat_vent_pass),
                    subtitle = getString(R.string.map_event_seat_vent_pass_hint)
                )
            )
        )
    }

    private fun showOptions(options: List<ActionOption>) {
        val adapter = ActionAdapter(options)
        val list = findViewById<ListView>(R.id.mapEventList)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            when (adapter.getItem(position).id) {
                ACTION_CLEAR -> {
                    clearMapping()
                    finish()
                }
                ACTION_OPEN_APP -> pickApp.launch(appPickerIntent())
                ACTION_SYSTEM -> pickAction.launch(systemPickerIntent())
                ACTION_INTENT -> editIntent.launch(intentEditorIntent())
                ACTION_SEAT -> showSeatOptions()
                ACTION_SEAT_HEAT -> {
                    saveSeat(
                        ButtonAction.SeatCycle.Kind.HEAT,
                        ButtonAction.SeatCycle.Zone.DRIVER,
                        getString(R.string.map_event_seat_heat)
                    )
                    finish()
                }
                ACTION_SEAT_VENT -> {
                    saveSeat(
                        ButtonAction.SeatCycle.Kind.VENT,
                        ButtonAction.SeatCycle.Zone.DRIVER,
                        getString(R.string.map_event_seat_vent)
                    )
                    finish()
                }
                ACTION_SEAT_HEAT_PASS -> {
                    saveSeat(
                        ButtonAction.SeatCycle.Kind.HEAT,
                        ButtonAction.SeatCycle.Zone.PASSENGER,
                        getString(R.string.map_event_seat_heat_pass)
                    )
                    finish()
                }
                ACTION_SEAT_VENT_PASS -> {
                    saveSeat(
                        ButtonAction.SeatCycle.Kind.VENT,
                        ButtonAction.SeatCycle.Zone.PASSENGER,
                        getString(R.string.map_event_seat_vent_pass)
                    )
                    finish()
                }
            }
        }
    }

    private fun saveSeat(
        kind: ButtonAction.SeatCycle.Kind,
        zone: ButtonAction.SeatCycle.Zone,
        label: String
    ) {
        val action = ButtonAction.SeatCycle(kind, zone, label)
        val dial = dialSlot
        if (dial != null) settings.setDialAction(dial, action)
        else settings.setButtonAction(buttonId, buttonSlot, action)
    }

    private fun subtitle(): String {
        val dial = dialSlot
        return if (dial != null) {
            getString(
                R.string.map_action_dial_subtitle_fmt,
                if (dial.side == "LEFT") getString(R.string.dial_left) else getString(R.string.dial_right),
                getString(dial.titleRes)
            )
        } else {
            getString(R.string.map_action_subtitle_fmt, buttonId, getString(buttonSlot.titleRes))
        }
    }

    private fun currentMapping(): ButtonAction? {
        val dial = dialSlot
        return if (dial != null) settings.dialAction(dial) else settings.buttonAction(buttonId, buttonSlot)
    }

    private fun clearMapping() {
        val dial = dialSlot
        if (dial != null) settings.clearDialAction(dial) else settings.clearButtonAction(buttonId, buttonSlot)
    }

    private fun appPickerIntent(): Intent =
        Intent(this, AppPickerActivity::class.java).also { putTargetExtras(it) }

    private fun systemPickerIntent(): Intent =
        Intent(this, ActionPickerActivity::class.java).also { putTargetExtras(it) }

    private fun intentEditorIntent(): Intent =
        Intent(this, IntentEditorActivity::class.java).also { putTargetExtras(it) }

    private fun putTargetExtras(intent: Intent) {
        val dial = dialSlot
        if (dial != null) {
            intent.putExtra(EXTRA_DIAL_SLOT, dial.storageKey)
        } else {
            intent.putExtra(EXTRA_BUTTON_ID, buttonId)
            intent.putExtra(EXTRA_SLOT, buttonSlot.storageKey)
        }
    }

    companion object {
        const val EXTRA_BUTTON_ID = "button_id"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_DIAL_SLOT = "dial_slot"
        private const val ACTION_CLEAR = "clear"
        private const val ACTION_OPEN_APP = "open_app"
        private const val ACTION_SYSTEM = "system_action"
        private const val ACTION_INTENT = "run_intent"
        private const val ACTION_SEAT = "seat"
        private const val ACTION_SEAT_HEAT = "seat_heat"
        private const val ACTION_SEAT_VENT = "seat_vent"
        private const val ACTION_SEAT_HEAT_PASS = "seat_heat_pass"
        private const val ACTION_SEAT_VENT_PASS = "seat_vent_pass"
    }
}

private data class ActionOption(
    val id: String,
    val title: String,
    val subtitle: String
)

private class ActionAdapter(
    private val items: List<ActionOption>
) : BaseAdapter() {
    override fun getCount(): Int = items.size

    override fun getItem(position: Int): ActionOption = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_event, parent, false)
        val item = items[position]
        view.findViewById<TextView>(R.id.mapEventItemTitle).text = item.title
        val subtitle = view.findViewById<TextView>(R.id.mapEventItemSubtitle)
        subtitle.text = item.subtitle
        subtitle.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
        return view
    }
}
