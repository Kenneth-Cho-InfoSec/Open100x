package com.example.zoomhundred.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import com.example.zoomhundred.model.ProSettings
import com.example.zoomhundred.model.WbMode

private const val TAG = "ProCameraController"

/**
 * Applies manual camera2 settings (ISO, shutter speed, white balance)
 * onto CameraX Preview and ImageCapture builders via Camera2Interop.
 */
object ProCameraController {

    /**
     * Inject Camera2Interop options into the Preview builder.
     * Call before provider.bindToLifecycle().
     */
    fun applyToPreviewBuilder(builder: Preview.Builder, settings: ProSettings) {
        val ext = Camera2Interop.Extender(builder)
        applyExposure(ext, settings)
        applyWhiteBalance(ext, settings)
    }

    /**
     * Inject Camera2Interop options into the ImageCapture builder.
     */
    fun applyToCapturBuilder(builder: ImageCapture.Builder, settings: ProSettings) {
        val ext = Camera2Interop.Extender(builder)
        applyExposure(ext, settings)
        applyWhiteBalance(ext, settings)
    }

    private fun <T> applyExposure(ext: Camera2Interop.Extender<T>, settings: ProSettings) {
        if (settings.manualMode) {
            val iso = ProSettings.ISO_VALUES[settings.isoIndex.coerceIn(0, ProSettings.ISO_VALUES.lastIndex)]
            val shutter = ProSettings.SHUTTER_VALUES[settings.shutterIndex.coerceIn(0, ProSettings.SHUTTER_VALUES.lastIndex)]
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
        } else {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
    }

    private fun <T> applyWhiteBalance(ext: Camera2Interop.Extender<T>, settings: ProSettings) {
        val awbMode = when (settings.wbMode) {
            WbMode.AUTO        -> CameraMetadata.CONTROL_AWB_MODE_AUTO
            WbMode.DAYLIGHT    -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            WbMode.CLOUDY      -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            WbMode.SHADE       -> CameraMetadata.CONTROL_AWB_MODE_SHADE
            WbMode.TUNGSTEN    -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
            WbMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
            WbMode.CUSTOM      -> CameraMetadata.CONTROL_AWB_MODE_AUTO // lock handled separately
        }
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
    }

    /**
     * Queries hardware capabilities for the bound camera.
     * Returns a [CameraCapabilities] object for gating UI features.
     */
    fun queryCapabilities(camera: Camera): CameraCapabilities {
        return try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val oisModes = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
            CameraCapabilities(
                supportsManualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                isoRange = isoRange,
                exposureTimeRange = exposureRange,
                supportsOis = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query capabilities: ${e.message}")
            CameraCapabilities()
        }
    }

    /**
     * Applies OIS mode via CameraControl after binding.
     */
    fun applyOisToPreviewBuilder(builder: Preview.Builder, oisOn: Boolean) {
        val mode = if (oisOn)
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        else
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
    }
}

data class CameraCapabilities(
    val supportsManualSensor: Boolean = false,
    val supportsRaw: Boolean = false,
    val isoRange: android.util.Range<Int>? = null,
    val exposureTimeRange: android.util.Range<Long>? = null,
    val supportsOis: Boolean = false
)
