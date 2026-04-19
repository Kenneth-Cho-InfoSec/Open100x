package com.example.zoomhundred.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.zoomhundred.R
import com.example.zoomhundred.camera.CameraCapabilities
import com.example.zoomhundred.model.GridMode
import com.example.zoomhundred.model.OutputFormat
import com.example.zoomhundred.model.ProSettings
import com.example.zoomhundred.model.WbMode
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class ProSettingsSheet : BottomSheetDialogFragment() {

    var settings: ProSettings = ProSettings()
    var capabilities: CameraCapabilities = CameraCapabilities()
    var onSettingsChanged: ((ProSettings) -> Unit)? = null

    private val WB_LABELS = listOf("Auto","Day","Cloud","Shade","Tungsten","Fluoro","Custom")
    private val WB_MODES  = WbMode.entries

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_pro, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupExposure(view)
        setupWhiteBalance(view)
        setupOverlays(view)
        setupAntiShake(view)
        setupOutputFormat(view)
        setupCaptureProcessing(view)
        setupNightUi(view)
    }

    // ── Exposure ──────────────────────────────────────────────────────────────

    private fun setupExposure(view: View) {
        val switchManual = view.findViewById<SwitchMaterial>(R.id.switchManualMode)
        val sliderIso    = view.findViewById<Slider>(R.id.sliderIso)
        val sliderSS     = view.findViewById<Slider>(R.id.sliderShutter)
        val tvIso        = view.findViewById<TextView>(R.id.tvIsoValue)
        val tvSS         = view.findViewById<TextView>(R.id.tvShutterValue)
        val rowIso       = view.findViewById<View>(R.id.rowIso)
        val rowSS        = view.findViewById<View>(R.id.rowShutter)

        fun updateExposureRows() {
            val enabled = settings.manualMode && capabilities.supportsManualSensor
            rowIso.alpha = if (enabled) 1f else 0.38f
            rowSS.alpha  = if (enabled) 1f else 0.38f
            sliderIso.isEnabled = enabled
            sliderSS.isEnabled  = enabled
        }

        switchManual.isChecked = settings.manualMode
        if (!capabilities.supportsManualSensor) {
            switchManual.isEnabled = false
            switchManual.alpha = 0.4f
        }
        sliderIso.value = settings.isoIndex.toFloat()
        sliderSS.value  = settings.shutterIndex.toFloat()
        tvIso.text = "ISO ${ProSettings.ISO_VALUES[settings.isoIndex]}"
        tvSS.text  = ProSettings.SHUTTER_LABELS[settings.shutterIndex]
        updateExposureRows()

        switchManual.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(manualMode = checked)
            updateExposureRows()
            onSettingsChanged?.invoke(settings)
        }
        sliderIso.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val idx = value.toInt().coerceIn(0, ProSettings.ISO_VALUES.lastIndex)
            settings = settings.copy(isoIndex = idx)
            tvIso.text = "ISO ${ProSettings.ISO_VALUES[idx]}"
            onSettingsChanged?.invoke(settings)
        }
        sliderSS.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val idx = value.toInt().coerceIn(0, ProSettings.SHUTTER_VALUES.lastIndex)
            settings = settings.copy(shutterIndex = idx)
            tvSS.text = ProSettings.SHUTTER_LABELS[idx]
            onSettingsChanged?.invoke(settings)
        }
    }

    // ── White balance ─────────────────────────────────────────────────────────

    private fun setupWhiteBalance(view: View) {
        val group = view.findViewById<LinearLayout>(R.id.wbButtonGroup)
        val buttons = mutableListOf<MaterialButton>()

        WB_LABELS.forEachIndexed { i, label ->
            val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                insetTop = 0; insetBottom = 0
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    resources.getDimensionPixelSize(R.dimen.chip_height)
                ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.chip_gap) }
                layoutParams = lp
                cornerRadius = resources.getDimensionPixelSize(R.dimen.chip_radius)
                setOnClickListener {
                    settings = settings.copy(wbMode = WB_MODES[i])
                    onSettingsChanged?.invoke(settings)
                    refreshWbButtons(buttons, i)
                }
            }
            buttons.add(btn)
            group.addView(btn)
        }
        refreshWbButtons(buttons, settings.wbMode.ordinal)
    }

    private fun refreshWbButtons(buttons: List<MaterialButton>, activeIdx: Int) {
        buttons.forEachIndexed { i, btn ->
            if (i == activeIdx) {
                btn.setBackgroundColor(0xCCFFFFFF.toInt())
                btn.setTextColor(0xFF111418.toInt())
            } else {
                btn.setBackgroundColor(0x22FFFFFF)
                btn.setTextColor(Color.WHITE)
            }
        }
    }

    // ── Overlays ──────────────────────────────────────────────────────────────

    private fun setupOverlays(view: View) {
        val btnFP   = view.findViewById<MaterialButton>(R.id.btnFocusPeaking)
        val btnHist = view.findViewById<MaterialButton>(R.id.btnHistogram)
        val btnHud  = view.findViewById<MaterialButton>(R.id.btnHud)

        // Grid buttons
        val gridBtns = mapOf(
            view.findViewById<MaterialButton>(R.id.btnGridNone)   to GridMode.NONE,
            view.findViewById<MaterialButton>(R.id.btnGridThirds) to GridMode.THIRDS,
            view.findViewById<MaterialButton>(R.id.btnGridSquare) to GridMode.SQUARE,
            view.findViewById<MaterialButton>(R.id.btnGridDiag)   to GridMode.DIAGONAL
        )

        fun refreshToggle(btn: MaterialButton, active: Boolean) {
            if (active) {
                btn.setBackgroundColor(0xCCFFFFFF.toInt())
                btn.setTextColor(0xFF111418.toInt())
            } else {
                btn.setBackgroundColor(0x22FFFFFF)
                btn.setTextColor(0xCCFFFFFF.toInt())
            }
        }

        fun refreshGrid() {
            gridBtns.forEach { (btn, mode) -> refreshToggle(btn, settings.gridMode == mode) }
        }

        refreshToggle(btnFP, settings.focusPeakingEnabled)
        refreshToggle(btnHist, settings.histogramEnabled)
        refreshToggle(btnHud, settings.hudEnabled)
        refreshGrid()

        btnFP.setOnClickListener {
            settings = settings.copy(focusPeakingEnabled = !settings.focusPeakingEnabled)
            refreshToggle(btnFP, settings.focusPeakingEnabled)
            onSettingsChanged?.invoke(settings)
        }
        btnHist.setOnClickListener {
            settings = settings.copy(histogramEnabled = !settings.histogramEnabled)
            refreshToggle(btnHist, settings.histogramEnabled)
            onSettingsChanged?.invoke(settings)
        }
        btnHud.setOnClickListener {
            settings = settings.copy(hudEnabled = !settings.hudEnabled)
            refreshToggle(btnHud, settings.hudEnabled)
            onSettingsChanged?.invoke(settings)
        }
        gridBtns.forEach { (btn, mode) ->
            btn.setOnClickListener {
                settings = settings.copy(gridMode = mode)
                refreshGrid()
                onSettingsChanged?.invoke(settings)
            }
        }
    }

    // ── Anti-shake ────────────────────────────────────────────────────────────

    private fun setupAntiShake(view: View) {
        val sw = view.findViewById<SwitchMaterial>(R.id.switchAntiShake)
        sw.isChecked = settings.antiShakeEnabled
        sw.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(antiShakeEnabled = checked)
            onSettingsChanged?.invoke(settings)
        }
    }

    // ── Output format ─────────────────────────────────────────────────────────

    private fun setupOutputFormat(view: View) {
        val btnJpeg = view.findViewById<MaterialButton>(R.id.btnFmtJpeg)
        val btnWebp = view.findViewById<MaterialButton>(R.id.btnFmtWebp)
        val swGps   = view.findViewById<SwitchMaterial>(R.id.switchStripGps)

        fun refreshFmt() {
            val jpegActive = settings.outputFormat == OutputFormat.JPEG
            fun active(btn: MaterialButton, on: Boolean) {
                if (on) { btn.setBackgroundColor(0xCCFFFFFF.toInt()); btn.setTextColor(0xFF111418.toInt()) }
                else    { btn.setBackgroundColor(0x22FFFFFF);          btn.setTextColor(0xCCFFFFFF.toInt()) }
            }
            active(btnJpeg, jpegActive)
            active(btnWebp, !jpegActive)
        }

        refreshFmt()
        swGps.isChecked = settings.stripGpsExif

        btnJpeg.setOnClickListener {
            settings = settings.copy(outputFormat = OutputFormat.JPEG)
            refreshFmt()
            onSettingsChanged?.invoke(settings)
        }
        btnWebp.setOnClickListener {
            settings = settings.copy(outputFormat = OutputFormat.WEBP)
            refreshFmt()
            onSettingsChanged?.invoke(settings)
        }
        swGps.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(stripGpsExif = checked)
            onSettingsChanged?.invoke(settings)
        }
    }

    // ── Night UI ──────────────────────────────────────────────────────────────

    private fun setupCaptureProcessing(view: View) {
        val swSound = view.findViewById<SwitchMaterial>(R.id.switchShutterSound)
        val rowVolume = view.findViewById<View>(R.id.rowShutterVolume)
        val sliderVolume = view.findViewById<Slider>(R.id.sliderShutterVolume)
        val tvVolume = view.findViewById<TextView>(R.id.tvShutterVolume)
        val swPost = view.findViewById<SwitchMaterial>(R.id.switchZoomPostProcess)
        val rowStrength = view.findViewById<View>(R.id.rowPostProcessStrength)
        val sliderStrength = view.findViewById<Slider>(R.id.sliderPostProcessStrength)
        val tvStrength = view.findViewById<TextView>(R.id.tvPostProcessStrength)

        fun refreshStrengthRow() {
            rowStrength.alpha = if (settings.zoomPostProcessEnabled) 1f else 0.38f
            sliderStrength.isEnabled = settings.zoomPostProcessEnabled
            tvStrength.text = settings.zoomPostProcessStrength.toString()
        }

        fun refreshVolumeRow() {
            rowVolume.alpha = if (settings.shutterSoundEnabled) 1f else 0.38f
            sliderVolume.isEnabled = settings.shutterSoundEnabled
            tvVolume.text = settings.shutterSoundVolume.toString()
        }

        swSound.isChecked = settings.shutterSoundEnabled
        sliderVolume.value = settings.shutterSoundVolume.coerceIn(0, 100).toFloat()
        swPost.isChecked = settings.zoomPostProcessEnabled
        sliderStrength.value = settings.zoomPostProcessStrength.coerceIn(0, 100).toFloat()
        refreshVolumeRow()
        refreshStrengthRow()

        swSound.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(shutterSoundEnabled = checked)
            refreshVolumeRow()
            onSettingsChanged?.invoke(settings)
        }
        sliderVolume.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val volume = value.toInt().coerceIn(0, 100)
            settings = settings.copy(shutterSoundVolume = volume)
            tvVolume.text = volume.toString()
            onSettingsChanged?.invoke(settings)
        }
        swPost.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(zoomPostProcessEnabled = checked)
            refreshStrengthRow()
            onSettingsChanged?.invoke(settings)
        }
        sliderStrength.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val strength = value.toInt().coerceIn(0, 100)
            settings = settings.copy(zoomPostProcessStrength = strength)
            tvStrength.text = strength.toString()
            onSettingsChanged?.invoke(settings)
        }
    }

    private fun setupNightUi(view: View) {
        val sw = view.findViewById<SwitchMaterial>(R.id.switchNightUi)
        sw.isChecked = settings.nightUiEnabled
        sw.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(nightUiEnabled = checked)
            onSettingsChanged?.invoke(settings)
        }
    }

    companion object {
        const val TAG = "ProSettingsSheet"
    }
}
