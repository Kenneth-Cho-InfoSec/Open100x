package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Small horizontal bar showing gyroscope shake level (0=still, 1=shaky).
 * Green when stable, amber/red when shaking.
 */
class ShakeMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val bgPaint = Paint().apply {
        color = Color.argb(80, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bgPaint)
        barPaint.color = when {
            level < 0.2f -> Color.argb(220, 80, 220, 80)   // green = stable
            level < 0.6f -> Color.argb(220, 255, 200, 0)   // amber
            else         -> Color.argb(220, 255, 60, 60)    // red
        }
        rect.set(0f, 0f, level * w, h)
        canvas.drawRoundRect(rect, r, r, barPaint)
    }
}
