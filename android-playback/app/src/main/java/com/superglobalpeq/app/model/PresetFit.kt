package com.superglobalpeq.app.model

import org.json.JSONObject

data class PresetFit(
    val device: String,
    val tips: String,
    val size: String,
    val target: String,
    val fitter: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device", device)
        .put("tips", tips)
        .put("size", size)
        .put("target", target)
        .put("fitter", fitter)

    companion object {
        fun fromJson(obj: JSONObject): PresetFit? {
            fun field(key: String): String = (obj.opt(key) as? String).orEmpty().take(160)
                .map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
                .joinToString("").replace(Regex("\\s+"), " ").trim()

            return PresetFit(field("device"), field("tips"), field("size"), field("target"), field("fitter"))
                .takeIf { listOf(it.device, it.tips, it.size, it.target, it.fitter).any(String::isNotEmpty) }
        }
    }
}
