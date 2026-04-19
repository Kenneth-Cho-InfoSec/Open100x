package com.example.zoomhundred

import android.Manifest
import android.media.AudioManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.example.zoomhundred.camera.AntiShakeController
import com.example.zoomhundred.camera.CameraCapabilities
import com.example.zoomhundred.camera.ProCameraController
import com.example.zoomhundred.camera.ZoomAnimator
import com.example.zoomhundred.databinding.ActivityMainBinding
import com.example.zoomhundred.imaging.ExifUtils
import com.example.zoomhundred.imaging.ExtremeZoomPostProcessor
import com.example.zoomhundred.model.GridMode
import com.example.zoomhundred.model.OutputFormat
import com.example.zoomhundred.model.ProSettings
import com.example.zoomhundred.model.ZoomPreset
import com.example.zoomhundred.model.ZoomPresets
import com.example.zoomhundred.ui.ProSettingsSheet
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val HISTOGRAM_FRAME_INTERVAL_MS = 100L
private const val HEAT_HAZE_FRAME_INTERVAL_MS = 120L
private const val SCOPE_FRAME_INTERVAL_MS = 220L
private const val ZOOM_EPSILON = 0.01f
private const val EXTREME_CAPTURE_ZOOM = 15f
private const val POST_PROCESS_MIN_ZOOM = 30f
private const val SETTLED_CAPTURE_MIN_DELAY_MS = 320L
private const val SETTLED_CAPTURE_TIMEOUT_MS = 2600L
private const val GESTURE_PREFS = "gesture_prefs"
private const val GESTURE_ONBOARDING_SHOWN = "gesture_onboarding_shown"
private const val DOUBLE_TAP_TIMEOUT_MS = 280L

private enum class CameraGestureMode {
    NONE,
    RIGHT_EDGE_ZOOM,
    RIGHT_EDGE_LENS,
    BOTTOM_RAIL,
    BOTTOM_DRAWER,
    TOP_HUD,
    LEFT_FLASH,
    LEFT_INFO,
    CORNER_HEAT,
    CORNER_BRACKET,
    PREVIEW_PAN
}

