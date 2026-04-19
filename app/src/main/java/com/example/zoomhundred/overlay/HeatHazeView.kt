package com.example.zoomhundred.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.annotation.WorkerThread
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

class HeatHazeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        alpha = 185
    }
    @Volatile private var edgeBitmap: Bitmap? = null

    @WorkerThread
    fun updateFromLumaPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ) {
        val outW = 160
        val outH = max(1, (height * outW / width.toFloat()).toInt())
        val luma = IntArray(outW * outH)
        val pixels = IntArray(outW * outH)

        for (y in 0 until outH) {
            val srcY = y * height / outH
            val row = srcY * rowStride
            for (x in 0 until outW) {
                val srcX = x * width / outW
                luma[y * outW + x] = buffer.get(row + srcX * pixelStride).toInt() and 0xFF
            }
        }

        for (y in 1 until outH - 1) {
            for (x in 1 until outW - 1) {
                val center = luma[y * outW + x]
                val gx = abs(luma[y * outW + x + 1] - luma[y * outW + x - 1])
                val gy = abs(luma[(y + 1) * outW + x] - luma[(y - 1) * outW + x])
                val edge = ((gx + gy) * 2).coerceIn(0, 255)
                val contrast = (center + edge / 2).coerceIn(0, 255)
                val alpha = if (edge > 18) (70 + edge / 2).coerceIn(0, 190) else 0
                pixels[y * outW + x] = (alpha shl 24) or (contrast shl 16) or ((255 - contrast / 3) shl 8) or 255
            }
        }

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        val displayBitmap = bitmap.rotateForDisplay(rotationDegrees)
        if (displayBitmap != bitmap) bitmap.recycle()
        post {
            val old = edgeBitmap
            edgeBitmap = displayBitmap
            old?.recycle()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        edgeBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
        }
    }

    private fun Bitmap.rotateForDisplay(rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return this
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}
