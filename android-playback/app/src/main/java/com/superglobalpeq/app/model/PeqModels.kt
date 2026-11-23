package com.superglobalpeq.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

enum class FilterType(
    val apoCode: String,
    val labelEn: String,
    val supportsGain: Boolean
) {
    PK("PK", "PK (Peaking)", true),
    LSC("LSC", "LSC (Low Shelf)", true),
    HSC("HSC", "HSC (High Shelf)", true),
    LP("LP", "LP (Low Pass)", false),
    HP("HP", "HP (High Pass)", false),
    NO("NO", "NO (Notch)", false),
    BP("BP", "BP (Band Pass)", false),
    AP("AP", "AP (All Pass)", false);

    companion object {
        fun fromApoToken(token: String): FilterType? {
            return when (token.trim().uppercase()) {
                "PK", "PEQ", "MODAL" -> PK
                "LSC", "LS", "LOWSHELF" -> LSC
                "HSC", "HS", "HIGHSHELF" -> HSC
                "LP", "LPQ", "LOWPASS" -> LP
                "HP", "HPQ", "HIGHPASS" -> HP
                "NO", "NOTCH" -> NO
                "BP", "BANDPASS" -> BP
                "AP", "ALLPASS" -> AP
                else -> null
            }
        }
    }
}

enum class PeqChannelTarget {
    COMMON,
    LEFT,
    RIGHT
}

enum class SwapPosition {
    BEFORE_INDEPENDENT_EQ,
    AFTER_INDEPENDENT_EQ
}

data class PeqBand(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val type: FilterType = FilterType.PK,
    val frequencyHz: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 1.0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("enabled", enabled)
        put("type", type.name)
        put("frequencyHz", frequencyHz)
        put("gainDb", gainDb)
        put("q", q)
    }

    companion object {
        val frequencyRange = 10.0..22000.0
        val gainRange = -50.0..50.0
        val qRange = (10.0 / 1024.0)..63.0

        fun fromJson(obj: JSONObject): PeqBand = PeqBand(
            id = obj.optString("id", UUID.randomUUID().toString()),
            enabled = obj.optBoolean("enabled", true),
            type = FilterType.entries.firstOrNull { it.name == obj.optString("type") } ?: FilterType.PK,
            frequencyHz = obj.optDouble("frequencyHz", 1000.0),
            gainDb = obj.optDouble("gainDb", 0.0),
            q = obj.optDouble("q", 1.0)
        )
    }
}

data class EqPresetBaseline(
    val commonPreampDb: Double = 0.0,
    val commonBands: List<PeqBand> = emptyList(),
    val leftPreampDb: Double = 0.0,
    val leftBands: List<PeqBand> = emptyList(),
    val rightPreampDb: Double = 0.0,
    val rightBands: List<PeqBand> = emptyList(),
    val channelSwap: Boolean = false,
    val swapPosition: SwapPosition = SwapPosition.BEFORE_INDEPENDENT_EQ
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("commonPreampDb", commonPreampDb)
        put("commonBands", JSONArray().apply { commonBands.forEach { put(it.toJson()) } })
        put("leftPreampDb", leftPreampDb)
        put("leftBands", JSONArray().apply { leftBands.forEach { put(it.toJson()) } })
        put("rightPreampDb", rightPreampDb)
        put("rightBands", JSONArray().apply { rightBands.forEach { put(it.toJson()) } })
        put("channelSwap", channelSwap)
        put("swapPosition", swapPosition.name)
    }

    companion object {
        fun fromPreset(preset: EqPreset): EqPresetBaseline = EqPresetBaseline(
            commonPreampDb = preset.commonPreampDb,
            commonBands = preset.commonBands.map { it.copy() },
            leftPreampDb = preset.leftPreampDb,
            leftBands = preset.leftBands.map { it.copy() },
            rightPreampDb = preset.rightPreampDb,
            rightBands = preset.rightBands.map { it.copy() },
            channelSwap = preset.channelSwap,
            swapPosition = preset.swapPosition
        )

        fun fromJson(obj: JSONObject): EqPresetBaseline {
            fun parseBands(key: String): List<PeqBand> {
                val arr = obj.optJSONArray(key) ?: return emptyList()
                return buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(PeqBand.fromJson(it)) }
                    }
                }
            }
            val posName = obj.optString("swapPosition", SwapPosition.BEFORE_INDEPENDENT_EQ.name)
            val swapPos = SwapPosition.entries.firstOrNull { it.name == posName }
                ?: SwapPosition.BEFORE_INDEPENDENT_EQ
            return EqPresetBaseline(
                commonPreampDb = obj.optDouble("commonPreampDb", 0.0),
                commonBands = parseBands("commonBands"),
                leftPreampDb = obj.optDouble("leftPreampDb", 0.0),
                leftBands = parseBands("leftBands"),
                rightPreampDb = obj.optDouble("rightPreampDb", 0.0),
                rightBands = parseBands("rightBands"),
                channelSwap = obj.optBoolean("channelSwap", false),
                swapPosition = swapPos
            )
        }
    }
}

