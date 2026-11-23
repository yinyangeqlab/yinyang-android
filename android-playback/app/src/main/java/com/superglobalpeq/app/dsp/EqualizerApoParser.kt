package com.superglobalpeq.app.dsp

import com.superglobalpeq.app.model.EqPreset
import com.superglobalpeq.app.model.FilterType
import com.superglobalpeq.app.model.PeqBand
import com.superglobalpeq.app.model.PeqChannelTarget
import com.superglobalpeq.app.model.SwapPosition
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.pow

object EqualizerApoParser {
    data class ParseResult(
        val preset: EqPreset,
        val warnings: List<String>
    )

    private val quveSwapRegex = Regex(
        """^\s*#\s*Quve-ChannelSwap\s*:\s*(true|false|on|off|1|0)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val quveSwapPosRegex = Regex(
        """^\s*#\s*Quve-SwapPosition\s*:\s*([A-Za-z_]+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val preampRegex = Regex(
        """^\s*Preamp\s*:\s*([+-]?\d+(?:[.,]\d+)?)\s*(?:dB)?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val channelRegex = Regex("""^\s*Channel\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val filterHeaderRegex = Regex(
        """^\s*Filter(?:\s+\d+)?\s*:\s*(ON|OFF)\s+([A-Za-z]+)\s+Fc\s+(\d+(?:[.,]\d+)?)\s*(?:Hz)?(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val gainRegex = Regex("""\bGain\s+([+-]?\d+(?:[.,]\d+)?)\s*(?:dB)?""", RegexOption.IGNORE_CASE)
    private val qRegex = Regex("""\bQ\s+(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
    private val bwOctRegex = Regex("""\bBW\s+Oct\s+(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)

    fun parse(
        text: String,
        presetName: String,
        existingId: String? = null
    ): ParseResult {
        var currentTarget = PeqChannelTarget.COMMON
        var commonPreamp = 0.0
        var leftPreamp = 0.0
        var rightPreamp = 0.0
        val commonBands = mutableListOf<PeqBand>()
        val leftBands = mutableListOf<PeqBand>()
        val rightBands = mutableListOf<PeqBand>()
        var channelSwap = false
        var swapPosition = SwapPosition.BEFORE_INDEPENDENT_EQ
        val warnings = mutableListOf<String>()

        text.lineSequence().forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val trimmedRaw = rawLine.trim()
            quveSwapRegex.matchEntire(trimmedRaw)?.let { m ->
                val v = m.groupValues[1].lowercase(Locale.US)
                channelSwap = v == "true" || v == "on" || v == "1"
                return@forEachIndexed
            }
            quveSwapPosRegex.matchEntire(trimmedRaw)?.let { m ->
                val name = m.groupValues[1].uppercase(Locale.US)
                SwapPosition.entries.firstOrNull { it.name == name }?.let { swapPosition = it }
                return@forEachIndexed
            }
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            channelRegex.matchEntire(line)?.let { m ->
                val chSpec = m.groupValues[1].trim().uppercase(Locale.US)
                val tokens = chSpec.split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
                currentTarget = when {
                    tokens.size == 1 && (tokens[0] == "L" || tokens[0] == "1" || tokens[0] == "LEFT") ->
                        PeqChannelTarget.LEFT
                    tokens.size == 1 && (tokens[0] == "R" || tokens[0] == "2" || tokens[0] == "RIGHT") ->
                        PeqChannelTarget.RIGHT
                    tokens.contains("ALL") || (tokens.contains("L") && tokens.contains("R")) ||
                        (tokens.contains("1") && tokens.contains("2")) ->
                        PeqChannelTarget.COMMON
                    else -> {
                        warnings.add("L$lineNo: Unsupported Channel ($chSpec); treated as Common (L+R)")
                        PeqChannelTarget.COMMON
                    }
                }
                return@forEachIndexed
            }

            preampRegex.matchEntire(line)?.let { m ->
                val db = m.groupValues[1].replace(',', '.').toDoubleOrNull()
                if (db == null) {
                    warnings.add("L$lineNo: Failed to parse Preamp value")
                } else {
                    when (currentTarget) {
                        PeqChannelTarget.COMMON -> commonPreamp = db
                        PeqChannelTarget.LEFT -> leftPreamp = db
                        PeqChannelTarget.RIGHT -> rightPreamp = db
                    }
                }
                return@forEachIndexed
            }

            filterHeaderRegex.matchEntire(line)?.let { m ->
                val enabled = m.groupValues[1].equals("ON", ignoreCase = true)
                val typeToken = m.groupValues[2]
                val type = FilterType.fromApoToken(typeToken)
                val freq = m.groupValues[3].replace(',', '.').toDoubleOrNull()
                val tail = m.groupValues[4]

                if (type == null || freq == null) {
                    warnings.add("L$lineNo: Unsupported filter format ($line)")
                    return@forEachIndexed
                }

                val gain = gainRegex.find(tail)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                val explicitQ = qRegex.find(tail)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                val bwOct = bwOctRegex.find(tail)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                val resolvedQ = when {
                    explicitQ != null -> explicitQ
                    bwOct != null && bwOct > 0.0 -> {
                        val twoPowBw = 2.0.pow(bwOct)
                        kotlin.math.sqrt(twoPowBw) / (twoPowBw - 1.0)
                    }
                    else -> 0.7071
                }

                val band = PeqBand(
                    enabled = enabled,
                    type = type,
                    frequencyHz = freq,
                    gainDb = gain,
                    q = resolvedQ
                )
                when (currentTarget) {
                    PeqChannelTarget.COMMON -> commonBands.add(band)
                    PeqChannelTarget.LEFT -> leftBands.add(band)
                    PeqChannelTarget.RIGHT -> rightBands.add(band)
                }
                return@forEachIndexed
            }

            if (!line.startsWith("GraphicEQ", ignoreCase = true) &&
                !line.startsWith("Stage", ignoreCase = true) &&
                !line.startsWith("Include", ignoreCase = true)
            ) {
                warnings.add("L$lineNo: Skipped unrecognized line ($line)")
            } else {
                warnings.add("L$lineNo: Skipped non-PEQ command ($line)")
            }
        }

        val preset = if (existingId != null) {
            EqPreset(
                id = existingId,
                name = presetName,
                commonPreampDb = commonPreamp,
                commonBands = commonBands,
                leftPreampDb = leftPreamp,
                leftBands = leftBands,
                rightPreampDb = rightPreamp,
                rightBands = rightBands,
                channelSwap = channelSwap,
                swapPosition = swapPosition
            )
        } else {
            EqPreset(
                name = presetName,
                commonPreampDb = commonPreamp,
                commonBands = commonBands,
                leftPreampDb = leftPreamp,
                leftBands = leftBands,
                rightPreampDb = rightPreamp,
                rightBands = rightBands,
                channelSwap = channelSwap,
                swapPosition = swapPosition
            )
        }
        return ParseResult(preset, warnings)
    }

    fun parseSingleSection(text: String): Pair<Double?, List<PeqBand>> {
        var preamp: Double? = null
        val bands = mutableListOf<PeqBand>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            preampRegex.matchEntire(line)?.let { m ->
                preamp = m.groupValues[1].replace(',', '.').toDoubleOrNull()
                return@forEach
            }
            filterHeaderRegex.matchEntire(line)?.let { m ->
                val enabled = m.groupValues[1].equals("ON", ignoreCase = true)
                val type = FilterType.fromApoToken(m.groupValues[2]) ?: return@forEach
                val freq = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return@forEach
                val tail = m.groupValues[4]
                val gain = gainRegex.find(tail)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                val q = qRegex.find(tail)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.7071
                bands.add(
                    PeqBand(
                        enabled = enabled,
                        type = type,
                        frequencyHz = freq.coerceIn(PeqBand.frequencyRange),
                        gainDb = gain.coerceIn(PeqBand.gainRange),
                        q = q.coerceIn(PeqBand.qRange)
                    )
                )
            }
        }
        return preamp to bands
    }

    fun export(preset: EqPreset): String {
        fun number(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        return buildString {
            if (preset.channelSwap || preset.swapPosition != SwapPosition.BEFORE_INDEPENDENT_EQ) {
                appendLine("# Quve-ChannelSwap: ${preset.channelSwap}")
                appendLine("# Quve-SwapPosition: ${preset.swapPosition.name}")
            }
            fun appendSection(preamp: Double, bands: List<PeqBand>) {
                appendLine("Preamp: ${number(preamp)} dB")
                bands.forEachIndexed { index, band ->
                    val state = if (band.enabled) "ON" else "OFF"
                    append("Filter ${index + 1}: $state ${band.type.apoCode} Fc ${number(band.frequencyHz)} Hz")
                    if (band.type.supportsGain) append(" Gain ${number(band.gainDb)} dB")
                    appendLine(" Q ${number(band.q)}")
                }
            }
            appendSection(preset.commonPreampDb, preset.commonBands)
            if (preset.leftBands.isNotEmpty() || preset.leftPreampDb != 0.0) {
                appendLine("Channel: L")
                appendSection(preset.leftPreampDb, preset.leftBands)
            }
            if (preset.rightBands.isNotEmpty() || preset.rightPreampDb != 0.0) {
                appendLine("Channel: R")
                appendSection(preset.rightPreampDb, preset.rightBands)
            }
        }
    }
}

object DefaultPresets {
    const val FLAT_PRESET_ID = "preset_flat"

    val FLAT_DEFAULT_NAMES = setOf("フラット (0 dB)", "Flat (0 dB)")

    fun createFlatPreset(name: String = "Flat (0 dB)"): EqPreset = EqPreset(
        id = FLAT_PRESET_ID,
        name = name,
        commonPreampDb = 0.0,
        commonBands = emptyList(),
        leftPreampDb = 0.0,
        leftBands = emptyList(),
        rightPreampDb = 0.0,
        rightBands = emptyList()
    ).withCapturedBaseline()
}
