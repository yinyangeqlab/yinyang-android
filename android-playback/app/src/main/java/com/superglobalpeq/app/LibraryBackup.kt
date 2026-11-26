package com.superglobalpeq.app

import com.superglobalpeq.app.model.EqPreset
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class LibraryBackup(val presets: List<EqPreset>, val selectedId: String?) {
    fun encode(): ByteArray {
        val bytes = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("selectedId", selectedId ?: JSONObject.NULL)
            .put("presets", JSONArray().apply { presets.forEach { put(it.toJson()) } })
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "The library exceeds the 16 MB backup limit." }
        return bytes
    }

    companion object {
        private const val FORMAT = "yinyang-phone-eq-library"
        private const val VERSION = 1
        private const val MAX_BYTES = 16 * 1024 * 1024

        fun read(input: InputStream): LibraryBackup {
            val bytes = input.readNBytes(MAX_BYTES + 1)
            require(bytes.size <= MAX_BYTES) { "Choose a Phone EQ backup under 16 MB." }
            val source = JSONTokener(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            val backup = source.nextValue()
            require(backup is JSONObject && source.nextClean() == '\u0000') {
                "Choose a Phone EQ library backup file."
            }
            require(backup.opt("format") == FORMAT) { "Choose a Phone EQ library backup file." }
            require(backup.opt("version") == VERSION) { "This backup version needs a different version of Phone EQ." }
            require(backup.length() == 4 && backup.has("selectedId")) { "The backup format does not match Phone EQ." }
            val entries = backup.getJSONArray("presets")
            val ids = mutableSetOf<String>()
            val presets = List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                val id = entry.get("id")
                require(id is String && id.isNotBlank() && id.length <= 128 && ids.add(id)) {
                    "The backup contains an invalid or repeated preset ID."
                }
                val preset = EqPreset.fromJson(entry)
                require(matchesFormat(entry, preset.toJson())) { "The backup contains a preset in the wrong format." }
                require(preset.name == PresetInput.title(preset.name)) { "The backup contains an invalid preset name." }
                PresetInput.validate(preset)
                preset
            }
            val selection = backup.get("selectedId")
            require(selection == JSONObject.NULL || selection is String) { "The backup contains an invalid selection." }
            val selectedId = selection as? String
            require(selectedId == null || selectedId in ids) { "The selected preset is missing from this backup." }
            return LibraryBackup(presets, selectedId)
        }

        // Compare parsed values so indentation, object key order and equivalent numbers are allowed.
        private fun matchesFormat(source: Any?, encoded: Any?): Boolean = when {
            source is JSONObject && encoded is JSONObject ->
                source.length() == encoded.length() && encoded.keys().asSequence().all { key ->
                    source.has(key) && matchesFormat(source.opt(key), encoded.opt(key))
                }
            source is JSONArray && encoded is JSONArray ->
                source.length() == encoded.length() && (0 until encoded.length()).all { index ->
                    matchesFormat(source.opt(index), encoded.opt(index))
                }
            source is Number && encoded is Number -> source.toDouble() == encoded.toDouble()
            else -> source == encoded
        }
    }
}
