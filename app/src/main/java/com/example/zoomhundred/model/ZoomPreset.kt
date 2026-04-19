package com.example.zoomhundred.model

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class ZoomPreset(val label: String, val ratio: Float)

object ZoomPresets {
    private const val PREF_FILE = "zoom_presets"
    private const val KEY = "presets_json"

    private val DEFAULTS = listOf(
        ZoomPreset("1x", 1f),
        ZoomPreset("2x", 2f),
        ZoomPreset("5x", 5f),
        ZoomPreset("10x", 10f),
        ZoomPreset("30x", 30f),
        ZoomPreset("100x", 100f)
    )

    fun load(context: Context): MutableList<ZoomPreset> {
        val json = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return DEFAULTS.toMutableList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                ZoomPreset(obj.getString("label"), obj.getDouble("ratio").toFloat())
            }.toMutableList()
        } catch (e: Exception) {
            DEFAULTS.toMutableList()
        }
    }

    fun save(context: Context, presets: List<ZoomPreset>) {
        val arr = JSONArray()
        presets.forEach {
            arr.put(JSONObject().apply {
                put("label", it.label)
                put("ratio", it.ratio.toDouble())
            })
        }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit {
            putString(KEY, arr.toString())
        }
    }
}
