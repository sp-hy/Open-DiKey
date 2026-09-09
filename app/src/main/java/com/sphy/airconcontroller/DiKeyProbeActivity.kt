package com.sphy.airconcontroller

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sphy.airconcontroller.dikey.DiKeyEvent
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug overlay: live DiKey event / transport log. */
class DiKeyProbeActivity : OpenDiKeyActivity() {
    private lateinit var connStatusText: TextView
    private lateinit var eventLogText: TextView

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val eventLines = ArrayDeque<String>(MAX_LOG_LINES)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dikey_probe)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.dikey_title)

        val session = OpenDiKeyApp.from(this).dikey
        connStatusText = findViewById(R.id.dikeyConnStatusText)
        eventLogText = findViewById(R.id.dikeyEventLog)

        findViewById<Button>(R.id.dikeyClearLogButton).setOnClickListener {
            eventLines.clear()
            eventLogText.text = getString(R.string.dikey_event_log_empty)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.status.collect { connStatusText.text = it }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.events.collect { appendEvent(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.logs.collect { appendLogLine(it) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun appendEvent(event: DiKeyEvent) {
        appendLogLine("${event.summary}\n  ${event.hex}")
    }

    private fun appendLogLine(line: String) {
        val stamped = "${timeFmt.format(Date())}  $line"
        while (eventLines.size >= MAX_LOG_LINES) eventLines.removeFirst()
        eventLines.addFirst(stamped)
        eventLogText.text = eventLines.joinToString("\n\n")
    }

    companion object {
        private const val MAX_LOG_LINES = 40
    }
}
