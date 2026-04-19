package com.example.zoomhundred.camera

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import androidx.camera.core.CameraControl

/**
 * Animates zoom ratio changes smoothly.
 * Cancels the previous animation if a new target arrives mid-flight.
 */
class ZoomAnimator {

    enum class Speed(val durationMs: Long) {
        FAST(180), SMOOTH(450), CINEMATIC(900)
    }

    var speed: Speed = Speed.SMOOTH

    private var animator: ValueAnimator? = null

    /**
     * @param fromRatio current zoom ratio
     * @param toRatio   target zoom ratio
     * @param onUpdate  called each frame with the interpolated ratio
     */
    fun animateTo(fromRatio: Float, toRatio: Float, onUpdate: (Float) -> Unit) {
        animator?.cancel()
        if (fromRatio == toRatio) {
            onUpdate(toRatio)
            return
        }
        animator = ValueAnimator.ofFloat(fromRatio, toRatio).apply {
            duration = speed.durationMs
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { onUpdate(it.animatedValue as Float) }
            start()
        }
    }

    fun cancel() { animator?.cancel() }
}
