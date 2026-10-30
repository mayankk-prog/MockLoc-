package com.example.mockloc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Simple on-screen joystick. Reports x/y in -1..1 (up on screen = negative y = north). */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onChange: ((Float, Float) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE1E88E5.toInt()
        style = Paint.Style.FILL
    }

    private var kx = 0f
    private var ky = 0f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 4f
        canvas.drawCircle(cx, cy, r, basePaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawCircle(cx + kx, cy + ky, r * 0.35f, knobPaint)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 4f
        val maxTravel = r * 0.65f

        when (ev.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = ev.x - cx
                val dy = ev.y - cy
                val dist = hypot(dx, dy)
                val scale = if (dist > maxTravel) maxTravel / dist else 1f
                kx = dx * scale
                ky = dy * scale
                onChange?.invoke(kx / maxTravel, ky / maxTravel)
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                kx = 0f
                ky = 0f
                onChange?.invoke(0f, 0f)
                invalidate()
                if (ev.action == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
