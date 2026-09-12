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
 * Attitude instruments:
 * - [AxisMode.COMBINED] circular artificial horizon (pitch + roll)
 * - [AxisMode.PITCH] vertical pitch tape
 * - [AxisMode.ROLL] bank-angle arc with pointer
 */
class AttitudeHorizonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class AxisMode { COMBINED, PITCH, ROLL }

    private val density = resources.displayMetrics.density

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
        strokeWidth = density * 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.horizon_line)
        style = Paint.Style.STROKE
        strokeWidth = density * 1.25f
        strokeCap = Paint.Cap.ROUND
        alpha = 140
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_orange)
        style = Paint.Style.STROKE
        strokeWidth = density * 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val accentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_orange)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_orange)
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_stroke)
        style = Paint.Style.FILL
    }
    private val clipPath = Path()
    private val clipRect = RectF()
    private val arcRect = RectF()
    private val pointerPath = Path()

    private var displayPitch = 0f
    private var displayRoll = 0f
    private var targetPitch = 0f
    private var targetRoll = 0f
    var axisMode: AxisMode = AxisMode.COMBINED
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    fun setAttitude(pitchDeg: Double?, rollDeg: Double?) {
        val pitch = (pitchDeg ?: 0.0).toFloat().coerceIn(-45f, 45f)
        val roll = (rollDeg ?: 0.0).toFloat().coerceIn(-45f, 45f)
        when (axisMode) {
            AxisMode.COMBINED -> {
                targetPitch = pitch
                targetRoll = roll
            }
            AxisMode.PITCH -> {
                targetPitch = pitch
                targetRoll = 0f
            }
            AxisMode.ROLL -> {
                targetPitch = 0f
                targetRoll = roll
            }
        }
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
        when (axisMode) {
            AxisMode.COMBINED -> drawCombined(canvas)
            AxisMode.PITCH -> drawPitchTape(canvas)
            AxisMode.ROLL -> drawRollBank(canvas)
        }
    }

    private fun drawCombined(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val stroke = ringPaint.strokeWidth
        val radius = minOf(cx, cy) - stroke
        // Inset fill so antialias doesn't bleed past the ring.
        val fillRadius = radius - stroke * 0.5f

        clipRect.set(cx - fillRadius, cy - fillRadius, cx + fillRadius, cy + fillRadius)
        clipPath.reset()
        clipPath.addOval(clipRect, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.translate(cx, cy)
        canvas.rotate(-displayRoll)

        val pitchPx = displayPitch / 45f * fillRadius * 0.85f
        canvas.translate(0f, pitchPx)

        val extent = fillRadius * 3f
        canvas.drawRect(-extent, -extent, extent, 0f, skyPaint)
        canvas.drawRect(-extent, 0f, extent, extent, groundPaint)
        canvas.drawLine(-fillRadius * 1.2f, 0f, fillRadius * 1.2f, 0f, linePaint)

        for (step in listOf(-20f, -10f, 10f, 20f)) {
            val y = -step / 45f * fillRadius * 0.85f
            val half = fillRadius * 0.22f
            canvas.drawLine(-half, y, half, y, linePaint)
        }
        canvas.restore()

        canvas.drawCircle(cx, cy, radius, ringPaint)
        val wing = fillRadius * 0.42f
        canvas.drawLine(cx - wing, cy, cx - fillRadius * 0.12f, cy, accentPaint)
        canvas.drawLine(cx + fillRadius * 0.12f, cy, cx + wing, cy, accentPaint)
        canvas.drawLine(cx, cy, cx, cy + fillRadius * 0.18f, accentPaint)
        canvas.drawCircle(cx, cy, fillRadius * 0.06f, accentPaint)

        for (deg in -60..60 step 30) {
            val rad = Math.toRadians(deg.toDouble() - 90.0)
            val inner = radius - density * 6f
            val outer = radius - stroke * 0.5f
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            canvas.drawLine(
                cx + c * inner,
                cy + s * inner,
                cx + c * outer,
                cy + s * outer,
                linePaint,
            )
        }
    }

    /** Vertical pitch tape — ladder scrolls; fixed center index. */
    private fun drawPitchTape(canvas: Canvas) {
        val pad = density * 8f
        val tapeW = width * 0.42f
        val left = (width - tapeW) / 2f
        val top = pad
        val bottom = height - pad
        val midY = (top + bottom) / 2f
        val halfH = (bottom - top) / 2f
        val pxPerDeg = halfH / 45f
        val corner = density * 10f
        val stroke = ringPaint.strokeWidth

        clipRect.set(left, top, left + tapeW, bottom)
        clipPath.reset()
        clipPath.addRoundRect(clipRect, corner, corner, Path.Direction.CW)

        canvas.drawPath(clipPath, trackPaint)

        canvas.save()
        canvas.clipPath(clipPath)
        val shift = displayPitch * pxPerDeg
        // Draw well past the clip; path clip keeps corners clean.
        canvas.drawRect(left - 2f, top - halfH, left + tapeW + 2f, midY + shift, skyPaint)
        canvas.drawRect(left - 2f, midY + shift, left + tapeW + 2f, bottom + halfH, groundPaint)
        canvas.drawLine(left, midY + shift, left + tapeW, midY + shift, linePaint)

        for (step in -40..40 step 10) {
            if (step == 0) continue
            val y = midY + shift - step * pxPerDeg
            if (y < top - 4f || y > bottom + 4f) continue
            val half = if (step % 20 == 0) tapeW * 0.32f else tapeW * 0.20f
            val cx = left + tapeW / 2f
            canvas.drawLine(cx - half, y, cx + half, y, faintPaint)
        }
        canvas.restore()

        // Fixed center index — kept inside the tape width.
        val idxHalf = tapeW * 0.38f
        val cx = width / 2f
        canvas.drawLine(cx - idxHalf, midY, cx - density * 10f, midY, accentPaint)
        canvas.drawLine(cx + density * 10f, midY, cx + idxHalf, midY, accentPaint)
        pointerPath.reset()
        pointerPath.moveTo(cx - density * 7f, midY)
        pointerPath.lineTo(cx, midY - density * 6f)
        pointerPath.lineTo(cx + density * 7f, midY)
        pointerPath.close()
        canvas.drawPath(pointerPath, accentFill)

        // Frame on top of fills
        clipRect.inset(stroke * 0.25f, stroke * 0.25f)
        canvas.drawRoundRect(clipRect, corner, corner, ringPaint)
    }

    /** Bank arc — curved scale with a pointer that tracks roll. */
    private fun drawRollBank(canvas: Canvas) {
        val pad = density * 8f
        val cx = width / 2f
        val cy = height / 2f
        val stroke = ringPaint.strokeWidth
        // Fit the full dial inside the view so nothing is clipped.
        val radius = minOf(width / 2f, height / 2f) - pad - stroke
        if (radius <= density * 8f) return
        val fillRadius = radius - stroke * 0.5f

        // Soft ground/sky disk — clipped strictly inside the ring.
        clipRect.set(cx - fillRadius, cy - fillRadius, cx + fillRadius, cy + fillRadius)
        clipPath.reset()
        clipPath.addOval(clipRect, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(clipRect.left, clipRect.top, clipRect.right, cy, skyPaint)
        canvas.drawRect(clipRect.left, cy, clipRect.right, clipRect.bottom, groundPaint)
        canvas.drawLine(clipRect.left, cy, clipRect.right, cy, faintPaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Bank scale kept inside the ring so ticks don't escape the border.
        val scaleR = fillRadius - density * 4f
        arcRect.set(cx - scaleR, cy - scaleR, cx + scaleR, cy + scaleR)
        canvas.drawArc(arcRect, 210f, 120f, false, faintPaint)

        for (deg in -60..60 step 15) {
            val rad = Math.toRadians(deg.toDouble() - 90.0)
            val longTick = deg % 30 == 0
            val outer = scaleR
            val inner = scaleR - if (longTick) density * 10f else density * 6f
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            canvas.drawLine(
                cx + c * inner,
                cy + s * inner,
                cx + c * outer,
                cy + s * outer,
                if (longTick) linePaint else faintPaint,
            )
        }

        // Zero mark at top (inside ring)
        canvas.drawLine(
            cx,
            cy - scaleR,
            cx,
            cy - scaleR + density * 12f,
            accentPaint,
        )

        // Roll pointer along the inner arc
        val rollRad = Math.toRadians((-displayRoll).toDouble() - 90.0)
        val tipR = scaleR - density * 2f
        val tipX = cx + cos(rollRad).toFloat() * tipR
        val tipY = cy + sin(rollRad).toFloat() * tipR
        pointerPath.reset()
        val leftRad = rollRad + Math.toRadians(12.0)
        val rightRad = rollRad - Math.toRadians(12.0)
        val baseR = tipR - density * 14f
        pointerPath.moveTo(tipX, tipY)
        pointerPath.lineTo(
            cx + cos(leftRad).toFloat() * baseR,
            cy + sin(leftRad).toFloat() * baseR,
        )
        pointerPath.lineTo(
            cx + cos(rightRad).toFloat() * baseR,
            cy + sin(rightRad).toFloat() * baseR,
        )
        pointerPath.close()
        canvas.drawPath(pointerPath, accentFill)

        // Fixed aircraft wings at center
        val wing = fillRadius * 0.38f
        canvas.drawLine(cx - wing, cy, cx - density * 8f, cy, accentPaint)
        canvas.drawLine(cx + density * 8f, cy, cx + wing, cy, accentPaint)
        canvas.drawCircle(cx, cy, density * 4f, accentFill)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}