data class EqPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val commonPreampDb: Double = 0.0,
    val commonBands: List<PeqBand> = emptyList(),
    val leftPreampDb: Double = 0.0,
    val leftBands: List<PeqBand> = emptyList(),
    val rightPreampDb: Double = 0.0,
    val rightBands: List<PeqBand> = emptyList(),
    val channelSwap: Boolean = false,
    val swapPosition: SwapPosition = SwapPosition.BEFORE_INDEPENDENT_EQ,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val baseline: EqPresetBaseline? = null
) {
    val isModifiedFromBaseline: Boolean
        get() = baseline != null && !matchesBaseline(baseline)

    fun matchesBaseline(b: EqPresetBaseline): Boolean {
        fun bandsEqual(a: List<PeqBand>, other: List<PeqBand>): Boolean {
            if (a.size != other.size) return false
            for (i in a.indices) {
                val x = a[i]
                val y = other[i]
                if (x.enabled != y.enabled || x.type != y.type) return false
                if (abs(x.frequencyHz - y.frequencyHz) > 1e-3) return false
                if (abs(x.gainDb - y.gainDb) > 1e-4) return false
                if (abs(x.q - y.q) > 1e-4) return false
            }
            return true
        }
        return abs(commonPreampDb - b.commonPreampDb) <= 1e-4 &&
            abs(leftPreampDb - b.leftPreampDb) <= 1e-4 &&
            abs(rightPreampDb - b.rightPreampDb) <= 1e-4 &&
            channelSwap == b.channelSwap &&
            swapPosition == b.swapPosition &&
            bandsEqual(commonBands, b.commonBands) &&
            bandsEqual(leftBands, b.leftBands) &&
            bandsEqual(rightBands, b.rightBands)
    }

    fun revertToBaseline(): EqPreset {
        val b = baseline ?: return this
        return copy(
            commonPreampDb = b.commonPreampDb,
            commonBands = b.commonBands.map { it.copy() },
            leftPreampDb = b.leftPreampDb,
            leftBands = b.leftBands.map { it.copy() },
            rightPreampDb = b.rightPreampDb,
            rightBands = b.rightBands.map { it.copy() },
            channelSwap = b.channelSwap,
            swapPosition = b.swapPosition,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    fun withCapturedBaseline(): EqPreset = copy(baseline = EqPresetBaseline.fromPreset(this))

    fun preampFor(target: PeqChannelTarget): Double = when (target) {
        PeqChannelTarget.COMMON -> commonPreampDb
        PeqChannelTarget.LEFT -> leftPreampDb
        PeqChannelTarget.RIGHT -> rightPreampDb
    }

    fun bandsFor(target: PeqChannelTarget): List<PeqBand> = when (target) {
        PeqChannelTarget.COMMON -> commonBands
        PeqChannelTarget.LEFT -> leftBands
        PeqChannelTarget.RIGHT -> rightBands
    }

    fun withTarget(
        target: PeqChannelTarget,
        preampDb: Double = preampFor(target),
        bands: List<PeqBand> = bandsFor(target)
    ): EqPreset = when (target) {
        PeqChannelTarget.COMMON -> copy(
            commonPreampDb = preampDb,
            commonBands = bands,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        PeqChannelTarget.LEFT -> copy(
            leftPreampDb = preampDb,
            leftBands = bands,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        PeqChannelTarget.RIGHT -> copy(
            rightPreampDb = preampDb,
            rightBands = bands,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("commonPreampDb", commonPreampDb)
        put("commonBands", JSONArray().apply { commonBands.forEach { put(it.toJson()) } })
        put("leftPreampDb", leftPreampDb)
        put("leftBands", JSONArray().apply { leftBands.forEach { put(it.toJson()) } })
        put("rightPreampDb", rightPreampDb)
        put("rightBands", JSONArray().apply { rightBands.forEach { put(it.toJson()) } })
        put("channelSwap", channelSwap)
        put("swapPosition", swapPosition.name)
        put("updatedAtEpochMs", updatedAtEpochMs)
        if (baseline != null) {
            put("baseline", baseline.toJson())
        }
    }

    companion object {
        fun fromJsonWithSwapFlag(obj: JSONObject): Pair<EqPreset, Boolean> {
            fun parseBands(key: String): List<PeqBand> {
                val arr = obj.optJSONArray(key) ?: return emptyList()
                return buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(PeqBand.fromJson(it)) }
                    }
                }
            }
            val hasSwapKey = obj.has("channelSwap")
            val posName = obj.optString("swapPosition", SwapPosition.BEFORE_INDEPENDENT_EQ.name)
            val swapPos = SwapPosition.entries.firstOrNull { it.name == posName }
                ?: SwapPosition.BEFORE_INDEPENDENT_EQ
            val baseline = obj.optJSONObject("baseline")?.let { EqPresetBaseline.fromJson(it) }
            val preset = EqPreset(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "Untitled"),
                commonPreampDb = obj.optDouble("commonPreampDb", 0.0),
                commonBands = parseBands("commonBands"),
                leftPreampDb = obj.optDouble("leftPreampDb", 0.0),
                leftBands = parseBands("leftBands"),
                rightPreampDb = obj.optDouble("rightPreampDb", 0.0),
                rightBands = parseBands("rightBands"),
                channelSwap = obj.optBoolean("channelSwap", false),
                swapPosition = swapPos,
                updatedAtEpochMs = obj.optLong("updatedAtEpochMs", System.currentTimeMillis()),
                baseline = baseline
            )
            return preset to hasSwapKey
        }

        fun fromJson(obj: JSONObject): EqPreset = fromJsonWithSwapFlag(obj).first
    }
}

data class ActiveDspConfig(
    val bypass: Boolean = false,
    val channelSwap: Boolean = false,
    val swapPosition: SwapPosition = SwapPosition.BEFORE_INDEPENDENT_EQ,
    val preset: EqPreset
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bypass", bypass)
        put("channelSwap", channelSwap)
        put("swapPosition", swapPosition.name)
        put("preset", preset.toJson())
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun fromJsonString(json: String): ActiveDspConfig {
            val obj = JSONObject(json)
            val posName = obj.optString("swapPosition", SwapPosition.BEFORE_INDEPENDENT_EQ.name)
            val swapPos = SwapPosition.entries.firstOrNull { it.name == posName }
                ?: SwapPosition.BEFORE_INDEPENDENT_EQ
            val presetObj = obj.getJSONObject("preset")
            return ActiveDspConfig(
                bypass = obj.optBoolean("bypass", false),
                channelSwap = obj.optBoolean("channelSwap", false),
                swapPosition = swapPos,
                preset = EqPreset.fromJson(presetObj)
            )
        }
    }
}