fun isGooglePixelDevice(): Boolean {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()
    val model = Build.MODEL.lowercase()
    val device = Build.DEVICE.lowercase()
    val product = Build.PRODUCT.lowercase()
    val googleHardware = manufacturer == "google" || brand == "google"
    val pixelName = model.contains("pixel") || device.contains("pixel") || product.contains("pixel")
    return googleHardware && pixelName
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pixelPortraitOnly = isGooglePixelDevice()
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var sensorManager: SensorManager
    private var gyroSensor: Sensor? = null

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensOptions: List<LensOption> = emptyList()
    private var currentLensIndex: Int = 0

    private var requestedZoom = 1f
    private var nativeZoomCap = 1f
    private var minNativeZoom = 1f
    private var lastAppliedNativeZoom = 1f
    private var pendingNativeZoom: Float? = null
    private var nativeZoomInFlight = false
    private var torchEnabled = false
    private var currentDigitalFactor = 1f
    private var digitalCropOffsetX = 0f
    private var digitalCropOffsetY = 0f
    private var baseHorizontalFovDeg = 0f
    private var baseVerticalFovDeg = 0f
    private val zoomAnimator = ZoomAnimator()

    private lateinit var proSettings: ProSettings
    private var capabilities = CameraCapabilities()
    private lateinit var antiShake: AntiShakeController
    private var antiShakeArmed = false
    @Volatile private var histogramEnabledForAnalyzer = true
    @Volatile private var heatHazeEnabled = false
    private var lastHistogramUpdateMs = 0L
    private var lastHeatHazeUpdateMs = 0L
    private var lastScopeFrameUpdateMs = 0L
    private var latestScopeBitmap: Bitmap? = null
    private val scopeBitmapLock = Any()
    private var freezeScopeActive = false
    private var displayedFreezeBitmap: Bitmap? = null
    private var gyroMagnitude = 0f
    private var stableSinceMs = 0L
    private var lastGyroTimestampNs = 0L
    private var aimDriftXRad = 0f
    private var aimDriftYRad = 0f
    private var lastOverlaySensorUpdateMs = 0L
    private var settledCapturePending = false
    private var settledCaptureStartedMs = 0L
    private val settledCaptureRunnable = object : Runnable {
        override fun run() {
            updateSettledCapture()
        }
    }
    private val zoomRailStops = floatArrayOf(1f, 8f, 25f, 50f, 100f)
    private val mainZoomStops = listOf(
        ZoomPreset("1x", 1f),
        ZoomPreset("10x", 10f),
        ZoomPreset("30x", 30f),
        ZoomPreset("100.0x", 100f)
    )
    private var railIndex = 0
    private var focusSweepRunning = false
    private var zoomPresets: MutableList<ZoomPreset> = mutableListOf()
    private val presetButtons: MutableList<MaterialButton> = mutableListOf()
    private var toolsDrawerOpen = false
    private var gestureMode = CameraGestureMode.NONE
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureStartZoom = 1f
    private var gestureDownTimeMs = 0L
    private var maxGesturePointerCount = 0
    private var freezeGestureActive = false
    private var previewLongPressArmed = false
    private var lastPreviewTapMs = 0L
    private val previewLongPressRunnable = Runnable {
        if (previewLongPressArmed && gestureMode == CameraGestureMode.NONE && maxGesturePointerCount <= 1) {
            freezeGestureActive = true
            haptic(HapticFeedbackConstants.LONG_PRESS)
            showFreezeScope()
        }
    }
    private var shutterDownX = 0f
    private var shutterDownY = 0f
    private var shutterLongPressFired = false
    private var drawerDownX = 0f
    private var drawerDownY = 0f
    private var zoomWheelDownX = 0f
    private var zoomWheelDownY = 0f
    private var zoomWheelStartZoom = 1f
    private var zoomWheelLongPressed = false
    private var zoomWheelMoved = false
    private var topPanelBaseHeight = 0
    private var topPanelBasePaddingTop = 0
    private var lastTargetRotation = Int.MIN_VALUE
    private var rotationUpdateQueued = false
    private val shutterLongPressRunnable = Runnable {
        shutterLongPressFired = true
        haptic(HapticFeedbackConstants.LONG_PRESS)
        if (requestedZoom >= EXTREME_CAPTURE_ZOOM && !settledCapturePending) {
            beginSettledCapture()
        } else {
            onCaptureClicked()
        }
    }
    private val zoomWheelLongPressRunnable = Runnable {
        zoomWheelLongPressed = true
        haptic(HapticFeedbackConstants.LONG_PRESS)
        setToolsDrawerOpen(open = true, animate = true)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show(); finish() }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        if (pixelPortraitOnly) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        orientationListener = createOrientationListener()
        proSettings = ProSettings.load(this)
        antiShake = AntiShakeController(this)
        zoomPresets = ZoomPresets.load(this)
        setupUi()
        applySettingsToUi()
        ensurePermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        if (!pixelPortraitOnly && orientationListener.canDetectOrientation()) orientationListener.enable()
        registerExtremeSensors()
        if (!pixelPortraitOnly) updateUseCaseRotations()
        if (proSettings.antiShakeEnabled) startAntiShake()
    }

    override fun onPause() {
        cancelSettledCapture()
        antiShake.stop()
        unregisterExtremeSensors()
        if (!pixelPortraitOnly) orientationListener.disable()
        proSettings.save(this)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        if (!pixelPortraitOnly) orientationListener.disable()
        cameraExecutor.shutdown()
        zoomAnimator.cancel()
        mainHandler.removeCallbacks(settledCaptureRunnable)
        synchronized(scopeBitmapLock) {
            latestScopeBitmap?.recycle()
            latestScopeBitmap = null
        }
        displayedFreezeBitmap?.recycle()
        displayedFreezeBitmap = null
    }

    // ── Volume key zoom nudge ─────────────────────────────────────────────────

    private val extremeSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
            val nowMs = SystemClock.uptimeMillis()
            if (lastGyroTimestampNs != 0L) {
                val dt = (event.timestamp - lastGyroTimestampNs) / 1_000_000_000f
                if (dt in 0f..0.08f) {
                    aimDriftXRad += -event.values[1] * dt
                    aimDriftYRad += event.values[0] * dt
                }
            }
            lastGyroTimestampNs = event.timestamp

            gyroMagnitude = sqrt(
                event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
            )

            if (gyroMagnitude < stabilityThreshold()) {
                if (stableSinceMs == 0L) stableSinceMs = nowMs
            } else {
                stableSinceMs = 0L
            }

            if (nowMs - lastOverlaySensorUpdateMs >= 16L) {
                lastOverlaySensorUpdateMs = nowMs
                updateExtremeOverlay()
                updateCaptureStabilityUi()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun registerExtremeSensors() {
        lastGyroTimestampNs = 0L
        gyroSensor?.let { sensorManager.registerListener(extremeSensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun unregisterExtremeSensors() {
        sensorManager.unregisterListener(extremeSensorListener)
        lastGyroTimestampNs = 0L
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (proSettings.zoomLocked) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP   -> { nudgeZoom(+0.5f); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { nudgeZoom(-0.5f); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun nudgeZoom(delta: Float) {
        animateZoomTo((requestedZoom + delta).coerceIn(1f, 100f))
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupUi() {
        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !proSettings.zoomLocked) {
                zoomAnimator.cancel()
                setZoomImmediately(value, syncSlider = false)
            }
        }
        binding.resetZoomButton.setOnClickListener   { openAppInfo() }
        binding.switchLensButton.setOnClickListener  { switchLens() }
        binding.switchLensButton.setOnLongClickListener {
            switchToFrontCamera()
            true
        }
        binding.captureButton.setOnTouchListener { _, event -> handleShutterTouch(event) }
        binding.lockZoomButton.setOnClickListener    { toggleZoomLock() }
        binding.flashlightButton.setOnClickListener  { toggleFlashlight() }
        binding.proSettingsButton.setOnClickListener { openProSettings() }
        binding.gridButton.setOnClickListener        { cycleGrid() }
        binding.gestureLayer.setOnTouchListener { _, event -> handleCameraGesture(event) }
        binding.gestureHintButton.setOnClickListener { showGestureHints(force = true) }
        binding.bottomPanel.setOnTouchListener { _, event -> handleDrawerTouch(event) }
        binding.zoomValueText.setOnTouchListener { _, event -> handleZoomWheelTouch(event) }
        setupSystemInsets()
        buildPresetBar()
        bringControlUiToFront()
        setToolsDrawerOpen(open = false, animate = false)
        showGestureHints(force = false)
    }

    private fun bringControlUiToFront() {
        binding.sidePanel?.bringToFront()
        binding.topPanel.bringToFront()
        binding.bottomPanel.bringToFront()
        if (isPortraitUi()) {
            binding.bottomControlsBar?.bringToFront()
            binding.bottomPanel.bringToFront()
        }
    }

    private fun setupSystemInsets() {
        topPanelBaseHeight = binding.topPanel.layoutParams.height
        topPanelBasePaddingTop = binding.topPanel.paddingTop
        val basePaddingLeft = binding.topPanel.paddingLeft
        val basePaddingRight = binding.topPanel.paddingRight
        val basePaddingBottom = binding.topPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val portrait = isPortraitUi()
            val extraTop = if (portrait) statusTop else 0
            binding.topPanel.setPadding(
                basePaddingLeft,
                topPanelBasePaddingTop + extraTop,
                basePaddingRight,
                basePaddingBottom
            )
            if (topPanelBaseHeight > 0) {
                binding.topPanel.layoutParams = binding.topPanel.layoutParams.apply {
                    height = topPanelBaseHeight + extraTop
                }
            }
            insets
        }
    }

    private fun handleZoomWheelTouch(event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                binding.zoomValueText.parent?.requestDisallowInterceptTouchEvent(true)
                zoomWheelDownX = event.x
                zoomWheelDownY = event.y
                zoomWheelStartZoom = requestedZoom
                zoomWheelLongPressed = false
                zoomWheelMoved = false
                mainHandler.postDelayed(
                    zoomWheelLongPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong()
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - zoomWheelDownX
                val dy = event.y - zoomWheelDownY
                if (!zoomWheelLongPressed && abs(dx) > slop && abs(dx) > abs(dy) * 1.15f) {
                    zoomWheelMoved = true
                    mainHandler.removeCallbacks(zoomWheelLongPressRunnable)
                    if (!proSettings.zoomLocked) {
                        zoomAnimator.cancel()
                        setZoomImmediately(zoomWheelStartZoom + preciseZoomDelta(dx), syncSlider = true)
                    }
                } else if (sqrt(dx * dx + dy * dy) > slop * 2f) {
                    mainHandler.removeCallbacks(zoomWheelLongPressRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                binding.zoomValueText.parent?.requestDisallowInterceptTouchEvent(false)
                mainHandler.removeCallbacks(zoomWheelLongPressRunnable)
                if (!zoomWheelMoved && !zoomWheelLongPressed) {
                    binding.zoomValueText.performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                binding.zoomValueText.parent?.requestDisallowInterceptTouchEvent(false)
                mainHandler.removeCallbacks(zoomWheelLongPressRunnable)
                return true
            }
        }
        return true
    }

    private fun preciseZoomDelta(dxPx: Float): Float {
        val dxDp = dxPx / resources.displayMetrics.density
        val sensitivity = when {
            zoomWheelStartZoom < 10f -> 0.035f
            zoomWheelStartZoom < 30f -> 0.08f
            else -> 0.14f
        }
        return dxDp * sensitivity
    }

    private fun handleDrawerTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerDownX = event.x
                drawerDownY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - drawerDownX
                val dy = event.y - drawerDownY
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (dy > slop * 4 && abs(dy) > abs(dx)) {
                    setToolsDrawerOpen(open = false, animate = true)
                    return true
                }
            }
        }
        return false
    }

    private fun handleShutterTouch(event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                shutterDownX = event.x
                shutterDownY = event.y
                shutterLongPressFired = false
                mainHandler.postDelayed(
                    shutterLongPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong()
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - shutterDownX
                val dy = event.y - shutterDownY
                if (!shutterLongPressFired && -dy > slop * 3 && abs(dy) > abs(dx) * 1.2f) {
                    mainHandler.removeCallbacks(shutterLongPressRunnable)
                    shutterLongPressFired = true
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    openProSettings()
                } else if (sqrt(dx * dx + dy * dy) > slop * 3) {
                    mainHandler.removeCallbacks(shutterLongPressRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(shutterLongPressRunnable)
                if (!shutterLongPressFired) {
                    binding.captureButton.performClick()
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    onCaptureClicked()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(shutterLongPressRunnable)
                return true
            }
        }
        return true
    }

    private fun handleCameraGesture(event: MotionEvent): Boolean {
        val width = binding.gestureLayer.width.toFloat().coerceAtLeast(1f)
        val height = binding.gestureLayer.height.toFloat().coerceAtLeast(1f)
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            maxGesturePointerCount = max(maxGesturePointerCount, event.pointerCount)
            mainHandler.removeCallbacks(previewLongPressRunnable)
            previewLongPressArmed = false
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                gestureMode = CameraGestureMode.NONE
                gestureDownX = event.x
                gestureDownY = event.y
                gestureStartZoom = requestedZoom
                gestureDownTimeMs = SystemClock.uptimeMillis()
                maxGesturePointerCount = event.pointerCount
                freezeGestureActive = false
                previewLongPressArmed = true
                mainHandler.postDelayed(
                    previewLongPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong()
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                maxGesturePointerCount = max(maxGesturePointerCount, event.pointerCount)
                val dx = event.x - gestureDownX
                val dy = event.y - gestureDownY
                if (sqrt(dx * dx + dy * dy) > slop * 1.5f) {
                    mainHandler.removeCallbacks(previewLongPressRunnable)
                    previewLongPressArmed = false
                    if (freezeGestureActive) {
                        hideFreezeScope()
                        freezeGestureActive = false
                    }
                }

                if (toolsDrawerOpen && dy > slop * 4 && abs(dy) > abs(dx)) {
                    setToolsDrawerOpen(open = false, animate = true)
                    pulseEdgeGlow(binding.edgeGlowBottom)
                    return true
                }

                if (gestureMode == CameraGestureMode.NONE) {
                    gestureMode = resolveGestureMode(dx, dy, slop)
                }

                when (gestureMode) {
                    CameraGestureMode.RIGHT_EDGE_ZOOM -> {
                        if (!proSettings.zoomLocked) {
                            val zoomDelta = (-dy / height) * 120f
                            zoomAnimator.cancel()
                            setZoomImmediately(gestureStartZoom + zoomDelta, syncSlider = true)
                            showEdgeGlow(binding.edgeGlowRight)
                        }
                    }
                    CameraGestureMode.BOTTOM_DRAWER -> {
                        setToolsDrawerOpen(open = true, animate = true)
                        showEdgeGlow(binding.edgeGlowBottom)
                    }
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(previewLongPressRunnable)
                previewLongPressArmed = false
                val dx = event.x - gestureDownX
                val dy = event.y - gestureDownY
                val distance = sqrt(dx * dx + dy * dy)

                if (freezeGestureActive) {
                    hideFreezeScope()
                    freezeGestureActive = false
                    return true
                }

                if (toolsDrawerOpen && distance < slop) {
                    setToolsDrawerOpen(open = false, animate = true)
                    return true
                }

                if (maxGesturePointerCount >= 3) {
                    handleThreeFingerGesture(dx, dy, slop)
                    return true
                }
                if (maxGesturePointerCount >= 2) {
                    handleTwoFingerGesture(dx, dy, slop)
                    return true
                }

                val finalMode = if (gestureMode == CameraGestureMode.NONE) {
                    resolveGestureMode(dx, dy, slop)
                } else {
                    gestureMode
                }
                handleCompletedGesture(finalMode, width, event.x, event.y, dx, distance, slop)
                gestureMode = CameraGestureMode.NONE
                hideEdgeGlows()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(previewLongPressRunnable)
                previewLongPressArmed = false
                if (freezeGestureActive) hideFreezeScope()
                freezeGestureActive = false
                gestureMode = CameraGestureMode.NONE
                hideEdgeGlows()
                return true
            }
        }
        return true
    }

    private fun resolveGestureMode(dx: Float, dy: Float, slop: Float): CameraGestureMode {
        if (abs(dx) < slop && abs(dy) < slop) return CameraGestureMode.NONE
        if (maxGesturePointerCount >= 2) return CameraGestureMode.PREVIEW_PAN
        return CameraGestureMode.NONE
    }

    private fun handleCompletedGesture(
        mode: CameraGestureMode,
        width: Float,
        upX: Float,
        upY: Float,
        dx: Float,
        distance: Float,
        slop: Float
    ) {
        when (mode) {
            CameraGestureMode.RIGHT_EDGE_LENS -> {
                val longSwitch = max(dp(180f), width * 0.28f)
                if (-dx >= longSwitch) switchToFrontCamera() else switchLens()
                pulseEdgeGlow(binding.edgeGlowRight)
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            CameraGestureMode.BOTTOM_RAIL -> {
                val direction = if (dx < 0f) +1 else -1
                advanceZoomRail(direction)
                pulseEdgeGlow(binding.edgeGlowBottom)
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            CameraGestureMode.TOP_HUD -> {
                toggleHudHistogram()
                pulseEdgeGlow(binding.edgeGlowTop)
            }
            CameraGestureMode.LEFT_FLASH -> {
                toggleFlashlight()
                pulseEdgeGlow(binding.edgeGlowLeft)
            }
            CameraGestureMode.LEFT_INFO -> {
                openAppInfo()
                pulseEdgeGlow(binding.edgeGlowLeft)
            }
            CameraGestureMode.CORNER_HEAT -> {
                toggleHeatHaze()
                pulseEdgeGlow(binding.edgeGlowLeft)
            }
            CameraGestureMode.CORNER_BRACKET -> {
                startFocusSweepBracket()
                pulseEdgeGlow(binding.edgeGlowRight)
            }
            CameraGestureMode.BOTTOM_DRAWER -> setToolsDrawerOpen(open = true, animate = true)
            CameraGestureMode.NONE -> handlePreviewTap(upX, upY, distance, slop)
            else -> Unit
        }
    }

    private fun handlePreviewTap(upX: Float, upY: Float, distance: Float, slop: Float) {
        if (distance > slop) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewTapMs <= DOUBLE_TAP_TIMEOUT_MS) {
            lastPreviewTapMs = 0L
            centerDigitalCrop()
            aimDriftXRad = 0f
            aimDriftYRad = 0f
            binding.extremeOverlay.clearTarget()
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            lastPreviewTapMs = now
            lockTargetAt(upX, upY)
        }
    }

    private fun handleTwoFingerGesture(dx: Float, dy: Float, slop: Float) {
        if (abs(dx) < slop * 3 && abs(dy) < slop * 3) return
        if (abs(dx) > abs(dy)) {
            nudgeDigitalCrop(if (dx > 0f) 1 else -1, 0)
        } else {
            nudgeDigitalCrop(0, if (dy > 0f) 1 else -1)
        }
        haptic(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun handleThreeFingerGesture(dx: Float, dy: Float, slop: Float) {
        if (abs(dx) < slop * 2 && abs(dy) < slop * 2) {
            toggleZoomLock()
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        } else if (abs(dx) > abs(dy)) {
            cycleGrid()
            haptic(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun setToolsDrawerOpen(open: Boolean, animate: Boolean) {
        val drawer = binding.bottomPanel
        toolsDrawerOpen = open
        drawer.animate().cancel()
        val landscape = !isPortraitUi()
        if (landscape) {
            drawer.visibility = View.VISIBLE
            drawer.alpha = 1f
            drawer.translationX = 0f
            drawer.translationY = 0f
            return
        }
        val closedOffset = if (landscape) dp(360f) else dp(420f)
        if (open) {
            drawer.visibility = View.VISIBLE
            if (!animate) {
                drawer.alpha = 1f
                drawer.translationX = 0f
                drawer.translationY = 0f
                return
            }
            if (landscape) drawer.translationX = closedOffset else drawer.translationY = closedOffset
            drawer.alpha = 0f
            drawer.animate()
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(180L)
                .start()
        } else {
            if (!animate) {
                drawer.alpha = 0f
                drawer.translationX = if (landscape) closedOffset else 0f
                drawer.translationY = if (landscape) 0f else closedOffset
                drawer.visibility = View.GONE
                return
            }
            drawer.animate()
                .alpha(0f)
                .translationX(if (landscape) closedOffset else 0f)
                .translationY(if (landscape) 0f else closedOffset)
                .setDuration(160L)
                .withEndAction { if (!toolsDrawerOpen) drawer.visibility = View.GONE }
                .start()
        }
    }

    private fun showGestureHints(force: Boolean) {
        val prefs = getSharedPreferences(GESTURE_PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean(GESTURE_ONBOARDING_SHOWN, false)) return
        binding.gestureHintOverlay.animate().cancel()
        binding.gestureHintOverlay.alpha = 0f
        binding.gestureHintOverlay.visibility = View.VISIBLE
        binding.gestureHintOverlay.animate().alpha(1f).setDuration(160L).start()
        prefs.edit().putBoolean(GESTURE_ONBOARDING_SHOWN, true).apply()
        mainHandler.postDelayed({
            binding.gestureHintOverlay.animate()
                .alpha(0f)
                .setDuration(260L)
                .withEndAction { binding.gestureHintOverlay.visibility = View.GONE }
                .start()
        }, if (force) 6500L else 5200L)
    }

    private fun toggleHudHistogram() {
        val enabled = !(proSettings.hudEnabled || proSettings.histogramEnabled)
        proSettings = proSettings.copy(hudEnabled = enabled, histogramEnabled = enabled)
        applySettingsToUi()
        proSettings.save(this)
    }

    private fun pulseEdgeGlow(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .setDuration(70L)
            .withEndAction {
                view.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction { view.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private fun showEdgeGlow(view: View) {
        view.animate().cancel()
        view.alpha = 0.85f
        view.visibility = View.VISIBLE
    }

    private fun hideEdgeGlows() {
        listOf(binding.edgeGlowLeft, binding.edgeGlowRight, binding.edgeGlowTop, binding.edgeGlowBottom).forEach { glow ->
            glow.animate().cancel()
            glow.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction { glow.visibility = View.GONE }
                .start()
        }
    }

    private fun edgeSizePx(width: Float, height: Float): Float {
        return max(dp(42f), min(width, height) * 0.1f)
    }

    private fun haptic(type: Int) {
        binding.root.performHapticFeedback(type)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun isPortraitUi(): Boolean =
        pixelPortraitOnly || resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private fun buildPresetBar() {
        val bar = binding.zoomPresetBar
        val secondaryBar = binding.secondaryControlBar
        bar.removeAllViews()
        secondaryBar.removeAllViews()
        presetButtons.clear()
        mainZoomStops.forEach { preset ->
            val btn = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = preset.label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                insetTop = 0; insetBottom = 0
                val dp = resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (34 * dp).toInt()
                ).apply { marginEnd = (6 * dp).toInt() }
                cornerRadius = (17 * dp).toInt()
                setBackgroundColor(0x22FFFFFF)
                setOnClickListener { animateZoomTo(preset.ratio) }
            }
            presetButtons.add(btn)
            bar.addView(btn)
        }
        addToolButton(bar, "Rail") { advanceZoomRail(+1) }
        addToolButton(bar, "←") { nudgeDigitalCrop(-1, 0) }
        addToolButton(bar, "↑") { nudgeDigitalCrop(0, -1) }
        addToolButton(bar, "•") { centerDigitalCrop() }
        addToolButton(bar, "↓") { nudgeDigitalCrop(0, 1) }
        addToolButton(bar, "→") { nudgeDigitalCrop(1, 0) }
        addHoldToolButton(bar, getString(R.string.freeze_scope))
        addToolButton(bar, "Heat") { toggleHeatHaze() }
        addToolButton(bar, "Bracket") { startFocusSweepBracket() }
        refreshPresetHighlight()
    }

    private fun addToolButton(bar: LinearLayout, label: String, action: () -> Unit): MaterialButton {
        val dp = resources.displayMetrics.density
        val targetBar = if (bar === binding.zoomPresetBar) binding.secondaryControlBar else bar
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (34 * dp).toInt()
            ).apply { marginEnd = (6 * dp).toInt() }
            cornerRadius = (17 * dp).toInt()
            setBackgroundColor(0x337C3AED)
            setOnClickListener { action() }
            targetBar.addView(this)
        }
    }

    private fun addHoldToolButton(bar: LinearLayout, label: String): MaterialButton {
        val button = addToolButton(bar, label) { showFreezeScope() }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showFreezeScope()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideFreezeScope()
                    true
                }
                else -> false
            }
        }
        return button
    }

    private fun refreshPresetHighlight() {
        presetButtons.forEachIndexed { i, btn ->
            val active = mainZoomStops.getOrNull(i)?.ratio?.let { abs(it - requestedZoom) < 0.05f } == true
            if (active) { btn.setBackgroundColor(0xFFC4B5FD.toInt()); btn.setTextColor(0xFF111418.toInt()) }
            else        { btn.setBackgroundColor(0x22FFFFFF);          btn.setTextColor(0xFFFFFFFF.toInt()) }
        }
    }

    // ── Settings → UI ─────────────────────────────────────────────────────────

    private fun applySettingsToUi() {
        histogramEnabledForAnalyzer = proSettings.histogramEnabled
        binding.nightOverlay.visibility   = if (proSettings.nightUiEnabled)   View.VISIBLE else View.GONE
        binding.histogramView.visibility  = if (proSettings.histogramEnabled)  View.VISIBLE else View.GONE
        binding.hudRow.visibility         = View.VISIBLE
        binding.shakeMeterRow.visibility  = if (proSettings.antiShakeEnabled)  View.VISIBLE else View.GONE
        binding.proModeBadge.visibility   = if (proSettings.manualMode)         View.VISIBLE else View.GONE
        binding.gridOverlay.gridMode      = proSettings.gridMode
        binding.gridButton.text = when (proSettings.gridMode) {
            GridMode.NONE     -> getString(R.string.grid_off)
            GridMode.THIRDS   -> "Thirds"
            GridMode.SQUARE   -> "Square"
            GridMode.DIAGONAL -> "Diagonal"
        }
        updateHud()
    }

    private fun updateHud() {
        val autoMode = !proSettings.manualMode
        binding.hudIso.text = if (autoMode) "Yes" else "No"
        binding.hudShutter.text = if (autoMode) "Yes" else "No"
        binding.hudWb.text = if (autoMode) "Yes" else "No"
        val stateColor = if (autoMode) 0xFFC4B5FD.toInt() else 0xFFFFFFFF.toInt()
        binding.hudIso.setTextColor(stateColor)
        binding.hudShutter.setTextColor(stateColor)
        binding.hudWb.setTextColor(stateColor)
    }

    // ── Camera startup ────────────────────────────────────────────────────────

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed || cameraExecutor.isShutdown) return@addListener
            cameraProvider = try {
                future.get()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.bind_failed, e.message), Toast.LENGTH_LONG).show()
                return@addListener
            }
            lensOptions = buildLensOptions(cameraProvider ?: return@addListener)
            if (lensOptions.isEmpty()) {
                Toast.makeText(this, R.string.no_camera_found, Toast.LENGTH_LONG).show()
                finish(); return@addListener
            }
            currentLensIndex = lensOptions.indexOfFirst { it.preferred }.takeIf { it >= 0 } ?: 0
            bindSelectedCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildLensOptions(provider: ProcessCameraProvider): List<LensOption> {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        data class CameraCandidate(
            val cameraId: String,
            val facing: Int,
            val maxFocal: Float,
            val horizontalFovDeg: Float,
            val verticalFovDeg: Float
        )

        val candidates = provider.availableCameraInfos.mapNotNull { info ->
            val cameraInfo = Camera2CameraInfo.from(info)
            val cameraId = cameraInfo.cameraId
            val facing = cameraInfo.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val maxFocal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.maxOrNull() ?: 0f
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val horizontalFov = if (sensorSize != null && maxFocal > 0f) {
                (2.0 * atan(sensorSize.width / (2.0 * maxFocal)) * 180.0 / PI).toFloat()
            } else 0f
            val verticalFov = if (sensorSize != null && maxFocal > 0f) {
                (2.0 * atan(sensorSize.height / (2.0 * maxFocal)) * 180.0 / PI).toFloat()
            } else 0f
            CameraCandidate(cameraId, facing, maxFocal, horizontalFov, verticalFov)
        }

        val backOptions = candidates
            .filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .sortedByDescending { it.maxFocal }
            .mapIndexed { index, candidate ->
             val selector = CameraSelector.Builder()
                 .addCameraFilter { infos ->
                     infos.filter { Camera2CameraInfo.from(it).cameraId == candidate.cameraId }
                 }.build()
             val isTelephoto = index == 0
             val label = if (isTelephoto) getString(R.string.lens_telephoto) else getString(R.string.lens_wide)
             LensOption(
                 label    = if (candidates.count { it.facing == CameraCharacteristics.LENS_FACING_BACK } > 1) "$label ${index + 1}" else label,
                 selector = selector,
                 facing = CameraCharacteristics.LENS_FACING_BACK,
                 cameraId = candidate.cameraId,
                 baseHorizontalFovDeg = candidate.horizontalFovDeg,
                 baseVerticalFovDeg = candidate.verticalFovDeg,
                 preferred = isTelephoto,
                 telephoto = isTelephoto
             )
         }

        val frontCandidates = candidates
            .filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            .sortedByDescending { it.maxFocal }

        val frontOptions = frontCandidates.mapIndexed { index, candidate ->
            val selector = CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == candidate.cameraId }
                }.build()
            LensOption(
                label = if (frontCandidates.size > 1) "${getString(R.string.lens_front)} ${index + 1}" else getString(R.string.lens_front),
                selector = selector,
                facing = CameraCharacteristics.LENS_FACING_FRONT,
                cameraId = candidate.cameraId,
                baseHorizontalFovDeg = candidate.horizontalFovDeg,
                baseVerticalFovDeg = candidate.verticalFovDeg,
                preferred = backOptions.isEmpty() && index == 0,
                telephoto = false
            )
        }

        return backOptions + frontOptions
    }

    private fun bindSelectedCamera() {
        if (isFinishing || isDestroyed || cameraExecutor.isShutdown) return
        val provider     = cameraProvider ?: return
        val selectedLens = lensOptions.getOrNull(currentLensIndex) ?: return
        val rotation     = currentDisplayRotation()

        val previewBuilder = Preview.Builder().setTargetRotation(rotation)
        ProCameraController.applyToPreviewBuilder(previewBuilder, proSettings)

        preview = previewBuilder.build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
        ProCameraController.applyToCapturBuilder(captureBuilder, proSettings)
        imageCapture = captureBuilder.build()

        val analysisResolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(320, 240),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        updateHistogram(image)
                    } finally {
                        image.close()
                    }
                }
            }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selectedLens.selector, preview, imageCapture, imageAnalysis)
            val zoomState = camera?.cameraInfo?.zoomState?.value
            minNativeZoom = zoomState?.minZoomRatio ?: 1f
            nativeZoomCap = zoomState?.maxZoomRatio ?: 1f
            baseHorizontalFovDeg = selectedLens.baseHorizontalFovDeg
            baseVerticalFovDeg = selectedLens.baseVerticalFovDeg
            lastAppliedNativeZoom = Float.NaN
            pendingNativeZoom = null
            nativeZoomInFlight = false
            aimDriftXRad = 0f
            aimDriftYRad = 0f
            capabilities  = ProCameraController.queryCapabilities(camera!!)
            updateUseCaseRotations(force = true)
            applyZoomToPipeline()
            bringControlUiToFront()
            updateLensBadge(selectedLens)
            torchEnabled = false
            updateFlashlightButton()
            if (proSettings.antiShakeEnabled) startAntiShake()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.bind_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    // ── Zoom ──────────────────────────────────────────────────────────────────

    private fun updateHistogram(image: androidx.camera.core.ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val lumaPlane = image.planes.firstOrNull() ?: return
        if (histogramEnabledForAnalyzer && now - lastHistogramUpdateMs >= HISTOGRAM_FRAME_INTERVAL_MS) {
            lastHistogramUpdateMs = now
            binding.histogramView.updateFromLumaPlane(
                buffer = lumaPlane.buffer,
                rowStride = lumaPlane.rowStride,
                pixelStride = lumaPlane.pixelStride,
                width = image.width,
                height = image.height
            )
        }
        if (heatHazeEnabled && now - lastHeatHazeUpdateMs >= HEAT_HAZE_FRAME_INTERVAL_MS) {
            lastHeatHazeUpdateMs = now
            binding.heatHazeView.updateFromLumaPlane(
                buffer = lumaPlane.buffer,
                rowStride = lumaPlane.rowStride,
                pixelStride = lumaPlane.pixelStride,
                width = image.width,
                height = image.height,
                rotationDegrees = image.imageInfo.rotationDegrees
            )
        }
        if (now - lastScopeFrameUpdateMs >= SCOPE_FRAME_INTERVAL_MS) {
            lastScopeFrameUpdateMs = now
            updateLatestScopeFrame(
                buffer = lumaPlane.buffer,
                rowStride = lumaPlane.rowStride,
                pixelStride = lumaPlane.pixelStride,
                width = image.width,
                height = image.height,
                rotationDegrees = image.imageInfo.rotationDegrees
            )
        }
    }

    private fun updateLatestScopeFrame(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ) {
        val outW = 320
        val outH = max(1, (height * outW / width.toFloat()).toInt())
        val pixels = IntArray(outW * outH)
        for (y in 0 until outH) {
            val srcY = y * height / outH
            val row = srcY * rowStride
            for (x in 0 until outW) {
                val srcX = x * width / outW
                val luma = buffer.get(row + srcX * pixelStride).toInt() and 0xFF
                pixels[y * outW + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        val displayBitmap = rotateBitmap(bitmap, rotationDegrees)
        if (displayBitmap != bitmap) bitmap.recycle()
        synchronized(scopeBitmapLock) {
            latestScopeBitmap?.recycle()
            latestScopeBitmap = displayBitmap
            if (freezeScopeActive) {
                mainHandler.post { refreshFreezeScopeFrame() }
            }
        }
    }

    private fun rotateBitmap(src: Bitmap, rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return src
        val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun animateZoomTo(target: Float) {
        if (proSettings.zoomLocked) return
        val from = requestedZoom
        zoomAnimator.animateTo(from, target.coerceIn(1f, 100f)) { value ->
            setZoomImmediately(value, syncSlider = true)
        }
    }

    private fun setZoomImmediately(value: Float, syncSlider: Boolean) {
        requestedZoom = value.coerceIn(1f, 100f)
        if (syncSlider) syncZoomSlider(requestedZoom)
        applyZoomToPipeline()
    }

    private fun syncZoomSlider(value: Float) {
        val slider = binding.zoomSlider
        val steppedValue = snapToSliderStep(value)
        if (abs(slider.value - steppedValue) > 0.001f) {
            slider.value = steppedValue
        }
    }

    private fun snapToSliderStep(value: Float): Float {
        val slider = binding.zoomSlider
        val clamped = value.coerceIn(slider.valueFrom, slider.valueTo)
        val step = slider.stepSize
        if (step <= 0f) return clamped
        val steps = ((clamped - slider.valueFrom) / step).roundToInt()
        return (slider.valueFrom + steps * step).coerceIn(slider.valueFrom, slider.valueTo)
    }

    private fun applyZoomToPipeline() {
        val nativeZoom    = requestedZoom.coerceIn(minNativeZoom, nativeZoomCap)
        val digitalFactor = max(1f, requestedZoom / nativeZoom)
        currentDigitalFactor = digitalFactor
        requestNativeZoom(nativeZoom)
        binding.previewView.scaleX = digitalFactor
        binding.previewView.scaleY = digitalFactor
        val maxShiftX = binding.previewView.width * (digitalFactor - 1f) / 2f
        val maxShiftY = binding.previewView.height * (digitalFactor - 1f) / 2f
        binding.previewView.translationX = -digitalCropOffsetX * maxShiftX
        binding.previewView.translationY = -digitalCropOffsetY * maxShiftY
        binding.extremeOverlay.updateCropOffset(digitalCropOffsetX, digitalCropOffsetY)
        binding.extremeOverlay.updateZoomInfo(
            requestedZoom,
            currentHorizontalFovDeg(),
            currentVerticalFovDeg()
        )
        updateZoomLabels()
        refreshPresetHighlight()
        updateExtremeOverlay()
        updateCaptureStabilityUi()
    }

    private fun requestNativeZoom(nativeZoom: Float) {
        val cam = camera ?: return
        val zoom = nativeZoom.coerceIn(minNativeZoom, nativeZoomCap)
        if (!nativeZoomInFlight && abs(zoom - lastAppliedNativeZoom) < ZOOM_EPSILON) return

        pendingNativeZoom = zoom
        if (nativeZoomInFlight) return
        applyPendingNativeZoom(cam)
    }

    private fun applyPendingNativeZoom(cam: Camera) {
        val zoom = pendingNativeZoom ?: return
        pendingNativeZoom = null
        nativeZoomInFlight = true

        val future = cam.cameraControl.setZoomRatio(zoom)
        future.addListener({
            if (camera !== cam) return@addListener
            try {
                future.get()
                lastAppliedNativeZoom = zoom
            } catch (_: Exception) {
                // Keep the UI responsive even if a device rejects an intermediate zoom ratio.
            } finally {
                nativeZoomInFlight = false
                if (pendingNativeZoom != null && camera === cam) {
                    applyPendingNativeZoom(cam)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun currentHorizontalFovDeg(): Float {
        return if (baseHorizontalFovDeg > 0f) baseHorizontalFovDeg / requestedZoom else 0f
    }

    private fun currentVerticalFovDeg(): Float {
        return if (baseVerticalFovDeg > 0f) baseVerticalFovDeg / requestedZoom else 0f
    }

    private fun lockTargetAt(x: Float, y: Float) {
        aimDriftXRad = 0f
        aimDriftYRad = 0f
        binding.extremeOverlay.lockTarget(x, y)
        Toast.makeText(this, R.string.target_locked, Toast.LENGTH_SHORT).show()
    }

    private fun updateExtremeOverlay() {
        val fovHrad = (currentHorizontalFovDeg().coerceAtLeast(0.05f) * PI / 180.0).toFloat()
        val fovVrad = (currentVerticalFovDeg().coerceAtLeast(0.05f) * PI / 180.0).toFloat()
        val dxPx = aimDriftXRad * binding.extremeOverlay.width / fovHrad
        val dyPx = aimDriftYRad * binding.extremeOverlay.height / fovVrad
        binding.extremeOverlay.updateDrift(dxPx, dyPx)
        binding.extremeOverlay.updateStability(
            level = shakeLevelForZoom(),
            isStable = isStableForCapture(),
            pendingCapture = settledCapturePending,
            progress = settledCaptureProgress()
        )
    }

    private fun nudgeDigitalCrop(dx: Int, dy: Int) {
        if (currentDigitalFactor <= 1.01f) {
            Toast.makeText(this, R.string.nudge_requires_digital_zoom, Toast.LENGTH_SHORT).show()
            return
        }
        val step = (0.16f / currentDigitalFactor).coerceIn(0.025f, 0.08f)
        digitalCropOffsetX = (digitalCropOffsetX + dx * step).coerceIn(-1f, 1f)
        digitalCropOffsetY = (digitalCropOffsetY + dy * step).coerceIn(-1f, 1f)
        applyZoomToPipeline()
    }

    private fun centerDigitalCrop() {
        digitalCropOffsetX = 0f
        digitalCropOffsetY = 0f
        applyZoomToPipeline()
    }

    private fun showFreezeScope() {
        freezeScopeActive = true
        if (!refreshFreezeScopeFrame()) {
            Toast.makeText(this, R.string.freeze_scope_wait, Toast.LENGTH_SHORT).show()
            return
        }
        binding.freezeScopeView.visibility = View.VISIBLE
    }

    private fun hideFreezeScope() {
        freezeScopeActive = false
        binding.freezeScopeView.visibility = View.GONE
        binding.freezeScopeView.setImageDrawable(null)
        displayedFreezeBitmap?.recycle()
        displayedFreezeBitmap = null
    }

    private fun refreshFreezeScopeFrame(): Boolean {
        val bitmap = synchronized(scopeBitmapLock) { latestScopeBitmap?.copy(Bitmap.Config.ARGB_8888, false) }
        if (bitmap == null) return false
        val previous = displayedFreezeBitmap
        displayedFreezeBitmap = bitmap
        binding.freezeScopeView.setImageBitmap(bitmap)
        previous?.recycle()
        binding.freezeScopeView.visibility = View.VISIBLE
        return true
    }

    private fun toggleHeatHaze() {
        heatHazeEnabled = !heatHazeEnabled
        binding.heatHazeView.visibility = if (heatHazeEnabled) View.VISIBLE else View.GONE
        Toast.makeText(
            this,
            if (heatHazeEnabled) R.string.heat_haze_on else R.string.heat_haze_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun advanceZoomRail(direction: Int) {
        val nearest = zoomRailStops.indices.minByOrNull { abs(zoomRailStops[it] - requestedZoom) } ?: 0
        railIndex = (nearest + direction).floorMod(zoomRailStops.size)
        zoomAnimator.speed = ZoomAnimator.Speed.CINEMATIC
        animateZoomTo(zoomRailStops[railIndex])
        zoomAnimator.speed = ZoomAnimator.Speed.SMOOTH
    }

    private fun stabilityThreshold(): Float {
        return (0.19f / sqrt(requestedZoom.coerceAtLeast(1f))).coerceIn(0.018f, 0.12f)
    }

    private fun isStableForCapture(): Boolean {
        return stableSinceMs != 0L && SystemClock.uptimeMillis() - stableSinceMs >= 180L
    }

    private fun shakeLevelForZoom(): Float {
        return (gyroMagnitude / (stabilityThreshold() * 4f)).coerceIn(0f, 1f)
    }

    private fun settledCaptureProgress(): Float {
        if (!settledCapturePending) return 0f
        return ((SystemClock.uptimeMillis() - settledCaptureStartedMs).toFloat() / SETTLED_CAPTURE_MIN_DELAY_MS).coerceIn(0f, 1f)
    }

    private fun updateCaptureStabilityUi() {
        val status = when {
            settledCapturePending -> getString(R.string.stability_settling)
            isStableForCapture() -> getString(R.string.stability_stable)
            else -> getString(R.string.stability_shake)
        }
        binding.captureStatusText.text = status
        binding.captureStatusText.visibility = View.VISIBLE
        binding.captureStatusText.setTextColor(
            when (status) {
                getString(R.string.stability_stable) -> 0xFFC4B5FD.toInt()
                getString(R.string.stability_settling) -> 0xFFDDDDDD.toInt()
                else -> 0xFFFFD7A8.toInt()
            }
        )
        binding.captureButton.text = ""
    }

    private fun toggleZoomLock() {
        proSettings = proSettings.copy(zoomLocked = !proSettings.zoomLocked)
        if (proSettings.zoomLocked) {
            binding.lockZoomButton.text = "Locked"
            binding.zoomSlider.isEnabled = false
            Toast.makeText(this, getString(R.string.zoom_locked, requestedZoom), Toast.LENGTH_SHORT).show()
        } else {
            binding.lockZoomButton.text = getString(R.string.lock_zoom)
            binding.zoomSlider.isEnabled = true
            Toast.makeText(this, R.string.zoom_unlocked, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Grid ──────────────────────────────────────────────────────────────────

    private fun cycleGrid() {
        val next = when (proSettings.gridMode) {
            GridMode.NONE     -> GridMode.THIRDS
            GridMode.THIRDS   -> GridMode.SQUARE
            GridMode.SQUARE   -> GridMode.DIAGONAL
            GridMode.DIAGONAL -> GridMode.NONE
        }
        proSettings = proSettings.copy(gridMode = next)
        applySettingsToUi()
        proSettings.save(this)
    }

    // ── Lens switch ───────────────────────────────────────────────────────────

    private fun switchLens() {
        val backLensIndexes = lensOptions.withIndex()
            .filter { it.value.facing == CameraCharacteristics.LENS_FACING_BACK }
            .map { it.index }

        if (backLensIndexes.isEmpty()) {
            Toast.makeText(this, R.string.no_other_lens, Toast.LENGTH_SHORT).show()
            return
        }

        currentLensIndex = if (lensOptions.getOrNull(currentLensIndex)?.facing == CameraCharacteristics.LENS_FACING_FRONT) {
            backLensIndexes.first()
        } else {
            val currentBackPosition = backLensIndexes.indexOf(currentLensIndex)
            if (backLensIndexes.size <= 1) {
                Toast.makeText(this, R.string.no_other_lens, Toast.LENGTH_SHORT).show()
                return
            }
            backLensIndexes[(currentBackPosition + 1).floorMod(backLensIndexes.size)]
        }
        bindSelectedCamera()
    }

    private fun switchToFrontCamera() {
        val frontIndex = lensOptions.indexOfFirst { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
        if (frontIndex < 0) {
            Toast.makeText(this, R.string.no_front_camera, Toast.LENGTH_SHORT).show()
            return
        }
        currentLensIndex = frontIndex
        bindSelectedCamera()
        Toast.makeText(this, R.string.front_camera_easter_egg, Toast.LENGTH_LONG).show()
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    private fun toggleFlashlight() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            torchEnabled = false
            updateFlashlightButton()
            Toast.makeText(this, R.string.flash_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        torchEnabled = !torchEnabled
        cam.cameraControl.enableTorch(torchEnabled)
        updateFlashlightButton()
    }

    private fun updateFlashlightButton() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        binding.flashlightButton.isEnabled = hasFlash
        binding.flashlightButton.alpha = if (hasFlash) 1f else 0.45f
        binding.flashlightButton.text = if (torchEnabled && hasFlash) getString(R.string.flash_on) else getString(R.string.flash_off)
    }

    private fun openAppInfo() {
        startActivity(Intent(this, AppInfoActivity::class.java))
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    private fun onCaptureClicked() {
        if (settledCapturePending) {
            cancelSettledCapture()
            Toast.makeText(this, R.string.capture_cancelled, Toast.LENGTH_SHORT).show()
            return
        }
        if (proSettings.antiShakeEnabled && !antiShakeArmed) {
            Toast.makeText(this, "Hold still — waiting for stable frame…", Toast.LENGTH_SHORT).show()
            return
        }
        if (requestedZoom >= EXTREME_CAPTURE_ZOOM && !isStableForCapture()) {
            beginSettledCapture()
            return
        }
        capturePhoto()
    }

    private fun beginSettledCapture() {
        settledCapturePending = true
        settledCaptureStartedMs = SystemClock.uptimeMillis()
        updateCaptureStabilityUi()
        updateExtremeOverlay()
        Toast.makeText(this, R.string.capture_waiting_for_stillness, Toast.LENGTH_SHORT).show()
        mainHandler.removeCallbacks(settledCaptureRunnable)
        mainHandler.post(settledCaptureRunnable)
    }

    private fun updateSettledCapture() {
        if (!settledCapturePending) return
        val elapsed = SystemClock.uptimeMillis() - settledCaptureStartedMs
        updateExtremeOverlay()
        updateCaptureStabilityUi()
        if ((elapsed >= SETTLED_CAPTURE_MIN_DELAY_MS && isStableForCapture()) || elapsed >= SETTLED_CAPTURE_TIMEOUT_MS) {
            settledCapturePending = false
            updateExtremeOverlay()
            updateCaptureStabilityUi()
            capturePhoto()
        } else {
            mainHandler.postDelayed(settledCaptureRunnable, 33L)
        }
    }

    private fun cancelSettledCapture() {
        settledCapturePending = false
        mainHandler.removeCallbacks(settledCaptureRunnable)
        updateCaptureStabilityUi()
        updateExtremeOverlay()
    }

    private fun playShutterClick(volume: Int) {
        val safeVolume = volume.coerceIn(0, 100)
        if (safeVolume <= 0) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, safeVolume)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 55)
            mainHandler.postDelayed({ tone.release() }, 120L)
        } catch (_: RuntimeException) {
            // Sound hardware can be unavailable transiently; capture should continue.
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        updateUseCaseRotations()
        val captureZoom     = requestedZoom
        val nativeAtCapture = min(captureZoom, nativeZoomCap)
        val captureCropX    = digitalCropOffsetX
        val captureCropY    = digitalCropOffsetY
        val captureIso      = if (proSettings.manualMode) ProSettings.ISO_VALUES[proSettings.isoIndex] else null
        val captureSS       = if (proSettings.manualMode) ProSettings.SHUTTER_VALUES[proSettings.shutterIndex] else null
        val playShutterSound = proSettings.shutterSoundEnabled
        val shutterVolume = proSettings.shutterSoundVolume
        val shouldPostProcess = proSettings.zoomPostProcessEnabled && captureZoom >= POST_PROCESS_MIN_ZOOM
        val postProcessStrength = proSettings.zoomPostProcessStrength
        val captureOutputFormat = proSettings.outputFormat
        val output = createOutputOptions(captureZoom)

        if (playShutterSound) playShutterClick(shutterVolume)
        capture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val uri = result.savedUri ?: return
                val digitalFactor = max(1f, captureZoom / nativeAtCapture)
                if (digitalFactor > 1f) applyDigitalCrop(uri, digitalFactor, captureCropX, captureCropY)
                if (shouldPostProcess) {
                    try {
                        ExtremeZoomPostProcessor.process(
                            contentResolver,
                            uri,
                            captureOutputFormat,
                            postProcessStrength
                        )
                    } catch (_: Exception) {
                        mainHandler.post {
                            Toast.makeText(this@MainActivity, R.string.post_process_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                ExifUtils.writeProMetadata(
                    contentResolver, uri,
                    zoomRatio  = captureZoom,
                    nativeZoom = nativeAtCapture,
                    iso        = captureIso,
                    shutterNs  = captureSS,
                    stripGps   = proSettings.stripGpsExif
                )
                mainHandler.post {
                    Toast.makeText(this@MainActivity, getString(R.string.photo_saved, captureZoom), Toast.LENGTH_SHORT).show()
                    antiShakeArmed = false
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, getString(R.string.capture_failed, exception.message), Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun createOutputOptions(zoom: Float): ImageCapture.OutputFileOptions {
        val isWebp = proSettings.outputFormat == OutputFormat.WEBP
        val ext  = if (isWebp) "webp" else "jpg"
        val mime = if (isWebp) "image/webp" else "image/jpeg"
        val name = "Open100x_${zoom.toInt()}x_${System.currentTimeMillis()}.$ext"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Open100x")
        }
        return ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
    }

    // ── Pro settings sheet ────────────────────────────────────────────────────

    private fun startFocusSweepBracket() {
        val cam = camera ?: return
        if (focusSweepRunning) return
        val capture = imageCapture ?: return
        val minFocusDistance = Camera2CameraInfo.from(cam.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minFocusDistance <= 0f) {
            Toast.makeText(this, R.string.focus_sweep_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        focusSweepRunning = true
        Toast.makeText(this, R.string.focus_sweep_started, Toast.LENGTH_SHORT).show()
        val farSweep = floatArrayOf(
            0f,
            minFocusDistance * 0.02f,
            minFocusDistance * 0.05f,
            minFocusDistance * 0.09f,
            minFocusDistance * 0.14f
        )
        captureFocusSweepFrame(capture, farSweep, 0)
    }

    private fun captureFocusSweepFrame(capture: ImageCapture, distances: FloatArray, index: Int) {
        if (index >= distances.size) {
            restoreAutofocusAfterSweep()
            Toast.makeText(this, R.string.focus_sweep_done, Toast.LENGTH_SHORT).show()
            return
        }
        applyFocusDistance(distances[index]) {
            mainHandler.postDelayed({
                val output = createOutputOptions(requestedZoom)
                capture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        mainHandler.post { captureFocusSweepFrame(capture, distances, index + 1) }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        mainHandler.post { captureFocusSweepFrame(capture, distances, index + 1) }
                    }
                })
            }, 120L)
        }
    }

    private fun applyFocusDistance(distance: Float, onApplied: () -> Unit) {
        val cam = camera ?: return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            .build()
        val future = Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options)
        future.addListener({ onApplied() }, ContextCompat.getMainExecutor(this))
    }

    private fun restoreAutofocusAfterSweep() {
        val cam = camera
        focusSweepRunning = false
        if (cam == null) return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            .build()
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options)
    }

    private fun openProSettings() {
        // Snapshot settings before opening
        val settingsBeforeOpen = proSettings.copy()
        val sheet = ProSettingsSheet().apply {
            settings     = proSettings.copy()
            capabilities = this@MainActivity.capabilities
            onSettingsChanged = { newSettings ->
                val rebindNeeded = newSettings.manualMode    != proSettings.manualMode    ||
                                   newSettings.isoIndex      != proSettings.isoIndex      ||
                                   newSettings.shutterIndex  != proSettings.shutterIndex  ||
                                   newSettings.wbMode        != proSettings.wbMode
                proSettings = newSettings
                applySettingsToUi()
                proSettings.save(this@MainActivity)
                if (rebindNeeded) bindSelectedCamera()
                if (newSettings.antiShakeEnabled) startAntiShake() else antiShake.stop()
            }
        }
        sheet.show(supportFragmentManager, ProSettingsSheet.TAG)
    }

    // ── Anti-shake ────────────────────────────────────────────────────────────

    private fun startAntiShake() {
        if (!antiShake.isAvailable) return
        antiShake.stop()
        antiShake.onShakeLevel = { level ->
            binding.shakeMeter.level = level
            binding.shakeMeterLabel.text = if (level < 0.2f)
                getString(R.string.anti_shake_ready) else getString(R.string.anti_shake_wait)
        }
        antiShake.onStable = {
            antiShakeArmed = true
            binding.shakeMeterLabel.text = getString(R.string.anti_shake_ready)
        }
        antiShake.start(threshold = proSettings.antiShakeThreshold, quietWindowMs = 150L)
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    private fun updateLensBadge(lens: LensOption) {
        binding.lensBadgeText.text = if (lens.telephoto)
            getString(R.string.badge_telephoto_enabled, lens.label)
        else
            getString(R.string.badge_standard_lens, lens.label)
    }

    private fun updateZoomLabels() {
        binding.zoomValueText.text  = getString(R.string.zoom_ratio_label, requestedZoom)
        binding.zoomDetailText.text = ""
        binding.zoomDetailText.visibility = View.GONE
    }

    // ── Digital crop ──────────────────────────────────────────────────────────

    private fun applyDigitalCrop(uri: android.net.Uri, digitalFactor: Float, cropOffsetX: Float, cropOffsetY: Float) {
        val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
        val exifOrientation = contentResolver.openInputStream(uri)?.use { inp ->
            ExifInterface(inp).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val oriented = rotateBitmapForExif(source, exifOrientation)
        if (oriented != source) source.recycle()

        val srcW  = oriented.width
        val srcH  = oriented.height
        val cropW = (srcW / digitalFactor).toInt().coerceAtLeast(1)
        val cropH = (srcH / digitalFactor).toInt().coerceAtLeast(1)
        val maxLeft = (srcW - cropW).coerceAtLeast(0)
        val maxTop = (srcH - cropH).coerceAtLeast(0)
        val left  = ((maxLeft / 2f) + cropOffsetX.coerceIn(-1f, 1f) * (maxLeft / 2f)).toInt().coerceIn(0, maxLeft)
        val top   = ((maxTop / 2f) + cropOffsetY.coerceIn(-1f, 1f) * (maxTop / 2f)).toInt().coerceIn(0, maxTop)

        val cropped = Bitmap.createBitmap(oriented, left, top, cropW, cropH)
        val scaled  = Bitmap.createScaledBitmap(cropped, srcW, srcH, true)
        oriented.recycle(); cropped.recycle()

        val fmt = if (proSettings.outputFormat == OutputFormat.WEBP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        } else Bitmap.CompressFormat.JPEG
        val quality = if (proSettings.outputFormat == OutputFormat.WEBP) 90 else 95

        contentResolver.openOutputStream(uri, "wt")?.use { scaled.compress(fmt, quality, it) }
        contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
            ExifInterface(fd.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
        }
        scaled.recycle()
    }

    private fun rotateBitmapForExif(src: Bitmap, orientation: Int): Bitmap {
        val m = android.graphics.Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL   -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE  -> { postScale(-1f, 1f); postRotate(270f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(90f) }
                else -> return src
            }
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // ── Orientation ───────────────────────────────────────────────────────────

    private fun createOrientationListener() = object : OrientationEventListener(this) {
        override fun onOrientationChanged(orientation: Int) {
            if (pixelPortraitOnly) return
            if (orientation == ORIENTATION_UNKNOWN) return
            queueUseCaseRotationUpdate()
        }
    }

    private fun queueUseCaseRotationUpdate() {
        if (pixelPortraitOnly) return
        if (rotationUpdateQueued || isFinishing || isDestroyed) return
        rotationUpdateQueued = true
        mainHandler.post {
            rotationUpdateQueued = false
            updateUseCaseRotations()
        }
    }

    private fun updateUseCaseRotations(force: Boolean = false) {
        if (isFinishing || isDestroyed) return
        if (pixelPortraitOnly && !force) return
        val r = currentDisplayRotation()
        if (!force && r == lastTargetRotation) return
        lastTargetRotation = r
        try {
            preview?.targetRotation      = r
            imageCapture?.targetRotation = r
            imageAnalysis?.targetRotation = r
            binding.heatHazeView.invalidate()
            if (freezeScopeActive) refreshFreezeScopeFrame()
        } catch (e: IllegalStateException) {
            // Rotation can arrive while Pixel devices are tearing down/recreating the
            // CameraX graph. The next attach or orientation callback will re-apply it.
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation() =
        if (pixelPortraitOnly) Surface.ROTATION_0
        else binding.previewView.display?.rotation ?: windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

    // ── Data class ────────────────────────────────────────────────────────────

    private data class LensOption(
        val label: String,
        val selector: CameraSelector,
        val facing: Int,
        val cameraId: String,
        val baseHorizontalFovDeg: Float,
        val baseVerticalFovDeg: Float,
        val preferred: Boolean,
        val telephoto: Boolean
    )
}
