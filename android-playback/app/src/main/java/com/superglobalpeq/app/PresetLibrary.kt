package com.superglobalpeq.app

import android.content.SharedPreferences
import com.superglobalpeq.app.model.EqPreset
import java.io.IOException
import java.util.UUID
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

    fun load(): List<EqPreset> = synchronized(libraryLock) {
        val entries = preferences.all.mapNotNull { (key, value) ->
            if (key.startsWith(PREFIX)) decode(value as? String) else null
        }
        val legacy = selected()?.takeUnless { preset -> entries.any { it.id == preset.id } }
        if (legacy != null) {
            preferences.edit().putString(PREFIX + legacy.id, legacy.toJson().toString()).apply()
        }
        (entries + listOfNotNull(legacy)).sortedByDescending { it.updatedAtEpochMs }
    }

    fun select(id: String): EqPreset? = synchronized(libraryLock) {
        val encoded = preferences.getString(PREFIX + id, null)
        val preset = decode(encoded) ?: return@synchronized null
        preferences.edit().putString(SELECTED, encoded).apply()
        preset
    }

    fun saveAndSelect(preset: EqPreset) = synchronized(libraryLock) {
        val serialized = preset.toJson().toString()
        preferences.edit().putString(PREFIX + preset.id, serialized).putString(SELECTED, serialized).apply()
    }

    fun delete(id: String): Boolean = synchronized(libraryLock) {
        val key = PREFIX + id
        val stored = preferences.getString(key, null)
        val selection = preferences.getString(SELECTED, null)
        val wasSelected = decode(selection)?.id == id
        if (stored == null && !wasSelected) return@synchronized false

        val edit = preferences.edit().remove(key)
        if (wasSelected) edit.remove(SELECTED)
        if (!edit.commit()) {
            val rollback = preferences.edit().putString(key, stored)
            if (wasSelected) rollback.putString(SELECTED, selection)
            rollback.apply()
            throw IOException("Could not delete the preset. Check available space and try again.")
        }
        true
    }

    fun backup(): LibraryBackup = synchronized(libraryLock) {
        LibraryBackup(load(), selected()?.id)
    }

    fun restore(backup: LibraryBackup): Int = synchronized(libraryLock) {
        val existing = load().associateBy { it.id }.toMutableMap()
        val edits = preferences.edit()
        val previousSelection = preferences.getString(SELECTED, null)
        val selectFromBackup = selected() == null
        val originals = mutableMapOf<String, String?>()
        var added = 0
        for (source in backup.presets) {
            var preset = source
            // Keep a different preset under the same ID. Use a stable copy ID on a repeated restore.
            while (existing[preset.id]?.let { it != preset } == true) {
                val id = UUID.nameUUIDFromBytes(preset.toJson().toString().toByteArray(Charsets.UTF_8))
                preset = preset.copy(id = id.toString())
            }
            if (existing.putIfAbsent(preset.id, preset) == null) {
                val key = PREFIX + preset.id
                originals[key] = preferences.getString(key, null)
                edits.putString(key, preset.toJson().toString())
                added++
            }
            if (selectFromBackup && source.id == backup.selectedId) {
                originals[SELECTED] = previousSelection
                edits.putString(SELECTED, preset.toJson().toString())
            }
        }
        if (originals.isNotEmpty() && !edits.commit()) {
            val rollback = preferences.edit()
            originals.forEach { (key, oldValue) -> rollback.putString(key, oldValue) }
            rollback.apply()
            throw IOException("Phone EQ could not save the restored presets. Check available space and try again.")
        }
        added
    }

    companion object {
        private const val PREFIX = "library."
        private const val SELECTED = "preset"
        private val libraryLock = Any()

        fun ownsKey(key: String?) = key == SELECTED || key?.startsWith(PREFIX) == true

        fun decode(json: String?): EqPreset? = runCatching {
            json?.let { EqPreset.fromJson(JSONObject(it)).also(PresetInput::validate) }
        }.getOrNull()
    }
}
