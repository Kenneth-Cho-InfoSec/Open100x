package com.example.zoomhundred.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * Monitors gyroscope magnitude.
 * When motion stays below [thresholdRadPerSec] for [quietWindowMs],
 * calls [onStable]. Calls [onShakeLevel] continuously with a 0..1 shake indicator.
 *
 * Usage:
 *   controller.start(threshold=0.05f)
 *   controller.stop()
 */
class AntiShakeController(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mainHandler = Handler(Looper.getMainLooper())

    var onShakeLevel: ((Float) -> Unit)? = null   // 0=perfectly still, 1=very shaky
    var onStable: (() -> Unit)? = null             // fired once per stable window

    private var threshold = 0.05f
    private var quietWindowMs = 150L
    private var quietSince = 0L
    private var stableFired = false
    private var running = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val mag = sqrt(
                event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
            )
            val normalised = (mag / (threshold * 10f)).coerceIn(0f, 1f)
            mainHandler.post { onShakeLevel?.invoke(normalised) }

            if (mag < threshold) {
                if (quietSince == 0L) quietSince = System.currentTimeMillis()
                val quietFor = System.currentTimeMillis() - quietSince
                if (quietFor >= quietWindowMs && !stableFired) {
                    stableFired = true
                    mainHandler.post { onStable?.invoke() }
                }
            } else {
                quietSince = 0L
                stableFired = false
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    val isAvailable: Boolean get() = gyro != null

    fun start(threshold: Float = 0.05f, quietWindowMs: Long = 150L) {
        if (running || gyro == null) return
        this.threshold = threshold
        this.quietWindowMs = quietWindowMs
        quietSince = 0L
        stableFired = false
        running = true
        sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(listener)
        mainHandler.post { onShakeLevel?.invoke(0f) }
    }
}
