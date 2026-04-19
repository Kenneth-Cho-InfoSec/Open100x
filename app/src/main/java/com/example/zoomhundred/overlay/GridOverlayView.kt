package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.zoomhundred.model.GridMode

class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var gridMode: GridMode = GridMode.NONE
        set(value) {
            field = value
            invalidate()
        }

    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        if (gridMode == GridMode.NONE) return
        val w = width.toFloat()
        val h = height.toFloat()
        when (gridMode) {
            GridMode.THIRDS -> drawThirds(canvas, w, h)
            GridMode.SQUARE -> drawSquare(canvas, w, h)
            GridMode.DIAGONAL -> drawDiagonal(canvas, w, h)
            GridMode.NONE -> Unit
        }
    }

    private fun drawThirds(canvas: Canvas, w: Float, h: Float) {
        canvas.drawLine(w / 3, 0f, w / 3, h, solidPaint)
        canvas.drawLine(w * 2 / 3, 0f, w * 2 / 3, h, solidPaint)
        canvas.drawLine(0f, h / 3, w, h / 3, solidPaint)
        canvas.drawLine(0f, h * 2 / 3, w, h * 2 / 3, solidPaint)
        // centre cross
        canvas.drawLine(w / 2 - 20f, h / 2, w / 2 + 20f, h / 2, centerPaint)
        canvas.drawLine(w / 2, h / 2 - 20f, w / 2, h / 2 + 20f, centerPaint)
    }

    private fun drawSquare(canvas: Canvas, w: Float, h: Float) {
        val size = minOf(w, h)
        val left = (w - size) / 2
        val top = (h - size) / 2
        canvas.drawRect(left, top, left + size, top + size, dashedPaint)
        drawThirds(canvas, w, h)
    }

    private fun drawDiagonal(canvas: Canvas, w: Float, h: Float) {
        canvas.drawLine(0f, 0f, w, h, solidPaint)
        canvas.drawLine(w, 0f, 0f, h, solidPaint)
        canvas.drawLine(0f, h / 2, w, h / 2, dashedPaint)
        canvas.drawLine(w / 2, 0f, w / 2, h, dashedPaint)
    }
}
