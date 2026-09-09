package com.sphy.airconcontroller

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sphy.airconcontroller.dikey.DiKeyButtonCatalog
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.launch

class ButtonMappingActivity : OpenDiKeyActivity() {
    private lateinit var session: DiKeySession
    private lateinit var settings: AppSettings
    private lateinit var connStatus: TextView
    private val rows = arrayOfNulls<RowViews>(BUTTON_COUNT + 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_button_mapping)

        session = OpenDiKeyApp.from(this).dikey
        settings = session.settings
        connStatus = findViewById(R.id.mappingConnStatus)

        findViewById<android.widget.ImageButton>(R.id.mappingBackButton).setOnClickListener {
            finish()
        }

        inflateRows()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.status.collect { connStatus.text = it }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
            up = view.findViewById(R.id.mappingUpLabel),
            clear = view.findViewById(R.id.mappingClearButton)
        )
        views.title.text = getString(R.string.button_n_fmt, buttonId)
        views.down.text = getString(
            R.string.button_mapping_down_fmt,
            getString(DiKeyButtonCatalog.downLabelRes(buttonId))
        )
        view.setOnClickListener { openEventMenu(buttonId) }
        views.clear.setOnClickListener {
            settings.clearUpOpenApp(buttonId)
            bindRow(buttonId)
        }
        rows[buttonId] = views
        bindRow(buttonId)
        return view
    }

    private fun bindRow(buttonId: Int) {
        val views = rows[buttonId] ?: return
        val mapping = settings.upOpenApp(buttonId)
        if (mapping == null) {
            views.up.text = getString(R.string.button_mapping_up_none)
            views.clear.visibility = View.GONE
            return
        }
        val available = packageManager.getLaunchIntentForPackage(mapping.packageName) != null
        views.up.text = if (available) {
            getString(R.string.button_mapping_up_app_fmt, mapping.label)
        } else {
            getString(R.string.button_mapping_up_missing_fmt, mapping.label)
        }
        views.clear.visibility = View.VISIBLE
    }

    private fun openEventMenu(buttonId: Int) {
        startActivity(
            Intent(this, MapEventActivity::class.java)
                .putExtra(MapEventActivity.EXTRA_BUTTON_ID, buttonId)
        )
    }

    private class RowViews(
        val title: TextView,
        val down: TextView,
        val up: TextView,
        val clear: TextView
    )

    companion object {
        private const val BUTTON_COUNT = 10
    }
}
