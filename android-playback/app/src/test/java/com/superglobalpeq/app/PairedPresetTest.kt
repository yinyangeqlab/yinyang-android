package com.superglobalpeq.app

import com.superglobalpeq.app.dsp.EqualizerApoParser
import com.superglobalpeq.app.dsp.BiquadCoefficients
import com.superglobalpeq.app.dsp.StereoPeqProcessor
import com.superglobalpeq.app.model.ActiveDspConfig
import com.superglobalpeq.app.model.EqPresetBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class PairedPresetTest {
    @Test fun classifiesMainChannelAndPairedExports() {
        val main = "Preamp: -2 dB\nFilter 1: ON PK Fc 1000 Hz Gain 1 dB Q 1"
        val channel = "Channel: L\nPreamp: -1 dB\nChannel: R\nPreamp: 0 dB"
        assertEquals(PresetKind.MAIN, PresetKind.of(PresetInput.parse(main, "Main").preset))
        assertEquals(PresetKind.CHANNEL, PresetKind.of(PresetInput.parse(channel, "Channel").preset))
        assertEquals(PresetKind.PAIRED, PresetKind.of(PresetInput.parse("$main\n$channel", "Pair").preset))
    }

    @Test fun preservesFilterSettingsAcrossTheCompanionRange() {
        val imported = PresetInput.parse(
            """
            Preamp: -40 dB
            Filter 1: ON PK Fc 1000 Hz Gain 35 dB Q 0.009765625
            Channel: L
            Filter 1: ON LSC Fc 22000 Hz Gain -35 dB Q 63
            """.trimIndent(),
            "Full filter range"
        )
        val common = imported.preset.commonBands.single()
        val left = imported.preset.leftBands.single()
        assertEquals(35.0, common.gainDb, 0.0)
        assertEquals(0.009765625, common.q, 0.0)
        assertEquals(-35.0, left.gainDb, 0.0)
        assertEquals(63.0, left.q, 0.0)
        assertTrue(BiquadCoefficients.fromBand(common).magnitudeDbAt(2000.0, 48000.0) > 34.0)
        assertThrows(IllegalArgumentException::class.java) {
            PresetInput.parse("Filter 1: ON PK Fc 1000 Hz Gain 0 dB Q 64", "Out of range")
        }
    }

    @Test fun rejectsUnsupportedInputAndBoundsSharedNames() {
        assertEquals("From YYEQ5K", PresetInput.title("From\u202e YYEQ5K\n"))
        assertThrows(IllegalArgumentException::class.java) {
            PresetInput.parse("Include: /other.txt", "Unsupported")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresetInput.parse("Preamp: 100 dB", "Excessive gain")
        }
        assertThrows(IOException::class.java) {
            PresetInput.read(ByteArray(PresetInput.LIMIT + 1).inputStream())
        }
    }

    @Test fun commonAndEarSectionsDriveIndependentOutput() {
        val imported = EqualizerApoParser.parse(
            """
            Preamp: -6 dB
            Filter 1: ON PK Fc 1000 Hz Gain 0 dB Q 1

            Channel: L
            Preamp: -6 dB
            Filter 1: ON PK Fc 700 Hz Gain 0 dB Q 1

            Channel: R
            Preamp: 0 dB
            Filter 1: ON PK Fc 1300 Hz Gain 0 dB Q 1
            """.trimIndent(),
            "Paired fixture"
        )
        assertTrue(imported.warnings.isEmpty())
        val preset = imported.preset
        assertEquals(1000.0, preset.commonBands.single().frequencyHz, 0.0)
        assertEquals(700.0, preset.leftBands.single().frequencyHz, 0.0)
        assertEquals(1300.0, preset.rightBands.single().frequencyHz, 0.0)
        val processor = StereoPeqProcessor()
        processor.updateConfig(ActiveDspConfig(preset = preset))
        var samples = ShortArray(0)
        repeat(20) {
            samples = ShortArray(960) { 8000 }
            processor.processInterleavedPcm16(samples, samples.size)
        }
        assertEquals(2010.0, samples[958].toDouble(), 12.0)
        assertEquals(4010.0, samples[959].toDouble(), 12.0)
    }

    @Test fun exportRoundTripsFilterPrecisionChannelSectionsAndSwap() {
        val source = PresetInput.parse(
            """
            # Quve-ChannelSwap: true
            # Quve-SwapPosition: AFTER_INDEPENDENT_EQ
            Preamp: -1.234 dB
            Filter 1: ON PK Fc 1000.125 Hz Gain 1.2345 dB Q 0.009765625
            Channel: L
            Filter 1: OFF PK Fc 99.5 Hz Gain -4.1234 dB Q 0.7071067811865476
            Channel: R
            Preamp: -2.375 dB
            """.trimIndent(), "Precise paired preset"
        ).preset
        val imported = PresetInput.parse(EqualizerApoParser.export(source), "Reimported")
        assertTrue(imported.warnings.isEmpty())
        val baseline = EqPresetBaseline.fromPreset(source)
        assertEquals(baseline, EqPresetBaseline.fromPreset(imported.preset).copy(
            commonBands = imported.preset.commonBands.mapIndexed { index, band ->
                band.copy(id = baseline.commonBands[index].id)
            },
            leftBands = imported.preset.leftBands.mapIndexed { index, band ->
                band.copy(id = baseline.leftBands[index].id)
            },
        ))
    }

    @Test fun pairedPlaybackCanApplyEitherSectionOrBothWithoutChangingThePreset() {
        val source = PresetInput.parse(
            """
            Preamp: -4 dB
            Filter 1: ON PK Fc 1000 Hz Gain 1 dB Q 1
            Channel: L
            Preamp: -2 dB
            Filter 1: ON PK Fc 100 Hz Gain 1 dB Q 1
            Channel: R
            Preamp: -1 dB
            """.trimIndent(), "Paired"
        ).preset
        val main = PresetPlaybackMode.MAIN.config(source).preset
        val channel = PresetPlaybackMode.CHANNEL.config(source).preset
        assertEquals(PresetKind.MAIN, PresetKind.of(main))
        assertEquals(PresetKind.CHANNEL, PresetKind.of(channel))
        assertEquals(source.commonBands, main.commonBands)
        assertEquals(source.commonPreampDb, main.commonPreampDb, 0.0)
        assertEquals(source.leftBands, channel.leftBands)
        assertEquals(source.leftPreampDb, channel.leftPreampDb, 0.0)
        assertEquals(source.rightPreampDb, channel.rightPreampDb, 0.0)
        assertEquals(source, PresetPlaybackMode.BOTH.config(source).preset)
        assertEquals(PresetPlaybackMode.MAIN, PresetPlaybackMode.forPreset(main, PresetPlaybackMode.BOTH))
        assertEquals(PresetPlaybackMode.CHANNEL, PresetPlaybackMode.forPreset(channel, PresetPlaybackMode.MAIN))
        assertEquals(PresetKind.CHANNEL, PresetKind.of(
            PresetInput.parse(EqualizerApoParser.export(channel), "Channel Match only").preset
        ))
    }

    @Test fun exportFilenameKeepsOneExtensionAndRemovesPathSeparators() {
        assertEquals("Fit-Left.txt", PresetInput.exportFilename("Fit/Left.TXT"))
        assertEquals("Phone EQ preset.txt", PresetInput.exportFilename("..."))
    }
}
