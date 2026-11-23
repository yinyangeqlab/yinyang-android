package com.superglobalpeq.app.dsp

import com.superglobalpeq.app.model.ActiveDspConfig
import com.superglobalpeq.app.model.EqPreset
import com.superglobalpeq.app.model.FilterType
import com.superglobalpeq.app.model.PeqBand
import com.superglobalpeq.app.model.SwapPosition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
) {
    fun magnitudeDbAt(freqHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -b1 * sinW - b2 * sin2W
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -a1 * sinW - a2 * sin2W

        val numSq = numRe * numRe + numIm * numIm
        val denSq = (denRe * denRe + denIm * denIm).coerceAtLeast(1e-30)
        val magSq = (numSq / denSq).coerceAtLeast(1e-12)
        return 10.0 * log10(magSq)
    }

    companion object {
        val UNITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        fun fromBand(band: PeqBand, sampleRate: Double = 48000.0): BiquadCoefficients {
            if (!band.enabled) return UNITY
            val fc = band.frequencyHz.coerceIn(5.0, sampleRate * 0.495)
            val q = band.q
            val w0 = 2.0 * PI * fc / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)
            val aLin = 10.0.pow(band.gainDb / 40.0)

            var b0 = 1.0
            var b1 = 0.0
            var b2 = 0.0
            var a0 = 1.0
            var a1 = 0.0
            var a2 = 0.0

            when (band.type) {
                FilterType.PK -> {
                    b0 = 1.0 + alpha * aLin
                    b1 = -2.0 * cosW0
                    b2 = 1.0 - alpha * aLin
                    a0 = 1.0 + alpha / aLin
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha / aLin
                }
                FilterType.LSC -> {
                    val twoSqrtAAlpha = 2.0 * sqrt(aLin) * alpha
                    b0 = aLin * ((aLin + 1.0) - (aLin - 1.0) * cosW0 + twoSqrtAAlpha)
                    b1 = 2.0 * aLin * ((aLin - 1.0) - (aLin + 1.0) * cosW0)
                    b2 = aLin * ((aLin + 1.0) - (aLin - 1.0) * cosW0 - twoSqrtAAlpha)
                    a0 = (aLin + 1.0) + (aLin - 1.0) * cosW0 + twoSqrtAAlpha
                    a1 = -2.0 * ((aLin - 1.0) + (aLin + 1.0) * cosW0)
                    a2 = (aLin + 1.0) + (aLin - 1.0) * cosW0 - twoSqrtAAlpha
                }
                FilterType.HSC -> {
                    val twoSqrtAAlpha = 2.0 * sqrt(aLin) * alpha
                    b0 = aLin * ((aLin + 1.0) + (aLin - 1.0) * cosW0 + twoSqrtAAlpha)
                    b1 = -2.0 * aLin * ((aLin - 1.0) + (aLin + 1.0) * cosW0)
                    b2 = aLin * ((aLin + 1.0) + (aLin - 1.0) * cosW0 - twoSqrtAAlpha)
                    a0 = (aLin + 1.0) - (aLin - 1.0) * cosW0 + twoSqrtAAlpha
                    a1 = 2.0 * ((aLin - 1.0) - (aLin + 1.0) * cosW0)
                    a2 = (aLin + 1.0) - (aLin - 1.0) * cosW0 - twoSqrtAAlpha
                }
                FilterType.LP -> {
                    b0 = (1.0 - cosW0) * 0.5
                    b1 = 1.0 - cosW0
                    b2 = (1.0 - cosW0) * 0.5
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha
                }
                FilterType.HP -> {
                    b0 = (1.0 + cosW0) * 0.5
                    b1 = -(1.0 + cosW0)
                    b2 = (1.0 + cosW0) * 0.5
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha
                }
                FilterType.BP -> {
                    b0 = alpha
                    b1 = 0.0
                    b2 = -alpha
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha
                }
                FilterType.NO -> {
                    b0 = 1.0
                    b1 = -2.0 * cosW0
                    b2 = 1.0
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha
                }
                FilterType.AP -> {
                    b0 = 1.0 - alpha
                    b1 = -2.0 * cosW0
                    b2 = 1.0 + alpha
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha
                }
            }

            if (a0 == 0.0 || !a0.isFinite()) return UNITY
            val invA0 = 1.0 / a0
            return BiquadCoefficients(
                b0 = b0 * invA0,
                b1 = b1 * invA0,
                b2 = b2 * invA0,
                a1 = a1 * invA0,
                a2 = a2 * invA0
            )
        }
    }
}

