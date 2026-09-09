package com.sphy.airconcontroller.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.sphy.airconcontroller.R

/**
 * Dark cabin photo behind every screen.
 * CSS equivalent: `opacity: 0.2` on a `blur(5px)` image over the dark window color.
 * Blur is baked into [R.drawable.bg_interior]; alpha is applied here.
 */
open class OpenDiKeyActivity : AppCompatActivity() {
    override fun setContentView(layoutResID: Int) {
        setContentView(layoutInflater.inflate(layoutResID, null))
    }

    override fun setContentView(view: View?) {
        val frame = FrameLayout(this)
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.bg_interior)
            alpha = 0.2f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(
            bg,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        super.setContentView(frame)
    }
}
