package com.example.zoomhundred.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * All pro-mode settings, persisted in SharedPreferences.
 * Accessed and mutated from the main thread only.
 */
data class ProSettings(
    // Manual exposure
    var manualMode: Boolean = false,
    var isoIndex: Int = 0,          // index into ISO_VALUES
    var shutterIndex: Int = 0,      // index into SHUTTER_VALUES
    // White balance
    var wbMode: WbMode = WbMode.AUTO,
    var wbKelvin: Int = 5500,
    // Overlays
    var focusPeakingEnabled: Boolean = false,
    var histogramEnabled: Boolean = true,
    var gridMode: GridMode = GridMode.NONE,
    var hudEnabled: Boolean = true,
    // Anti-shake
    var antiShakeEnabled: Boolean = false,
    var antiShakeThreshold: Float = 0.05f,
    // Output
    var outputFormat: OutputFormat = OutputFormat.JPEG,
    var stripGpsExif: Boolean = false,
    var shutterSoundEnabled: Boolean = true,
    var shutterSoundVolume: Int = 80,
    var zoomPostProcessEnabled: Boolean = false,
    var zoomPostProcessStrength: Int = 55,
    // Zoom
    var zoomLocked: Boolean = false,
    // Night UI
    var nightUiEnabled: Boolean = false
) {
    companion object {
        val ISO_VALUES = intArrayOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
        /** Exposure times in nanoseconds */
        val SHUTTER_VALUES = longArrayOf(
            500_000_000L,   // 1/2
            250_000_000L,   // 1/4
            125_000_000L,   // 1/8
            62_500_000L,    // 1/16
            33_333_333L,    // 1/30
            16_666_667L,    // 1/60
            8_333_333L,     // 1/120
            4_000_000L,     // 1/250
            2_000_000L,     // 1/500
            1_000_000L,     // 1/1000
            500_000L,       // 1/2000
            250_000L        // 1/4000
        )
        val SHUTTER_LABELS = arrayOf(
            "1/2","1/4","1/8","1/16","1/30","1/60",
            "1/120","1/250","1/500","1/1000","1/2000","1/4000"
        )

        private const val PREF_FILE = "pro_settings"

        fun load(context: Context): ProSettings {
            val p = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            return ProSettings(
                manualMode = p.getBoolean("manualMode", false),
                isoIndex = p.getInt("isoIndex", 3),
                shutterIndex = p.getInt("shutterIndex", 5),
                wbMode = WbMode.entries[p.getInt("wbMode", 0)],
                wbKelvin = p.getInt("wbKelvin", 5500),
                focusPeakingEnabled = p.getBoolean("focusPeaking", false),
                histogramEnabled = p.getBoolean("histogram", true),
                gridMode = GridMode.entries[p.getInt("gridMode", 0)],
                hudEnabled = p.getBoolean("hud", true),
                antiShakeEnabled = p.getBoolean("antiShake", false),
                antiShakeThreshold = p.getFloat("antiShakeThreshold", 0.05f),
                outputFormat = OutputFormat.entries[p.getInt("outputFormat", 0)],
                stripGpsExif = p.getBoolean("stripGps", false),
                shutterSoundEnabled = p.getBoolean("shutterSound", true),
                shutterSoundVolume = p.getInt("shutterSoundVolume", 80).coerceIn(0, 100),
                zoomPostProcessEnabled = p.getBoolean("zoomPostProcess", false),
                zoomPostProcessStrength = p.getInt("zoomPostProcessStrength", 55).coerceIn(0, 100),
                zoomLocked = false,
                nightUiEnabled = p.getBoolean("nightUi", false)
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit {
            putBoolean("manualMode", manualMode)
            putInt("isoIndex", isoIndex)
            putInt("shutterIndex", shutterIndex)
            putInt("wbMode", wbMode.ordinal)
            putInt("wbKelvin", wbKelvin)
            putBoolean("focusPeaking", focusPeakingEnabled)
            putBoolean("histogram", histogramEnabled)
            putInt("gridMode", gridMode.ordinal)
            putBoolean("hud", hudEnabled)
            putBoolean("antiShake", antiShakeEnabled)
            putFloat("antiShakeThreshold", antiShakeThreshold)
            putInt("outputFormat", outputFormat.ordinal)
            putBoolean("stripGps", stripGpsExif)
            putBoolean("shutterSound", shutterSoundEnabled)
            putInt("shutterSoundVolume", shutterSoundVolume.coerceIn(0, 100))
            putBoolean("zoomPostProcess", zoomPostProcessEnabled)
            putInt("zoomPostProcessStrength", zoomPostProcessStrength.coerceIn(0, 100))
            putBoolean("nightUi", nightUiEnabled)
        }
    }
}

enum class WbMode { AUTO, DAYLIGHT, CLOUDY, SHADE, TUNGSTEN, FLUORESCENT, CUSTOM }
enum class GridMode { NONE, THIRDS, SQUARE, DIAGONAL }
enum class OutputFormat { JPEG, WEBP }
