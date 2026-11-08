package com.phlox.tvwebbrowser.model

import org.json.JSONObject

/**
 * The video stream a download saves as one MP4 file ([Download.streamSpec]): the variant of it the user chose.
 * The download service reads [url] (and [audioUrl]) again each time it starts or resumes, so the addresses of the
 * segments are fresh.
 */
data class StreamSpec(
    val kind: Kind,
    /** The media playlist of the chosen variant (HLS), the manifest (DASH) */
    val url: String,
    /** The height of its video, null if unknown */
    val height: Int? = null,
    /** The bitrate of the whole of it, video and audio (bits per second), null if unknown */
    val bitrate: Int? = null,
    /** Its duration in seconds, null if unknown */
    val duration: Double? = null,
    /** HLS: the media playlist of its audio when the audio is a track of its own (a rendition) */
    val audioUrl: String? = null,
    /** DASH: the id of the representation of the video, and its bitrate, to find it in each period */
    val videoId: String? = null,
    val videoBitrate: Int? = null,
    /** DASH: the id of the representation of the audio, and its language */
    val audioId: String? = null,
    val audioLanguage: String? = null,
) {
    enum class Kind { HLS, DASH }

    /** The size of the file it makes, roughly; 0 if unknown */
    val estimatedSize: Long
        get() = if (bitrate != null && duration != null && bitrate > 0 && duration > 0) (bitrate / 8.0 * duration).toLong() else 0L

    fun toJson(): String = JSONObject().apply {
        put("kind", kind.name)
        put("url", url)
        height?.let { put("height", it) }
        bitrate?.let { put("bitrate", it) }
        duration?.let { put("duration", it) }
        audioUrl?.let { put("audioUrl", it) }
        videoId?.let { put("videoId", it) }
        videoBitrate?.let { put("videoBitrate", it) }
        audioId?.let { put("audioId", it) }
        audioLanguage?.let { put("audioLanguage", it) }
    }.toString()

    companion object {
        /** Null for a json that is not a stream this version knows */
        fun fromJson(json: String?): StreamSpec? {
            if (json.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(json)
                val kind = Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
                fun int(name: String) = if (o.has(name)) o.getInt(name) else null
                fun string(name: String) = if (o.has(name)) o.getString(name) else null
                StreamSpec(kind, o.getString("url"), height = int("height"), bitrate = int("bitrate"),
                    duration = if (o.has("duration")) o.getDouble("duration") else null,
                    audioUrl = string("audioUrl"), videoId = string("videoId"), videoBitrate = int("videoBitrate"),
                    audioId = string("audioId"), audioLanguage = string("audioLanguage"))
            } catch (e: Exception) {
                null
            }
        }
    }
}
