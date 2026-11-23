package com.superglobalpeq.app

import com.superglobalpeq.app.dsp.EqualizerApoParser
import com.superglobalpeq.app.model.EqPreset
import com.superglobalpeq.app.model.PeqBand
import java.io.IOException
import java.io.InputStream

internal object PresetInput {
    const val LIMIT = 65536
    private const val MAX_FILTERS = 160

    fun title(value: String): String = value.take(160).filter { !it.isISOControl() &&
        Character.getType(it) != Character.FORMAT.toInt() }.trim().take(100).ifBlank { "Imported preset" }

    fun parse(text: CharSequence, name: String): EqualizerApoParser.ParseResult {
        require(text.length <= LIMIT) { "Choose a TXT file under 64 KB." }
        val parsed = EqualizerApoParser.parse(text.toString().removePrefix("\uFEFF"), title(name))
        validate(parsed.preset)
        return parsed
    }

    fun validate(preset: EqPreset) {
        val count = preset.commonBands.size + preset.leftBands.size + preset.rightBands.size
        val preamps = listOf(preset.commonPreampDb, preset.leftPreampDb, preset.rightPreampDb)
        require(count > 0 || preamps.any { it != 0.0 }) { "No supported EQ filters or preamp settings were found." }
        require(count <= MAX_FILTERS && preamps.all { it.isFinite() && it in -60.0..24.0 }) {
            "The preset exceeds the supported filter or preamp range."
        }
        val bands = preset.commonBands + preset.leftBands + preset.rightBands
        require(bands.all { it.frequencyHz in PeqBand.frequencyRange && it.gainDb in PeqBand.gainRange &&
            it.q in PeqBand.qRange }) {
            "Use filters from 10 to 22000 Hz, gain from −50 to +50 dB, and Q from 0.009765625 to 63."
        }
    }

    fun read(input: InputStream): String {
        val bytes = input.readNBytes(LIMIT + 1)
        if (bytes.size > LIMIT) throw IOException("Choose a TXT file under 64 KB.")
        return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }
}