private class BiquadState {
    private var z1: Double = 0.0
    private var z2: Double = 0.0

    fun process(x: Double, c: BiquadCoefficients): Double {
        val y = c.b0 * x + z1
        z1 = c.b1 * x - c.a1 * y + z2
        z2 = c.b2 * x - c.a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}

data class FrequencyResponseCurves(
    val frequenciesHz: FloatArray,
    val commonDb: FloatArray,
    val leftIndependentDb: FloatArray,
    val rightIndependentDb: FloatArray,
    val totalLeftDb: FloatArray,
    val totalRightDb: FloatArray,
    val peakLeftDb: Double,
    val peakRightDb: Double
)

object FrequencyResponseCalculator {
    private const val SAMPLE_RATE = 48000.0
    private const val POINT_COUNT = 256

    val defaultFrequencies: FloatArray = FloatArray(POINT_COUNT) { idx ->
        val t = idx.toDouble() / (POINT_COUNT - 1)
        (20.0 * 1000.0.pow(t)).toFloat() // 20 Hz .. 20,000 Hz
    }

    fun calculate(preset: EqPreset, sampleRate: Double = SAMPLE_RATE): FrequencyResponseCurves {
        val commonCoeffs = preset.commonBands.filter { it.enabled }.map {
            BiquadCoefficients.fromBand(it, sampleRate)
        }
        val leftCoeffs = preset.leftBands.filter { it.enabled }.map {
            BiquadCoefficients.fromBand(it, sampleRate)
        }
        val rightCoeffs = preset.rightBands.filter { it.enabled }.map {
            BiquadCoefficients.fromBand(it, sampleRate)
        }

        val commonDb = FloatArray(POINT_COUNT)
        val leftIndepDb = FloatArray(POINT_COUNT)
        val rightIndepDb = FloatArray(POINT_COUNT)
        val totalLeftDb = FloatArray(POINT_COUNT)
        val totalRightDb = FloatArray(POINT_COUNT)

        var peakLeft = Double.NEGATIVE_INFINITY
        var peakRight = Double.NEGATIVE_INFINITY

        for (i in 0 until POINT_COUNT) {
            val f = defaultFrequencies[i].toDouble()
            var cDb = preset.commonPreampDb
            for (k in commonCoeffs.indices) {
                cDb += commonCoeffs[k].magnitudeDbAt(f, sampleRate)
            }
            var lDb = preset.leftPreampDb
            for (k in leftCoeffs.indices) {
                lDb += leftCoeffs[k].magnitudeDbAt(f, sampleRate)
            }
            var rDb = preset.rightPreampDb
            for (k in rightCoeffs.indices) {
                rDb += rightCoeffs[k].magnitudeDbAt(f, sampleRate)
            }

            val totL = cDb + lDb
            val totR = cDb + rDb
            if (totL > peakLeft) peakLeft = totL
            if (totR > peakRight) peakRight = totR

            commonDb[i] = cDb.toFloat()
            leftIndepDb[i] = lDb.toFloat()
            rightIndepDb[i] = rDb.toFloat()
            totalLeftDb[i] = totL.toFloat()
            totalRightDb[i] = totR.toFloat()
        }

        if (!peakLeft.isFinite()) peakLeft = 0.0
        if (!peakRight.isFinite()) peakRight = 0.0

        return FrequencyResponseCurves(
            frequenciesHz = defaultFrequencies,
            commonDb = commonDb,
            leftIndependentDb = leftIndepDb,
            rightIndependentDb = rightIndepDb,
            totalLeftDb = totalLeftDb,
            totalRightDb = totalRightDb,
            peakLeftDb = peakLeft,
            peakRightDb = peakRight
        )
    }

