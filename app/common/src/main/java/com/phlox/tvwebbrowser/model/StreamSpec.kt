package com.phlox.tvwebbrowser.model

import org.json.JSONObject

/**
 * The video stream a download saves as one MP4 file ([Download.streamSpec]): the variant of it the user chose.
 * The download service reads [url] again each time it starts or resumes, so the addresses of the segments are
 * fresh.
 */
data class StreamSpec(
    val kind: Kind,
    /** The media playlist of the chosen variant (HLS) */
    val url: String,
    /** The height of its video, null if unknown */
    val height: Int? = null,
    /** Its bitrate (bits per second), null if unknown */
    val bitrate: Int? = null,
    /** Its duration in seconds, null if unknown */
    val duration: Double? = null,
) {
    enum class Kind { HLS }

    /** The size of the file it makes, roughly; 0 if unknown */
    val estimatedSize: Long
        get() = if (bitrate != null && duration != null && bitrate > 0 && duration > 0) (bitrate / 8.0 * duration).toLong() else 0L

    fun toJson(): String = JSONObject().apply {
        put("kind", kind.name)
        put("url", url)
        height?.let { put("height", it) }
        bitrate?.let { put("bitrate", it) }
        duration?.let { put("duration", it) }
    }.toString()

    companion object {
        /** Null for a json that is not a stream this version knows */
        fun fromJson(json: String?): StreamSpec? {
            if (json.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(json)
                val kind = Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
                StreamSpec(kind, o.getString("url"),
                    height = if (o.has("height")) o.getInt("height") else null,
                    bitrate = if (o.has("bitrate")) o.getInt("bitrate") else null,
                    duration = if (o.has("duration")) o.getDouble("duration") else null)
            } catch (e: Exception) {
                null
            }
        }
    }
}
