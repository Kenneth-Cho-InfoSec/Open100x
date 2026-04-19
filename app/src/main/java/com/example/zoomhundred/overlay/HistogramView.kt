package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.annotation.WorkerThread
import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.math.max

/**
 * Draws a live luma histogram.
 * Call [updateFromBitmap] on a background thread with a downsampled bitmap;
 * the view redraws on the main thread.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bins = IntArray(256)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val clipPaint = Paint().apply {
        color = Color.argb(200, 255, 80, 80)
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(100, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val path = Path()
    @Volatile private var maxBin = 1

    /** Call from background thread. */
    @WorkerThread
    fun updateFromBitmap(bmp: Bitmap) {
        val localBins = IntArray(256)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = ((0.299 * r + 0.587 * g + 0.114 * b).toInt()).coerceIn(0, 255)
            localBins[luma]++
        }
        val logBins = IntArray(256) { i ->
            if (localBins[i] > 0) (ln(localBins[i].toFloat()) * 1000).toInt() else 0
        }
        val newMax = max(1, logBins.max())
        synchronized(bins) {
            logBins.copyInto(bins)
            maxBin = newMax
        }
        post { invalidate() }
    }

    /** Call from background thread with the Y plane from an ImageAnalysis frame. */
    @WorkerThread
    fun updateFromLumaPlane(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int) {
        val localBins = IntArray(256)
        val xStep = max(1, width / 160)
        val yStep = max(1, height / 90)

        for (y in 0 until height step yStep) {
            val rowOffset = y * rowStride
            for (x in 0 until width step xStep) {
                val luma = buffer.get(rowOffset + x * pixelStride).toInt() and 0xFF
                localBins[luma]++
            }
        }

        val logBins = IntArray(256) { i ->
            if (localBins[i] > 0) (ln(localBins[i].toFloat()) * 1000).toInt() else 0
        }
        val newMax = max(1, logBins.max())
        synchronized(bins) {
            logBins.copyInto(bins)
            maxBin = newMax
        }
        post { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val localBins: IntArray
        val localMax: Int
        synchronized(bins) {
            localBins = bins.copyOf()
            localMax = maxBin
        }

        path.reset()
        path.moveTo(0f, h)
        for (i in 0..255) {
            val x = i / 255f * w
            val barH = localBins[i].toFloat() / localMax * h
            path.lineTo(x, h - barH)
        }
        path.lineTo(w, h)
        path.close()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        // Overexposure warning: red tint on bins 245–255
        val clipStart = 245 / 255f * w
        if (localBins.slice(245..255).any { it > 0 }) {
            canvas.drawRect(clipStart, 0f, w, h, clipPaint)
        }
    }
}
