package com.superglobalpeq.app

import android.content.SharedPreferences
import com.superglobalpeq.app.model.EqPreset
import org.json.JSONObject

internal enum class PresetKind(val label: String) {
    MAIN("Main EQ"),
    CHANNEL("Channel Match"),
    PAIRED("Paired EQ");

    companion object {
        fun of(preset: EqPreset): PresetKind {
            val main = preset.commonPreampDb != 0.0 || preset.commonBands.isNotEmpty()
            val ears = preset.leftPreampDb != 0.0 || preset.rightPreampDb != 0.0 ||
                preset.leftBands.isNotEmpty() || preset.rightBands.isNotEmpty()
            if (main && ears) return PAIRED
            if (ears) return CHANNEL
            return MAIN
        }
    }
}

internal class PresetLibrary(private val preferences: SharedPreferences) {
    fun selected(): EqPreset? = decode(preferences.getString(SELECTED, null))

    fun load(): List<EqPreset> {
        val entries = preferences.all.mapNotNull { (key, value) ->
            if (key.startsWith(PREFIX)) decode(value as? String) else null
        }
        val legacy = selected()?.takeUnless { preset -> entries.any { it.id == preset.id } }
        if (legacy != null) {
            preferences.edit().putString(PREFIX + legacy.id, legacy.toJson().toString()).apply()
        }
        return (entries + listOfNotNull(legacy)).sortedByDescending { it.updatedAtEpochMs }
    }

    fun select(preset: EqPreset) = preferences.edit().putString(SELECTED, preset.toJson().toString()).apply()

    fun saveAndSelect(preset: EqPreset) {
        val serialized = preset.toJson().toString()
        preferences.edit().putString(PREFIX + preset.id, serialized).putString(SELECTED, serialized).apply()
    }

    companion object {
        private const val PREFIX = "library."
        private const val SELECTED = "preset"

        fun decode(json: String?): EqPreset? = runCatching {
            json?.let { EqPreset.fromJson(JSONObject(it)).also(PresetInput::validate) }
        }.getOrNull()
    }
}
