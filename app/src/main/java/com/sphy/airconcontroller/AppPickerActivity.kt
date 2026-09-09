package com.sphy.airconcontroller

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.sphy.airconcontroller.apps.LaunchableApp
import com.sphy.airconcontroller.apps.LaunchableApps
import com.sphy.airconcontroller.dikey.DiKeyButtonCatalog
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : OpenDiKeyActivity() {
    private val adapter = AppPickAdapter()
    private var buttonId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        buttonId = intent.getIntExtra(EXTRA_BUTTON_ID, 0)
        val subtitle = findViewById<TextView>(R.id.pickerSubtitle)
        subtitle.text = if (buttonId in DiKeyButtonCatalog.ids) {
            getString(R.string.button_mapping_choose_app_for_fmt, buttonId)
        } else {
            getString(R.string.button_mapping_choose_app_hint)
        }

        findViewById<android.widget.ImageButton>(R.id.pickerBackButton).setOnClickListener {
            finish()
        }

        val list = findViewById<ListView>(R.id.pickerList)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position)
            val result = Intent()
                .putExtra(EXTRA_BUTTON_ID, buttonId)
            if (app.packageName.isEmpty()) {
                result.putExtra(EXTRA_CLEARED, true)
            } else {
                result.putExtra(EXTRA_PACKAGE, app.packageName)
                result.putExtra(EXTRA_LABEL, app.label)
            }
            setResult(RESULT_OK, result)
            finish()
        }

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { LaunchableApps.list(this@AppPickerActivity) }
            adapter.submit(
                listOf(
                    LaunchableApp(
                        packageName = "",
                        label = getString(R.string.button_mapping_none),
                        icon = null
                    )
                ) + apps
            )
        }
    }

    companion object {
        const val EXTRA_BUTTON_ID = "button_id"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CLEARED = "cleared"
    }
}

private class AppPickAdapter : BaseAdapter() {
    private val items = mutableListOf<LaunchableApp>()

    fun submit(next: List<LaunchableApp>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): LaunchableApp = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_pick, parent, false)
        val item = items[position]
        val icon = view.findViewById<ImageView>(R.id.appPickIcon)
        val title = view.findViewById<TextView>(R.id.appPickTitle)
        val subtitle = view.findViewById<TextView>(R.id.appPickSubtitle)
        title.text = item.label
        if (item.packageName.isEmpty()) {
            icon.setImageDrawable(null)
            subtitle.text = parent.context.getString(R.string.button_mapping_none_hint)
        } else {
            icon.setImageDrawable(item.icon)
            subtitle.text = item.packageName
        }
        return view
    }
}
