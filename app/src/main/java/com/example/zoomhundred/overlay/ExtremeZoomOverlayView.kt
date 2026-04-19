package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ExtremeZoomOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 210, 64)
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        strokeWidth = 1.7f
        style = Paint.Style.STROKE
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 80, 210, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 28f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 210, 64)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val trail = ArrayDeque<PointF>()
    private val path = Path()

    private var targetLocked = false
    private var targetX = 0f
    private var targetY = 0f
    private var driftX = 0f
    private var driftY = 0f
    private var zoomRatio = 1f
    private var fovH = 0f
    private var fovV = 0f
    private var cropOffsetX = 0f
    private var cropOffsetY = 0f

    fun lockTarget(x: Float, y: Float) {
        targetLocked = true
        targetX = x
        targetY = y
        driftX = 0f
        driftY = 0f
        trail.clear()
        invalidate()
    }

    fun clearTarget() {
        targetLocked = false
        driftX = 0f
        driftY = 0f
        trail.clear()
        invalidate()
    }

    fun updateDrift(dxPx: Float, dyPx: Float) {
        driftX = dxPx.coerceIn(-width.toFloat(), width.toFloat())
        driftY = dyPx.coerceIn(-height.toFloat(), height.toFloat())
        val centerX = width / 2f + driftX
        val centerY = height / 2f + driftY
        trail.addLast(PointF(centerX, centerY))
        while (trail.size > 56) trail.removeFirst()
        invalidate()
    }

    fun updateZoomInfo(zoom: Float, horizontalFovDeg: Float, verticalFovDeg: Float) {
        zoomRatio = zoom
        fovH = horizontalFovDeg
        fovV = verticalFovDeg
        invalidate()
    }

    fun updateCropOffset(x: Float, y: Float) {
        cropOffsetX = x.coerceIn(-1f, 1f)
        cropOffsetY = y.coerceIn(-1f, 1f)
        invalidate()
    }

    fun updateStability(level: Float, isStable: Boolean, pendingCapture: Boolean, progress: Float) {
        // Stability is shown by MainActivity's pill indicator, not by this preview overlay.
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        drawAngularRuler(canvas, w, h)
        drawDriftTrail(canvas)
        drawCenterMark(canvas, cx, cy)
        drawTarget(canvas, cx, cy)
        drawCropOffset(canvas, w, h)
    }

    private fun drawAngularRuler(canvas: Canvas, w: Float, h: Float) {
        if (fovH <= 0f || fovV <= 0f) return
        val y = h * 0.54f
        val tickCount = 8
        centerPaint.alpha = 130
        canvas.drawLine(w * 0.18f, y, w * 0.82f, y, centerPaint)
        for (i in 0..tickCount) {
            val x = w * 0.18f + (w * 0.64f) * i / tickCount
            val major = i == 0 || i == tickCount || i == tickCount / 2
            val len = if (major) 18f else 10f
            canvas.drawLine(x, y - len, x, y + len, centerPaint)
        }
        centerPaint.alpha = 255
        val label = if (fovH < 1f) {
            String.format(Locale.US, "%.1f arcmin", fovH * 60f)
        } else {
            String.format(Locale.US, "%.2f deg", fovH)
        }
        canvas.drawText(label, w * 0.18f, y - 24f, textPaint)
    }

    private fun drawDriftTrail(canvas: Canvas) {
        if (trail.size < 2) return
        path.reset()
        trail.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        trailPaint.alpha = 170
        canvas.drawPath(path, trailPaint)
    }

    private fun drawCenterMark(canvas: Canvas, cx: Float, cy: Float) {
        val r = 22f
        canvas.drawCircle(cx, cy, r, centerPaint)
        canvas.drawLine(cx - r * 1.7f, cy, cx - r * 0.7f, cy, centerPaint)
        canvas.drawLine(cx + r * 0.7f, cy, cx + r * 1.7f, cy, centerPaint)
        canvas.drawLine(cx, cy - r * 1.7f, cx, cy - r * 0.7f, centerPaint)
        canvas.drawLine(cx, cy + r * 0.7f, cx, cy + r * 1.7f, centerPaint)
    }

    private fun drawTarget(canvas: Canvas, cx: Float, cy: Float) {
        if (!targetLocked) return
        val tx = targetX
        val ty = targetY
        val currentX = tx + driftX
        val currentY = ty + driftY
        canvas.drawCircle(tx, ty, 34f, reticlePaint)
        canvas.drawLine(tx - 48f, ty, tx - 22f, ty, reticlePaint)
        canvas.drawLine(tx + 22f, ty, tx + 48f, ty, reticlePaint)
        canvas.drawLine(tx, ty - 48f, tx, ty - 22f, reticlePaint)
        canvas.drawLine(tx, ty + 22f, tx, ty + 48f, reticlePaint)
        if (abs(driftX) + abs(driftY) > 2f) {
            canvas.drawLine(tx, ty, currentX, currentY, reticlePaint)
            canvas.drawCircle(currentX, currentY, 10f, reticlePaint)
        }
        canvas.drawText(String.format(Locale.US, "LOCK %.1fx", zoomRatio), max(24f, cx - 95f), min(height - 32f, cy + 84f), textPaint)
    }

    private fun drawCropOffset(canvas: Canvas, w: Float, h: Float) {
        if (abs(cropOffsetX) < 0.02f && abs(cropOffsetY) < 0.02f) return
        val boxW = w * 0.22f
        val boxH = h * 0.14f
        val left = w - boxW - 24f
        val top = h * 0.58f
        canvas.drawRect(left, top, left + boxW, top + boxH, cropPaint)
        val dotX = left + boxW * (0.5f + cropOffsetX * 0.45f)
        val dotY = top + boxH * (0.5f + cropOffsetY * 0.45f)
        canvas.drawCircle(dotX, dotY, 8f, reticlePaint)
        canvas.drawText("CROP", left, top - 10f, textPaint)
    }

}
