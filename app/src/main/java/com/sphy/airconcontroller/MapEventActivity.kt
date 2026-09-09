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
import com.sphy.airconcontroller.dikey.DiKeyButtonCatalog
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.OpenDiKeyActivity

class MapEventActivity : OpenDiKeyActivity() {
    private lateinit var settings: AppSettings
    private var buttonId: Int = 0

    private val pickApp = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val id = data.getIntExtra(AppPickerActivity.EXTRA_BUTTON_ID, buttonId)
        if (id !in DiKeyButtonCatalog.ids) return@registerForActivityResult
        if (data.getBooleanExtra(AppPickerActivity.EXTRA_CLEARED, false)) {
            settings.clearUpOpenApp(id)
        } else {
            val pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)?.takeIf { it.isNotBlank() }
                ?: return@registerForActivityResult
            val label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: pkg
            settings.setUpOpenApp(id, pkg, label)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_event)

        settings = OpenDiKeyApp.from(this).dikey.settings
        buttonId = intent.getIntExtra(EXTRA_BUTTON_ID, 0)

        findViewById<TextView>(R.id.mapEventSubtitle).text =
            if (buttonId in DiKeyButtonCatalog.ids) {
                getString(R.string.button_mapping_choose_app_for_fmt, buttonId)
            } else {
                getString(R.string.map_event_subtitle)
            }

        findViewById<android.widget.ImageButton>(R.id.mapEventBackButton).setOnClickListener {
            finish()
        }

        val options = listOf(
            MapEventOption(
                id = EVENT_OPEN_APP,
                title = getString(R.string.map_event_open_app),
                subtitle = getString(R.string.map_event_open_app_hint)
            )
        )
        val adapter = MapEventAdapter(options)
        val list = findViewById<ListView>(R.id.mapEventList)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            when (adapter.getItem(position).id) {
                EVENT_OPEN_APP -> pickApp.launch(
                    Intent(this, AppPickerActivity::class.java)
                        .putExtra(AppPickerActivity.EXTRA_BUTTON_ID, buttonId)
                )
            }
        }
    }

    companion object {
        const val EXTRA_BUTTON_ID = "button_id"
        private const val EVENT_OPEN_APP = "open_app"
    }
}

private data class MapEventOption(
    val id: String,
    val title: String,
    val subtitle: String
)

private class MapEventAdapter(
    private val items: List<MapEventOption>
) : BaseAdapter() {
    override fun getCount(): Int = items.size

    override fun getItem(position: Int): MapEventOption = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_event, parent, false)
        val item = items[position]
        val title = view.findViewById<TextView>(R.id.mapEventItemTitle)
        val subtitle = view.findViewById<TextView>(R.id.mapEventItemSubtitle)
        title.text = item.title
        subtitle.text = item.subtitle
        subtitle.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
        return view
    }
}