enum class DeviceCategory {
    SPEAKER,
    BLUETOOTH,
    USB_DAC,
    WIRED
}

enum class DeviceRuleAction {
    FOLLOW_MANUAL,
    PRESET,
    EQ_OFF
}

data class OutputDeviceSnapshot(
    val deviceKey: String,
    val displayName: String,
    val category: DeviceCategory,
    val address: String = "",
    val isActive: Boolean = false
)

data class DeviceRoutingRule(
    val deviceKey: String,
    val displayName: String,
    val category: DeviceCategory,
    val action: DeviceRuleAction = DeviceRuleAction.FOLLOW_MANUAL,
    val presetId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceKey", deviceKey)
        put("displayName", displayName)
        put("category", category.name)
        put("action", action.name)
        put("presetId", presetId ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(obj: JSONObject): DeviceRoutingRule {
            val cat = DeviceCategory.entries.firstOrNull { it.name == obj.optString("category") }
                ?: DeviceCategory.SPEAKER
            val action = DeviceRuleAction.entries.firstOrNull { it.name == obj.optString("action") }
                ?: DeviceRuleAction.FOLLOW_MANUAL
            val pid = if (obj.isNull("presetId")) null else obj.optString("presetId").takeIf { it.isNotEmpty() }
            return DeviceRoutingRule(
                deviceKey = obj.optString("deviceKey"),
                displayName = obj.optString("displayName", cat.name),
                category = cat,
                action = action,
                presetId = pid
            )
        }
    }
}

/** Installed package candidate shown in the per-app bypass selector. */
data class AppBypassCandidate(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystemApp: Boolean,
    val hasLauncherEntry: Boolean
)

/** Resolved bypass rule entry for display in Routing tab. */
data class BypassedAppItem(
    val packageName: String,
    val displayName: String,
    val uid: Int?,
    val isInstalled: Boolean
)

/**
 * Merges mandatory loopback-prevention UIDs ([serviceUid], [clientAppUid]) with user-selected
 * bypass UIDs, dropping non-positive entries while preserving insertion order without duplicates.
 */
fun mergeExcludedUids(
    serviceUid: Int,
    clientAppUid: Int,
    extraExcludedUids: IntArray?
): IntArray {
    val merged = LinkedHashSet<Int>()
    if (serviceUid > 0) merged.add(serviceUid)
    if (clientAppUid > 0) merged.add(clientAppUid)
    extraExcludedUids?.forEach { uid ->
        if (uid > 0) merged.add(uid)
    }
    return merged.toIntArray()
}