    /**
     * Computes suggested common preamp offset (without independent preamp) or exact remaining headroom
     * so that peak of Total L/R sits at 0.0 dB.
     */
    fun computeAutoPreampAdjustmentDb(preset: EqPreset, sampleRate: Double = SAMPLE_RATE): Double {
        val curves = calculate(preset, sampleRate)
        val maxTotal = max(curves.peakLeftDb, curves.peakRightDb)
        return (preset.commonPreampDb - maxTotal).coerceIn(-24.0, 12.0)
    }
}

/**
 * Thread-safe, zero-allocation (in audio thread) 64-bit TDF-II stereo parametric EQ processor.
 */
class StereoPeqProcessor(private val sampleRate: Double = 48000.0) {
    private class CompiledChain(
        val bypass: Boolean,
        val channelSwap: Boolean,
        val swapPosition: SwapPosition,
        val commonPreampLinear: Double,
        val leftPreampLinear: Double,
        val rightPreampLinear: Double,
        val commonCoeffs: Array<BiquadCoefficients>,
        val leftCoeffs: Array<BiquadCoefficients>,
        val rightCoeffs: Array<BiquadCoefficients>
    )

    @Volatile
    private var activeChain = CompiledChain(
        bypass = false,
        channelSwap = false,
        swapPosition = SwapPosition.BEFORE_INDEPENDENT_EQ,
        commonPreampLinear = 1.0,
        leftPreampLinear = 1.0,
        rightPreampLinear = 1.0,
        commonCoeffs = emptyArray(),
        leftCoeffs = emptyArray(),
        rightCoeffs = emptyArray()
    )

    // Accessed exclusively by the real-time audio thread; topology changes resize cleanly.
    private var commonLeftStates = emptyArray<BiquadState>()
    private var commonRightStates = emptyArray<BiquadState>()
    private var indepLeftStates = emptyArray<BiquadState>()
    private var indepRightStates = emptyArray<BiquadState>()
    private var statesValid = false

    fun updateConfig(config: ActiveDspConfig) {
        val preset = config.preset
        val common = preset.commonBands.filter { it.enabled }.map {
            BiquadCoefficients.fromBand(it, sampleRate)
        }.toTypedArray()
        val left = preset.leftBands.filter { it.enabled }.map {
            BiquadCoefficients.fromBand(it, sampleRate)
        }.toTypedArray()
        val right = preset.rightBands.filter { it.enabled }.map {
            BiquadCoefficients.fromBand(it, sampleRate)
        }.toTypedArray()

        activeChain = CompiledChain(
            bypass = config.bypass,
            channelSwap = config.channelSwap,
            swapPosition = config.swapPosition,
            commonPreampLinear = dbToLinear(preset.commonPreampDb),
            leftPreampLinear = dbToLinear(preset.leftPreampDb),
            rightPreampLinear = dbToLinear(preset.rightPreampDb),
            commonCoeffs = common,
            leftCoeffs = left,
            rightCoeffs = right
        )
    }

    fun resetStates() {
        statesValid = false
    }

