package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compact artificial-horizon style pitch/roll indicator.
 * Smoothly eases toward the latest IMU angles.
 */
class AttitudeHorizonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.horizon_sky)
        style = Paint.Style.FILL
    }
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.horizon_ground)
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.horizon_line)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_orange)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_orange)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val clipPath = Path()
    private val clipRect = RectF()

    private var displayPitch = 0f
    private var displayRoll = 0f
    private var targetPitch = 0f
    private var targetRoll = 0f

    fun setAttitude(pitchDeg: Double?, rollDeg: Double?) {
        targetPitch = (pitchDeg ?: 0.0).toFloat().coerceIn(-45f, 45f)
        targetRoll = (rollDeg ?: 0.0).toFloat().coerceIn(-45f, 45f)
        postOnAnimation(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val pd = targetPitch - displayPitch
            val rd = targetRoll - displayRoll
            val settled = kotlin.math.abs(pd) < 0.05f && kotlin.math.abs(rd) < 0.05f
            if (settled) {
                displayPitch = targetPitch
                displayRoll = targetRoll
                invalidate()
                return
            }
            displayPitch += pd * 0.18f
            displayRoll += rd * 0.18f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - ringPaint.strokeWidth

        clipRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        clipPath.reset()
        clipPath.addOval(clipRect, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.translate(cx, cy)
        canvas.rotate(-displayRoll)

        // Pitch: positive nose-up moves ground down (sky expands below center).
        val pitchPx = displayPitch / 45f * radius * 0.85f
        canvas.translate(0f, pitchPx)

        val extent = radius * 3f
        canvas.drawRect(-extent, -extent, extent, 0f, skyPaint)
        canvas.drawRect(-extent, 0f, extent, extent, groundPaint)
        canvas.drawLine(-radius * 1.2f, 0f, radius * 1.2f, 0f, linePaint)

        // Pitch ladders
        for (step in listOf(-20f, -10f, 10f, 20f)) {
            val y = -step / 45f * radius * 0.85f
            val half = radius * 0.22f
            canvas.drawLine(-half, y, half, y, linePaint)
        }
        canvas.restore()

        // Fixed aircraft symbol
        canvas.drawCircle(cx, cy, radius, ringPaint)
        val wing = radius * 0.42f
        canvas.drawLine(cx - wing, cy, cx - radius * 0.12f, cy, accentPaint)
        canvas.drawLine(cx + radius * 0.12f, cy, cx + wing, cy, accentPaint)
        canvas.drawLine(cx, cy, cx, cy + radius * 0.18f, accentPaint)
        canvas.drawCircle(cx, cy, radius * 0.06f, accentPaint)

        // Roll ticks on the rim
        for (deg in -60..60 step 30) {
            val rad = Math.toRadians(deg.toDouble() - 90.0)
            val inner = radius - resources.displayMetrics.density * 6f
            val outer = radius
            val cos = cos(rad).toFloat()
            val sin = sin(rad).toFloat()
            canvas.drawLine(
                cx + cos * inner,
                cy + sin * inner,
                cx + cos * outer,
                cy + sin * outer,
                linePaint,
            )
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}
