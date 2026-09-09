package com.sphy.airconcontroller

import android.content.Intent
import android.os.Bundle
import com.sphy.airconcontroller.ui.OpenDiKeyActivity

/** Debug menus behind the home cog. */
class SettingsHubActivity : OpenDiKeyActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_hub)

        findViewById<android.widget.ImageButton>(R.id.settingsBackButton).setOnClickListener {
            finish()
        }
        findViewById<android.view.View>(R.id.openClimateButton).setOnClickListener {
            startActivity(Intent(this, ClimateTestActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openDikeyButton).setOnClickListener {
            startActivity(Intent(this, DiKeyProbeActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openUsbButton).setOnClickListener {
            startActivity(Intent(this, UsbProbeActivity::class.java))
        }
    }
}