    fun processInterleavedPcm16(pcm: ShortArray, sampleCount: Int) {
        val chain = activeChain
        if (chain.bypass) {
            if (chain.channelSwap) {
                var i = 0
                while (i + 1 < sampleCount) {
                    val tmp = pcm[i]
                    pcm[i] = pcm[i + 1]
                    pcm[i + 1] = tmp
                    i += 2
                }
            }
            return
        }

        ensureStateArrays(chain)

        val commonCoeffs = chain.commonCoeffs
        val leftCoeffs = chain.leftCoeffs
        val rightCoeffs = chain.rightCoeffs
        val swapBefore = chain.channelSwap && chain.swapPosition == SwapPosition.BEFORE_INDEPENDENT_EQ
        val swapAfter = chain.channelSwap && chain.swapPosition == SwapPosition.AFTER_INDEPENDENT_EQ

        var i = 0
        while (i + 1 < sampleCount) {
            var l = pcm[i] * INV_32768
            var r = pcm[i + 1] * INV_32768

            // Stage 1: Common (L+R) preamp and filters
            l *= chain.commonPreampLinear
            r *= chain.commonPreampLinear
            for (k in commonCoeffs.indices) {
                val c = commonCoeffs[k]
                l = commonLeftStates[k].process(l, c)
                r = commonRightStates[k].process(r, c)
            }

            // Stage 2: Default hardware-preserving channel swap (before independent L/R EQ)
            if (swapBefore) {
                val tmp = l
                l = r
                r = tmp
            }

            // Stage 3: Independent Left / Right calibration preamp and filters
            l *= chain.leftPreampLinear
            for (k in leftCoeffs.indices) {
                l = indepLeftStates[k].process(l, leftCoeffs[k])
            }
            r *= chain.rightPreampLinear
            for (k in rightCoeffs.indices) {
                r = indepRightStates[k].process(r, rightCoeffs[k])
            }

            // Stage 4: Optional post-calibration swap (for reversed output wiring)
            if (swapAfter) {
                val tmp = l
                l = r
                r = tmp
            }

            pcm[i] = quantizeSoftLimited(l)
            pcm[i + 1] = quantizeSoftLimited(r)
            i += 2
        }
    }

    /**
     * Convenience helper for unit testing double stereo buffers without 16-bit quantization.
     */
    fun processStereoSampleDouble(leftIn: Double, rightIn: Double): Pair<Double, Double> {
        val chain = activeChain
        if (chain.bypass) {
            return if (chain.channelSwap) rightIn to leftIn else leftIn to rightIn
        }
        ensureStateArrays(chain)

        var l = leftIn * chain.commonPreampLinear
        var r = rightIn * chain.commonPreampLinear
        for (k in chain.commonCoeffs.indices) {
            val c = chain.commonCoeffs[k]
            l = commonLeftStates[k].process(l, c)
            r = commonRightStates[k].process(r, c)
        }

        if (chain.channelSwap && chain.swapPosition == SwapPosition.BEFORE_INDEPENDENT_EQ) {
            val tmp = l
            l = r
            r = tmp
        }

        l *= chain.leftPreampLinear
        for (k in chain.leftCoeffs.indices) {
            l = indepLeftStates[k].process(l, chain.leftCoeffs[k])
        }
        r *= chain.rightPreampLinear
        for (k in chain.rightCoeffs.indices) {
            r = indepRightStates[k].process(r, chain.rightCoeffs[k])
        }

        if (chain.channelSwap && chain.swapPosition == SwapPosition.AFTER_INDEPENDENT_EQ) {
            val tmp = l
            l = r
            r = tmp
        }
        return l to r
    }

    private fun ensureStateArrays(chain: CompiledChain) {
        if (!statesValid || commonLeftStates.size != chain.commonCoeffs.size) {
            commonLeftStates = Array(chain.commonCoeffs.size) { BiquadState() }
            commonRightStates = Array(chain.commonCoeffs.size) { BiquadState() }
        }
        if (!statesValid || indepLeftStates.size != chain.leftCoeffs.size) {
            indepLeftStates = Array(chain.leftCoeffs.size) { BiquadState() }
        }
        if (!statesValid || indepRightStates.size != chain.rightCoeffs.size) {
            indepRightStates = Array(chain.rightCoeffs.size) { BiquadState() }
        }
        statesValid = true
    }

    private fun quantizeSoftLimited(sample: Double): Short {
        if (!sample.isFinite()) return 0
        val out = if (sample > 0.96 || sample < -0.96) {
            val excess = (kotlin.math.abs(sample) - 0.96) * 25.0
            val limited = 0.96 + 0.0399 * tanh(excess)
            if (sample >= 0.0) limited else -limited
        } else {
            sample
        }
        val scaled = (out * 32767.0).roundToInt()
        return min(32767, max(-32767, scaled)).toShort()
    }

    companion object {
        private const val INV_32768 = 1.0 / 32768.0

        private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
    }
}
