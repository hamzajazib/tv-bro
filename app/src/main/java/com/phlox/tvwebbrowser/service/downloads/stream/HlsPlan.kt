package com.phlox.tvwebbrowser.service.downloads.stream

import android.net.Uri
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * What to download of an HLS media playlist to save it as one file: its segments in order, with their keys
 * (AES-128) and init segments (fMP4), grouped into [chunks] that the muxer reads as one stream each.
 */
class HlsPlan(
    val segments: List<Segment>,
    /** The init segments the [segments] point to by [Segment.init] */
    val inits: List<Piece>,
    /** The duration of the stream in microseconds */
    val durationUs: Long,
) {
    /** A range of a file: the whole of [url] when [length] is null */
    data class Piece(val url: String, val offset: Long = 0, val length: Long? = null, val key: Key? = null)

    /** An AES-128 key of whole segments, and the IV of a segment (16 bytes) */
    class Key(val url: String, val iv: ByteArray)

    data class Segment(
        val index: Int,
        val piece: Piece,
        /** Index in [inits], null for segments without one (MPEG-TS) */
        val init: Int?,
        /** Segments of another number follow a discontinuity: their timestamps start anew */
        val discontinuity: Int,
        val startUs: Long,
        val durationUs: Long,
    )

    /** Segments the muxer reads as one stream: the same init segment, no discontinuity between them */
    class Chunk(val segments: List<Segment>) {
        val init: Int? get() = segments.first().init
        val startUs: Long get() = segments.first().startUs
    }

    val chunks: List<Chunk>
        get() {
            val result = ArrayList<Chunk>()
            var current = ArrayList<Segment>()
            for (segment in segments) {
                val last = current.lastOrNull()
                if (last != null && (last.discontinuity != segment.discontinuity || last.init != segment.init)) {
                    result += Chunk(current)
                    current = ArrayList()
                }
                current += segment
            }
            if (current.isNotEmpty()) result += Chunk(current)
            return result
        }

    /** Why a playlist can not be saved */
    enum class Problem { LIVE, PROTECTED, NOT_MEDIA, EMPTY }

    class UnsupportedStreamException(val problem: Problem) : IOException("Unsupported stream: $problem")

    companion object {
        /** `#EXT-X-KEY` lines, to find the encryption Media3 ignores (SAMPLE-AES with an identity key) */
        private val KEY_LINE = Regex("^#EXT-X-(?:SESSION-)?KEY:(.*)$", RegexOption.MULTILINE)
        private val METHOD = Regex("METHOD=([A-Z0-9-]+)")
        private val KEYFORMAT = Regex("KEYFORMAT=\"([^\"]*)\"")

        /**
         * The plan of the media playlist [text] read from [url]
         * @throws UnsupportedStreamException for a live, protected or empty stream, or a multivariant playlist
         */
        fun parse(text: String, url: String): HlsPlan {
            if (isProtected(text)) throw UnsupportedStreamException(Problem.PROTECTED)
            val playlist = HlsPlaylistParser().parse(Uri.parse(url), ByteArrayInputStream(text.toByteArray()))
            if (playlist is HlsMultivariantPlaylist) throw UnsupportedStreamException(Problem.NOT_MEDIA)
            return of(playlist as HlsMediaPlaylist)
        }

        /** The encryption of segments other than AES-128 with an identity key: DRM or SAMPLE-AES */
        fun isProtected(text: String): Boolean = KEY_LINE.findAll(text).any { line ->
            val attributes = line.groupValues[1]
            val method = METHOD.find(attributes)?.groupValues?.get(1) ?: return@any false
            val keyFormat = KEYFORMAT.find(attributes)?.groupValues?.get(1) ?: "identity"
            method != "NONE" && (method != "AES-128" || keyFormat != "identity")
        }

        fun of(playlist: HlsMediaPlaylist): HlsPlan {
            if (!playlist.hasEndTag && playlist.playlistType != HlsMediaPlaylist.PLAYLIST_TYPE_VOD) {
                throw UnsupportedStreamException(Problem.LIVE)
            }
            if (playlist.protectionSchemes != null || playlist.segments.any { it.drmInitData != null }) {
                throw UnsupportedStreamException(Problem.PROTECTED)
            }
            val base = playlist.baseUri
            val inits = ArrayList<Piece>()
            val initIndexes = HashMap<HlsMediaPlaylist.Segment, Int>()
            val segments = ArrayList<Segment>()
            playlist.segments.forEachIndexed { i, segment ->
                if (segment.hasGapTag) return@forEachIndexed
                val mediaSequence = playlist.mediaSequence + i
                val init = segment.initializationSegment?.let { initSegment ->
                    initIndexes.getOrPut(initSegment) {
                        inits += pieceOf(base, initSegment, mediaSequence)
                        inits.size - 1
                    }
                }
                segments += Segment(segments.size, pieceOf(base, segment, mediaSequence), init,
                    playlist.discontinuitySequence + segment.relativeDiscontinuitySequence,
                    segment.relativeStartTimeUs, segment.durationUs)
            }
            if (segments.isEmpty()) throw UnsupportedStreamException(Problem.EMPTY)
            return HlsPlan(segments, inits, playlist.durationUs)
        }

        private fun pieceOf(base: String, segment: HlsMediaPlaylist.Segment, mediaSequence: Long): Piece {
            val key = segment.fullSegmentEncryptionKeyUri?.let {
                Key(UriUtil.resolve(base, it), ivOf(segment.encryptionIV, mediaSequence))
            }
            val length = segment.byteRangeLength.takeIf { it != androidx.media3.common.C.LENGTH_UNSET.toLong() }
            return Piece(UriUtil.resolve(base, segment.url), if (length != null) segment.byteRangeOffset else 0, length, key)
        }

        /** The IV of the key tag (`0x…`), else the media sequence number of the segment, both as 16 bytes */
        fun ivOf(iv: String?, mediaSequence: Long): ByteArray {
            val result = ByteArray(16)
            if (iv != null) {
                val hex = iv.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
                for (i in 0 until 16) result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            } else {
                var value = mediaSequence
                for (i in 15 downTo 8) {
                    result[i] = (value and 0xFF).toByte()
                    value = value shr 8
                }
            }
            return result
        }
    }
}
