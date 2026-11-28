package com.superglobalpeq.app

import com.superglobalpeq.app.model.ActiveDspConfig
import com.superglobalpeq.app.model.EqPreset

internal enum class PresetPlaybackMode(val label: String) {
    MAIN("Main EQ"),
    CHANNEL("Channel Match"),
    BOTH("Main EQ + Channel Match");

    fun config(preset: EqPreset): ActiveDspConfig {
        val playback = when (this) {
            MAIN -> preset.copy(
                leftPreampDb = 0.0, leftBands = emptyList(),
                rightPreampDb = 0.0, rightBands = emptyList(),
            )
            CHANNEL -> preset.copy(commonPreampDb = 0.0, commonBands = emptyList())
            BOTH -> preset
        }
        return ActiveDspConfig(channelSwap = playback.channelSwap, swapPosition = playback.swapPosition, preset = playback)
    }

    companion object {
        fun forPreset(preset: EqPreset, pairedMode: PresetPlaybackMode): PresetPlaybackMode =
            when (PresetKind.of(preset)) {
                PresetKind.MAIN -> MAIN
                PresetKind.CHANNEL -> CHANNEL
                PresetKind.PAIRED -> pairedMode
            }
    }
}
