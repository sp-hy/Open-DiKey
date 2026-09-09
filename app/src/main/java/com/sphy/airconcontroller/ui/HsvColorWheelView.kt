package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Hue/saturation wheel. Value (brightness) is applied by the parent card. */
class HsvColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val hsv = floatArrayOf(24f, 1f, 1f)
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val pointerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#33000000")
    }

    private var radius = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var tracking = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    var onHueSatChanged: ((hue: Float, sat: Float) -> Unit)? = null
    var onTrackingStopped: (() -> Unit)? = null

    fun setHueSat(hue: Float, sat: Float, fromUser: Boolean = false) {
        hsv[0] = hue.mod(360f)
        hsv[1] = sat.coerceIn(0f, 1f)
        invalidate()
        if (fromUser) onHueSatChanged?.invoke(hsv[0], hsv[1])
    }

    val hue: Float get() = hsv[0]
    val saturation: Float get() = hsv[1]

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            wMode == MeasureSpec.UNSPECIFIED && hMode == MeasureSpec.UNSPECIFIED ->
                (160 * resources.displayMetrics.density).toInt()
            wMode == MeasureSpec.UNSPECIFIED -> hSize
            hMode == MeasureSpec.UNSPECIFIED -> wSize
            else -> min(wSize, hSize)
        }.coerceAtLeast(1)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        val pad = pointerStroke.strokeWidth * 2.5f
        radius = min(w, h) / 2f - pad
        val hueColors = IntArray(13) { i ->
            Color.HSVToColor(floatArrayOf(i * 30f, 1f, 1f))
        }
        hueColors[12] = hueColors[0]
        val sweep = SweepGradient(centerX, centerY, hueColors, null)
        val radial = RadialGradient(
            centerX,
            centerY,
            radius,
            Color.WHITE,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        wheelPaint.shader = ComposeShader(sweep, radial, PorterDuff.Mode.SRC_OVER)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, radius, wheelPaint)
        val angle = Math.toRadians(hsv[0].toDouble())
        val dist = hsv[1] * radius
        val px = centerX + (cos(angle) * dist).toFloat()
        val py = centerY + (sin(angle) * dist).toFloat()
        pointerFill.color = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
        val pr = 10f * resources.displayMetrics.density
        canvas.drawCircle(px, py, pr, pointerFill)
        canvas.drawCircle(px, py, pr, pointerStroke)
        canvas.drawCircle(px, py, pr, pointerRim)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                tracking = true
                applyTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking) applyTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    tracking = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    onTrackingStopped?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTouch(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val hue = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
        val sat = (dist / radius).coerceIn(0f, 1f)
        setHueSat(hue, sat, fromUser = true)
    }
}
