package com.example.zoomhundred.imaging

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.example.zoomhundred.model.OutputFormat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ExtremeZoomPostProcessor {

    private const val MAX_OUTPUT_PIXELS = 24_000_000

    fun process(
        contentResolver: ContentResolver,
        uri: Uri,
        outputFormat: OutputFormat,
        strengthPercent: Int
    ) {
        val sliderStrength = strengthPercent.coerceIn(0, 100) / 100f
        if (sliderStrength <= 0f) return

        val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
        val exifOrientation = contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val oriented = rotateBitmapForExif(source, exifOrientation)
        if (oriented != source) source.recycle()

        val enhanced = enhance(oriented, sliderStrength)
        if (enhanced != oriented) oriented.recycle()

        val format = if (outputFormat == OutputFormat.WEBP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val quality = if (outputFormat == OutputFormat.WEBP) 92 else 96
        contentResolver.openOutputStream(uri, "wt")?.use { out ->
            enhanced.compress(format, quality, out)
        }
        contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
            ExifInterface(fd.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
        }
        enhanced.recycle()
    }

    private fun enhance(src: Bitmap, strength: Float): Bitmap {
        val argb = if (src.config == Bitmap.Config.ARGB_8888) src.copy(Bitmap.Config.ARGB_8888, false) else src.copy(Bitmap.Config.ARGB_8888, false)

        val strongCurve = strength * strength
        val smoothScale = (1f - 0.10f * strength - 0.48f * strongCurve).coerceIn(0.42f, 0.97f)
        val smoothW = max(1, (argb.width * smoothScale).toInt())
        val smoothH = max(1, (argb.height * smoothScale).toInt())
        val denoisedSmall = Bitmap.createScaledBitmap(argb, smoothW, smoothH, true)
        val denoised = Bitmap.createScaledBitmap(denoisedSmall, argb.width, argb.height, true)
        denoisedSmall.recycle()

        val upscaled = maybeUpscale(denoised, strength)
        if (upscaled != denoised) denoised.recycle()

        applyContrastAndSharpen(upscaled, strength)
        argb.recycle()
        return upscaled
    }

    private fun maybeUpscale(src: Bitmap, strength: Float): Bitmap {
        if (strength < 0.25f) return src
        val currentPixels = src.width.toLong() * src.height.toLong()
        val strongCurve = strength * strength
        val requestedScale = 1f + 0.15f * strength + 0.90f * strongCurve
        val capScale = sqrt(MAX_OUTPUT_PIXELS / currentPixels.toFloat()).coerceAtLeast(1f)
        val scale = min(requestedScale, capScale)
        if (scale < 1.04f) return src
        val outW = (src.width * scale).toInt().coerceAtLeast(src.width)
        val outH = (src.height * scale).toInt().coerceAtLeast(src.height)
        return Bitmap.createScaledBitmap(src, outW, outH, true)
    }

    private fun applyContrastAndSharpen(bitmap: Bitmap, strength: Float) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val blurSmall = Bitmap.createScaledBitmap(bitmap, max(1, width / 3), max(1, height / 3), true)
        val blurBitmap = Bitmap.createScaledBitmap(blurSmall, width, height, true)
        blurSmall.recycle()
        val blurPixels = IntArray(width * height)
        blurBitmap.getPixels(blurPixels, 0, width, 0, 0, width, height)
        blurBitmap.recycle()

        val strongCurve = strength * strength
        val contrast = 1f + 0.12f * strength + 0.72f * strongCurve
        val detail = 0.35f + 0.85f * strength + 3.90f * strongCurve
        val shadowLift = (8f * strength + 28f * strongCurve).toInt()
        val highlightGuard = (6f * strength + 22f * strongCurve).toInt()

        for (i in pixels.indices) {
            val p = pixels[i]
            val b = blurPixels[i]
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var blue = p and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF

            val localR = (r - br)
            val localG = (g - bg)
            val localB = (blue - bb)
            r = (((r - 128) * contrast + 128) + localR * detail).toInt()
            g = (((g - 128) * contrast + 128) + localG * detail).toInt()
            blue = (((blue - 128) * contrast + 128) + localB * detail).toInt()

            val luma = ((r * 299 + g * 587 + blue * 114) / 1000).coerceIn(0, 255)
            if (luma < 80) {
                r += shadowLift
                g += shadowLift
                blue += shadowLift
            } else if (luma > 220) {
                r -= highlightGuard
                g -= highlightGuard
                blue -= highlightGuard
            }

            pixels[i] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun rotateBitmapForExif(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f, 1f); postRotate(270f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(90f) }
                else -> return src
            }
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
