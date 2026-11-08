package com.phlox.tvwebbrowser.service.downloads.stream

import java.io.IOException

/**
 * What to download of a video stream to save it as one file: the segments of its video (with the audio inside, or
 * not) and of its [audio] track of its own (HLS renditions, DASH), each track packed by [StreamMuxer] chunk by chunk.
 */
class StreamPlan(val video: TrackPlan, val audio: TrackPlan? = null) {
    val tracks: List<TrackPlan> get() = listOfNotNull(video, audio)

    val durationUs: Long get() = video.durationUs

    /** The chunks of the video, each with the chunk of the audio of the same part (none if the audio has none) */
    val chunkPairs: List<Pair<TrackPlan.Chunk, TrackPlan.Chunk?>>
        get() {
            val audioChunks = audio?.chunks.orEmpty()
            return video.chunks.map { chunk -> chunk to audioChunks.firstOrNull { it.part == chunk.part } }
        }
}

/**
 * The segments of one track in order, with their keys (AES-128) and init segments (fMP4), in parts: segments of
 * another part (after an HLS discontinuity, of another DASH period) start their timestamps anew. [name] tells the
 * files of its segments from those of other tracks.
 */
class TrackPlan(
    val name: String,
    val segments: List<Segment>,
    /** The init segments the [segments] point to by [Segment.init] */
    val inits: List<Piece>,
    /** The duration of the track in microseconds */
    val durationUs: Long,
) {
    /** A range of a file: the whole of [url] when [length] is null */
    data class Piece(val url: String, val offset: Long = 0, val length: Long? = null, val key: Key? = null)

    /** An AES-128 key of whole segments, and the IV of a segment (16 bytes) */
    class Key(val url: String, val iv: ByteArray)

    data class Segment(
        val index: Int,
        val piece: Piece,
        /** Index in [inits], null for segments without one (MPEG-TS, a whole file) */
        val init: Int?,
        /** The part of the stream: an HLS discontinuity sequence number, the index of a DASH period */
        val part: Int,
        /** When it starts in the stream */
        val startUs: Long,
        val durationUs: Long,
    )

    /** Segments the muxer reads as one stream: of one part, after one init segment */
    class Chunk(val segments: List<Segment>) {
        val init: Int? get() = segments.first().init
        val part: Int get() = segments.first().part
        val startUs: Long get() = segments.first().startUs
    }

    val chunks: List<Chunk>
        get() {
            val result = ArrayList<Chunk>()
            var current = ArrayList<Segment>()
            for (segment in segments) {
                val last = current.lastOrNull()
                if (last != null && (last.part != segment.part || last.init != segment.init)) {
                    result += Chunk(current)
                    current = ArrayList()
                }
                current += segment
            }
            if (current.isNotEmpty()) result += Chunk(current)
            return result
        }

    /** What the folder of the segments holds: other values mean the segments there are of another plan */
    val description: String get() = "$name ${segments.size} $durationUs ${inits.size}"
}

/** Why a stream can not be saved */
enum class StreamProblem { LIVE, PROTECTED, NOT_MEDIA, EMPTY }

class UnsupportedStreamException(val problem: StreamProblem) : IOException("Unsupported stream: $problem")
